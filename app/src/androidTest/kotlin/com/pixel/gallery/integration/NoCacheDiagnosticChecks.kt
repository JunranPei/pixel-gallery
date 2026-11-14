package com.pixel.gallery.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.pixel.gallery.BuildConfig
import com.pixel.gallery.glide.FastScreenPreview
import com.pixel.gallery.glide.FastScreenPreviewFetcher
import com.pixel.gallery.glide.MediaStoreThumbnail
import com.pixel.gallery.glide.MediaStoreThumbnailFetcher
import com.pixel.gallery.glide.NativeJpegFit
import com.pixel.gallery.glide.NativeJpegFitFetcher
import com.pixel.gallery.ui.viewer.decoders.FastRegionDecoder
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exercises real Glide, source decoding and disk-cache gates with isolated generated pixels. */
internal class NoCacheDiagnosticChecks(private val context: Context) {
    private fun writeImage(file: File, color: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG) {
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { check(bitmap.compress(format, 100, it)) }
        bitmap.recycle()
    }

    private fun fetch(fetcher: DataFetcher<Bitmap>): Bitmap {
        var result: Bitmap? = null
        var failure: Exception? = null
        fetcher.loadData(Priority.IMMEDIATE, object : DataFetcher.DataCallback<Bitmap> {
            override fun onDataReady(data: Bitmap?) { result = data }
            override fun onLoadFailed(error: Exception) { failure = error }
        })
        fetcher.cleanup()
        return result ?: throw AssertionError("Source fetch failed", failure)
    }

    fun exercise(): String {
        check(BuildConfig.IMAGE_LOAD_NO_CACHE && BuildConfig.VIEWER_METRICS_ENABLED)
        check(context.packageName == "com.pixel.gallery.loadtrace.nocache")
        val report = mutableListOf("Independent package and compile-time no-cache policy enabled")
        val source = File(context.filesDir, "nocache-check-${UUID.randomUUID()}.png")
        val jpegSource = File(context.filesDir, "${source.nameWithoutExtension}.jpg")
        val seededFiles = mutableListOf<File>()
        try {
            writeImage(source, Color.RED)
            val manager = Glide.with(context)
            val sources = mutableListOf<DataSource>()
            fun request() = manager.asBitmap().load(source).override(240, 160).disallowHardwareConfig()
                // Deliberately request caching: the diagnostic backing stores must still refuse it.
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean) = false
                    override fun onResourceReady(resource: Bitmap, model: Any, target: Target<Bitmap>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        sources += dataSource
                        return false
                    }
                }).submit()
            val first = request()
            try {
                check(Color.red(first.get(30, TimeUnit.SECONDS).getPixel(20, 20)) > 240)
                // Same model and signature, first request still active: no active-resource reuse.
                writeImage(source, Color.BLUE)
                val second = request()
                try { check(Color.blue(second.get(30, TimeUnit.SECONDS).getPixel(20, 20)) > 240) }
                finally { manager.clear(second) }
            } finally { manager.clear(first) }
            val third = request()
            try { check(Color.blue(third.get(30, TimeUnit.SECONDS).getPixel(20, 20)) > 240) }
            finally { manager.clear(third) }
            check(sources == listOf(DataSource.LOCAL, DataSource.LOCAL, DataSource.LOCAL)) { "$sources" }
            report += "Repeated identical Glide requests: LOCAL x3; changed source pixels visible while first target remains active"

            for (color in listOf(Color.BLUE, Color.RED)) {
                writeImage(jpegSource, color, Bitmap.CompressFormat.JPEG)
                val native = fetch(NativeJpegFitFetcher(context,
                    NativeJpegFit(Uri.fromFile(jpegSource), 0, 0L), 240, 160))
                val pixel = native.getPixel(native.width / 2, native.height / 2)
                check(if (color == Color.BLUE) Color.blue(pixel) > 240 else Color.red(pixel) > 240)
                native.recycle()
            }
            report += "Native JPEG fit rereads changed source pixels on both calls"

            val uri = Uri.fromFile(source)
            val id = uri.hashCode()
            val gridPoison = File(context.cacheDir, "persistent_grid_thumbnails/aspect_v2_true_${id}_0_0_q75.jpg")
            val previewPoison = File(context.cacheDir, "persistent_viewer_thumbnails/fastpreview_${id}_0.jpg")
            for (file in listOf(gridPoison, previewPoison)) { writeImage(file, Color.GREEN); seededFiles += file }
            val model = MediaStoreThumbnail(uri, "image/png", 0, 0L, 6L * 1024 * 1024,
                allowPersistentCache = true, traceViewerLoad = true)
            repeat(2) {
                val bitmap = fetch(MediaStoreThumbnailFetcher(context, model, 200, 200))
                check(Color.blue(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 240)
                bitmap.recycle()
            }
            report += "Persistent grid poison ignored on both reads; original pixels decoded with system thumbnail bypass"
            repeat(2) {
                val bitmap = fetch(FastScreenPreviewFetcher(context, FastScreenPreview(uri, 0), 200, 200))
                check(Color.blue(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 240)
                bitmap.recycle()
            }
            report += "Persistent fit poison ignored on both reads"

            repeat(2) {
                val decoder = FastRegionDecoder(160, "nocache-check", source.absolutePath, 600, 400)
                decoder.init(context, uri)
                try {
                    val rect = Rect(0, 0, 300, 200)
                    check(!decoder.isRegionCached(rect, 1))
                    val bitmap = decoder.decodeRegion(rect, 1)
                    check(Color.blue(bitmap.getPixel(20, 20)) > 240)
                    check(!decoder.cacheRegion(rect, 1, bitmap))
                    check(!decoder.isRegionCached(rect, 1))
                    bitmap.recycle()
                } finally { decoder.recycle() }
            }
            report += "Two region decoder sessions: source pixels correct; disk-cache reads/writes refused"
            val imageDirs = listOf("image_manager_disk_cache", "persistent_grid_thumbnails",
                "persistent_viewer_thumbnails", "ssiv_tile_cache")
            val unexpected = imageDirs.flatMap { name -> File(context.cacheDir, name).walkTopDown()
                .filter { it.isFile && it !in seededFiles }.toList() }
            check(unexpected.isEmpty()) { "Unexpected cache files: $unexpected" }
            report += "No image-cache files written; only the two deliberately seeded poison fixtures exist"
            check(Glide.get(context).bitmapPool.maxSize == 0L)
            report += "Bitmap reuse pool disabled"
            return report.joinToString("\n")
        } finally {
            source.delete()
            jpegSource.delete()
            seededFiles.forEach { it.delete() }
        }
    }
}
