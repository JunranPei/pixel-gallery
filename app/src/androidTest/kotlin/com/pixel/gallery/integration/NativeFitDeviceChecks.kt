package com.pixel.gallery.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.pixel.gallery.glide.NativeJpegFit
import com.pixel.gallery.glide.NativeJpegFitFetcher
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

internal class NativeFitDeviceChecks(private val context: Context) {
    private val base = Uri.parse("content://${context.packageName}.test.thumbnailquality")
    private val version = System.currentTimeMillis()
    private val report = mutableListOf<String>()
    private fun uri(name: String) = base.buildUpon().appendPath(name).appendQueryParameter("run", version.toString()).build()
    private fun model(name: String, revision: Long = version) = NativeJpegFit(uri(name), 0, revision)
    private fun resetReads() { context.contentResolver.call(base, "reset", null, null) }
    private fun reads() = context.contentResolver.call(base, "counts", null, null)!!.getInt("originalReads")

    private fun fetch(name: String): Bitmap? {
        var result: Bitmap? = null
        var failure: Exception? = null
        val fetcher = NativeJpegFitFetcher(context, model(name), 512, 512)
        fetcher.loadData(Priority.IMMEDIATE, object : DataFetcher.DataCallback<Bitmap> {
            override fun onDataReady(data: Bitmap?) { result = data }
            override fun onLoadFailed(error: Exception) { failure = error }
        })
        fetcher.cleanup()
        if (result == null) check(failure != null) { "No success or failure callback" }
        return result
    }

    private fun assertNear(expected: Int, actual: Int) {
        check(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 4 &&
            kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 4 &&
            kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 4) { "Orientation/color mismatch: $expected vs $actual" }
    }

    fun exercise(): String = checkNotNull(context.contentResolver.acquireContentProviderClient(base)).use {
        // The generated pipe writer lives in the test provider process. Keep its
        // client relationship for the entire read, including asynchronous writes.
        exerciseWithProvider()
    }

    private fun exerciseWithProvider(): String {
        check(com.pixel.gallery.BuildConfig.JPEG_FIT_PREVIEW_ENABLED)
        val glide = Glide.with(context)
        for (orientation in 1..8) {
            val name = "native-orientation-$orientation"
            val actual = checkNotNull(fetch(name)) { "Native fit unavailable for EXIF $orientation" }
            val referenceTarget = glide.asBitmap().load(uri(name)).override(1025, 1025)
                .fitCenter().diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true).submit()
            try {
                val expected = referenceTarget.get(30, TimeUnit.SECONDS)
                val swapped = orientation >= 5
                check(actual.width == if (swapped) 9 else 1025)
                check(actual.height == if (swapped) 1025 else 9)
                for (fraction in listOf(.25f, .75f)) {
                    assertNear(expected.getPixel((expected.width * fraction).toInt(), (expected.height * fraction).toInt()),
                        actual.getPixel((actual.width * fraction).toInt(), (actual.height * fraction).toInt()))
                }
                check(Color.alpha(actual.getPixel(0, 0)) == 255)
                report += "EXIF $orientation: native ${actual.width}x${actual.height}, colors match platform"
            } finally { glide.clear(referenceTarget); actual.recycle() }
        }
        val ordinary = checkNotNull(fetch("tiny"))
        check(ordinary.width == 800 && ordinary.height == 600)
        ordinary.recycle()
        report += "ordinary 1600x1200 JPEG: native 1/2 fit 800x600"
        for (name in listOf("native-wide", "native-pipe", "missing")) {
            check(fetch(name) == null) { "$name must leave native path" }
            if (name != "missing") {
                // FutureTarget is also a request listener and reports the first
                // request's failure before its .error() target finishes. Observe
                // final target callbacks, as the actual ImageView does.
                val ready = CountDownLatch(1)
                val dimensions = AtomicReference<Pair<Int, Int>>()
                val target = object : CustomTarget<Bitmap>(512, 512) {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        dimensions.set(resource.width to resource.height); ready.countDown()
                    }
                    override fun onLoadFailed(errorDrawable: Drawable?) { ready.countDown() }
                    override fun onLoadCleared(placeholder: Drawable?) = Unit
                }
                val main = Handler(Looper.getMainLooper())
                main.post { glide.asBitmap().load(model(name)).override(512).fitCenter()
                    .error(glide.asBitmap().load(uri(name)).override(512).fitCenter())
                    .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true).into(target) }
                try {
                    check(ready.await(30, TimeUnit.SECONDS))
                    val size = checkNotNull(dimensions.get()) { "$name final target failed" }
                    check(size.first == 512 && size.second > 0)
                    report += "$name: actual Glide error fallback completed ${size.first}x${size.second}"
                } finally { main.post { glide.clear(target) } }
            } else report += "missing: recoverable native failure"
        }
        resetReads()
        val cancelled = NativeJpegFitFetcher(context, model("native-large"), 512, 512)
        cancelled.cancel()
        cancelled.loadData(Priority.NORMAL, object : DataFetcher.DataCallback<Bitmap> {
            override fun onDataReady(data: Bitmap?) { error("Cancelled fetch published") }
            override fun onLoadFailed(error: Exception) { throw error }
        })
        check(reads() == 0)
        report += "cancel before start: no source read/callback"

        // Wait for the asynchronous resource write by polling cache-only requests.
        val cacheModel = model("native-large")
        val first = glide.asBitmap().load(cacheModel).override(512).fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE).skipMemoryCache(true).submit()
        first.get(30, TimeUnit.SECONDS)
        glide.clear(first)
        val readsBeforeHit = reads()
        var cacheHit = false
        val limit = android.os.SystemClock.elapsedRealtime() + 5000
        while (!cacheHit && android.os.SystemClock.elapsedRealtime() < limit) {
            val source = AtomicReference<DataSource>()
            val cached = glide.asBitmap().load(cacheModel).override(512).fitCenter()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE).skipMemoryCache(true).onlyRetrieveFromCache(true)
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean) = false
                    override fun onResourceReady(resource: Bitmap, model: Any, target: Target<Bitmap>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        source.set(dataSource); return false
                    }
                }).submit()
            try { cached.get(5, TimeUnit.SECONDS); cacheHit = source.get() == DataSource.RESOURCE_DISK_CACHE }
            catch (_: java.util.concurrent.ExecutionException) { android.os.SystemClock.sleep(50) }
            finally { glide.clear(cached) }
        }
        check(cacheHit && reads() == readsBeforeHit) { "Resource cache reread original" }
        val changed = glide.asBitmap().load(model("native-large", version + 1)).override(512).fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE).skipMemoryCache(true).submit()
        try { changed.get(30, TimeUnit.SECONDS); check(reads() > readsBeforeHit) }
        finally { glide.clear(changed) }
        report += "resource disk cache reuse and modified-version invalidation: pass"
        return report.joinToString("\n")
    }
}
