package com.pixel.gallery.integration

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.pixel.gallery.glide.MediaStoreThumbnail
import com.pixel.gallery.glide.MediaStoreThumbnailFetcher
import com.pixel.gallery.glide.MediaStoreThumbnailLoader
import com.bumptech.glide.load.Options
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import android.os.Process
import kotlin.math.abs

internal class ThumbnailQualityDeviceChecks(private val context: Context) {
    private val base = Uri.parse("content://${context.packageName}.test.thumbnailquality")
    private val version = System.currentTimeMillis()
    private val report = mutableListOf<String>()

    private fun model(name: String, minimum: Int = 512, persistent: Boolean = false) = MediaStoreThumbnail(
        base.buildUpon().appendPath(name).appendQueryParameter("run", version.toString()).build(), "image/jpeg",
        if (name == "rotated" || name == "native-rotated") 90 else 0, version, 8L * 1024 * 1024,
        allowPersistentCache = persistent, traceViewerLoad = true, minimumLongEdgePixels = minimum,
    )

    private fun resetReads() { context.contentResolver.call(base, "reset", null, null) }
    private fun reads() = context.contentResolver.call(base, "counts", null, null)!!.getInt("originalReads")

    private fun fetch(model: MediaStoreThumbnail): Bitmap {
        var bitmap: Bitmap? = null
        var failure: Exception? = null
        val fetcher = MediaStoreThumbnailFetcher(context, model, 512, 512)
        fetcher.loadData(Priority.NORMAL, object : DataFetcher.DataCallback<Bitmap> {
            override fun onDataReady(data: Bitmap?) { bitmap = data }
            override fun onLoadFailed(error: Exception) { failure = error }
        })
        fetcher.cleanup()
        failure?.let { throw it }
        return checkNotNull(bitmap)
    }

