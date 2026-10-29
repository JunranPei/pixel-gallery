package com.pixel.gallery.glide

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import com.bumptech.glide.signature.ObjectKey
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import io.github.indexedjpeg.DcJpegPreview
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Trace-only accelerated standard JPEG fit. No prefetch or private disk cache. */
data class NativeJpegFit(val uri: Uri, val rotationDegrees: Int, val modified: Long)

internal class NativeJpegFitLoader(private val context: Context) : ModelLoader<NativeJpegFit, Bitmap> {
    override fun handles(model: NativeJpegFit) = true
    override fun buildLoadData(model: NativeJpegFit, width: Int, height: Int, options: Options) =
        ModelLoader.LoadData(ObjectKey("jpeg-fit-v2-${model.uri}-${model.rotationDegrees}-${model.modified}-${width}x$height"),
            NativeJpegFitFetcher(context, model, width, height))

    class Factory(private val context: Context) : ModelLoaderFactory<NativeJpegFit, Bitmap> {
        override fun build(multiFactory: MultiModelLoaderFactory) = NativeJpegFitLoader(context.applicationContext)
        override fun teardown() = Unit
    }
}

internal class NativeJpegFitFetcher(
    private val context: Context,
    private val model: NativeJpegFit,
    private val width: Int,
    private val height: Int,
) : DataFetcher<Bitmap> {
    private val cancelled = AtomicBoolean(false)
    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        if (cancelled.get()) return
        val token = ViewerLoadMetrics.workStarted("NATIVE_JPEG_FIT", model.uri.toString(), "requested=${width}x$height")
        var owned: Bitmap? = null
        try {
            // Use all EXIF orientations, including mirrors; MediaStore rotation
            // alone cannot describe these. A failed header read goes to Glide.
            val exif = context.contentResolver.openFileDescriptor(model.uri, "r")?.use {
                if (!OsConstants.S_ISREG(Os.fstat(it.fileDescriptor).st_mode)) throw IOException("Non-regular JPEG source")
                ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            } ?: throw IOException("JPEG orientation unavailable")
            val orientation = if (exif != ExifInterface.ORIENTATION_UNDEFINED) exif else when (model.rotationDegrees) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            val swapped = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
            val bitmap = DcJpegPreview.decodeFit(context.contentResolver, model.uri,
                if (swapped) height else width, if (swapped) width else height, cancelled)
            owned = bitmap
            if (cancelled.get()) {
                ViewerLoadMetrics.workCleared(token, "cancelled")
                return
            }
            if (bitmap == null) throw IOException("Use standard JPEG decoder")
            val timings = DcJpegPreview.lastFitTimings()
            val oriented = TransformationUtils.rotateImageExif(Glide.get(context).bitmapPool, bitmap, orientation)
            if (oriented !== bitmap) { owned = oriented; bitmap.recycle() }
            if (cancelled.get()) {
                ViewerLoadMetrics.workCleared(token, "cancelled")
                return
            }
            ViewerLoadMetrics.workReady(token, source = "NATIVE_FIT_SCANLINES",
                detail = "bitmap=${oriented.width}x${oriented.height} orientation=$orientation turbo=3.1.3 " +
                    "sample=${timings[5]} fastEntropy=${timings[6]} directRgba=true " +
                    "headerMs=${timings[0] / 1000000} allocMs=${timings[1] / 1000000} " +
                    "setupMs=${timings[2] / 1000000} scanMs=${timings[3] / 1000000} finishMs=${timings[4] / 1000000}")
            owned = null // Ownership transfers to Glide with the result.
            callback.onDataReady(oriented)
        } catch (e: Exception) {
            if (cancelled.get()) ViewerLoadMetrics.workCleared(token, "cancelled")
            else {
                ViewerLoadMetrics.workFailed(token, e.javaClass.simpleName)
                callback.onLoadFailed(e)
            }
        } finally {
            owned?.takeUnless { it.isRecycled }?.recycle()
        }
    }
    override fun cancel() { cancelled.set(true) }
    override fun cleanup() = Unit
    override fun getDataClass() = Bitmap::class.java
    override fun getDataSource() = DataSource.LOCAL
}