    fun exercise(): String {
        // Known-good source proportions and deliberately inadequate system previews.
        for (name in listOf("tiny", "unknown", "rotated", "mismatch", "small")) {
            resetReads()
            val bitmap = fetch(model(name))
            val required = if (name == "small") 80 else 512
            check(maxOf(bitmap.width, bitmap.height) >= required) { "$name: ${bitmap.width}x${bitmap.height}" }
            val ratio = if (name == "rotated") .75 else 4.0 / 3
            check(abs(bitmap.width.toDouble() / bitmap.height - ratio) < .02)
            if (name == "small") check(bitmap.width == 80 && bitmap.height == 60) { "Small source was upscaled" }
            check(reads() > 0)
            report += "$name: original fallback ${bitmap.width}x${bitmap.height}"
            bitmap.recycle()
        }
        resetReads()
        val adequate = fetch(model("adequate"))
        check(maxOf(adequate.width, adequate.height) >= 512)
        check(reads() == 0) { "Adequate provider preview caused an unnecessary original read" }
        report += "adequate system preview reused without original decode"
        adequate.recycle()

        if (com.pixel.gallery.BuildConfig.JPEG_DC_PREVIEW_ENABLED) {
            for (name in listOf("native-large", "native-rotated", "native-pipe")) {
                resetReads()
                fetch(model(name)).also {
                    // Platform streaming fallback rounds this thin JPEG to
                    // 512x4; the DC API explicitly uses ceil, giving 513x5.
                    check(it.width == (if (name == "native-rotated") 5 else if (name == "native-pipe") 512 else 513))
                    check(it.height == (if (name == "native-rotated") 513 else if (name == "native-pipe") 4 else 5))
                    check(it.config == if (name == "native-pipe") Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                    check(reads() == if (name == "native-pipe") 2 else 1)
                    report += "$name: ${it.width}x${it.height}, config=${it.config}, source opens=${reads()}"
                    it.recycle()
                }
            }
        }

        // Grid/default model keeps the fast small preview and the established source key.
        resetReads()
        val legacy = model("tiny", minimum = 0)
        val small = fetch(legacy)
        check(small.width == 160 && small.height == 120 && reads() == 0)
        small.recycle()
        check(legacy == legacy.copy(allowPersistentCache = true, traceViewerLoad = false))
        check(legacy != legacy.copy(minimumLongEdgePixels = 512))
        val loader = MediaStoreThumbnailLoader(context)
        val oldKey = loader.buildLoadData(legacy, 512, 512, Options()).sourceKey
        val newKey = loader.buildLoadData(legacy.copy(minimumLongEdgePixels = 512), 512, 512, Options()).sourceKey
        check(oldKey != newKey)
        report += "default preview and Grid identity preserved; strict preview key isolated"

        for (failureModel in listOf(model("missing"), model("tiny").copy(allowSourceFallback = false))) {
            var rejected = false
            try { fetch(failureModel).recycle() } catch (_: Exception) { rejected = true }
            check(rejected) { "Tiny preview silently satisfied a strict request without a usable original" }
        }
        report += "missing original and disabled source fallback reject inadequate previews cleanly"

        // Even a malformed current-version persistent entry must not bypass resolution validation.
        val cachedModel = model("tiny", persistent = true)
        val id = cachedModel.uri.hashCode()
        val dir = File(context.cacheDir, "persistent_viewer_thumbnails").apply { mkdirs() }
        val decoderVersion = if (com.pixel.gallery.BuildConfig.JPEG_DC_PREVIEW_ENABLED) "dc_v1_" else ""
        val cache = File(dir, "aspect_v2_true_${decoderVersion}min512_512x512_${id}_${version}_0.jpg")
        val tiny = Bitmap.createBitmap(160, 120, Bitmap.Config.RGB_565)
        cache.outputStream().use { tiny.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tiny.recycle()
        resetReads()
        fetch(cachedModel).also { check(maxOf(it.width, it.height) >= 512); it.recycle() }
        check(reads() > 0)
        resetReads()
        fetch(cachedModel).also { check(maxOf(it.width, it.height) >= 512); it.recycle() }
        check(reads() == 0)
        report += "undersized persistent entry repaired; subsequent strict cache hit avoids original"

        // Real Glide resource cache cannot return an older 160 px result to the strict model.
        val manager = Glide.with(context)
        fun glideLoad(model: MediaStoreThumbnail): Pair<Int, DataSource?> {
            val source = AtomicReference<DataSource?>()
            val target = manager.asBitmap().load(model).skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE).downsample(DownsampleStrategy.FIT_CENTER)
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean) = false
                    override fun onResourceReady(resource: Bitmap, model: Any, target: Target<Bitmap>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        source.set(dataSource); return false
                    }
                }).submit(512, 512)
            val result = target.get(30, TimeUnit.SECONDS)
            val edge = maxOf(result.width, result.height)
            manager.clear(target)
            return edge to source.get()
        }
        check(glideLoad(legacy).first < 512)
        val strict = legacy.copy(minimumLongEdgePixels = 512)
        check(glideLoad(strict).first >= 512)
        // Encoding follows the callback; wait on the same key after that work settles.
        Thread.sleep(250)
        val reused = glideLoad(strict)
        check(reused.first >= 512 && reused.second == DataSource.RESOURCE_DISK_CACHE)
        report += "Glide old-small resource cache isolated; strict resource cache reused"

        resetReads()
        val initialPriority = Process.getThreadPriority(Process.myTid())
        fetch(model("odd")).also {
            check(it.width == 801 && it.height == 601) { "Odd JPEG result: ${it.width}x${it.height}" }
            it.recycle()
        }
        check(reads() == 1) { "Odd JPEG required extra source opens: ${reads()}" }
        check(Process.getThreadPriority(Process.myTid()) == initialPriority)
        report += "odd JPEG 1601x1201 -> 801x601 with one source open; caller priority restored"

        resetReads()
        val callbacks = AtomicInteger()
        val cancelledCallback = object : DataFetcher.DataCallback<Bitmap> {
            override fun onDataReady(data: Bitmap?) { data?.recycle(); callbacks.incrementAndGet() }
            override fun onLoadFailed(error: Exception) { callbacks.incrementAndGet() }
        }
        val before = MediaStoreThumbnailFetcher(context, model("tiny"), 512, 512)
        before.cancel()
        before.loadData(Priority.NORMAL, cancelledCallback)
        check(reads() == 0 && callbacks.get() == 0)
        before.cleanup()
        report += "cancel before fetch performs no original reads or callbacks"

        val during = MediaStoreThumbnailFetcher(context, model("slow"), 512, 512)
        val worker = Thread { during.loadData(Priority.NORMAL, cancelledCallback) }
        worker.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (context.contentResolver.call(base, "counts", null, null)!!.getInt("bytesWritten") == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        try {
            check(context.contentResolver.call(base, "counts", null, null)!!.getInt("bytesWritten") > 0) { "Slow fixture did not start reading" }
            during.cancel()
            worker.join(3000)
            check(!worker.isAlive && callbacks.get() == 0) { "Cancelled decode still running or delivered a callback" }
        } finally { during.cancel(); during.cleanup() }
        report += "cancel during provider pipe read stops decode without callback"
        return report.joinToString("\n")
    }
}
