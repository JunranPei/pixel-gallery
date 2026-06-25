package com.pixel.gallery.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import com.pixel.gallery.utils.BitmapUtils.applyExifOrientation
import java.io.IOException

data class FastScreenPreview(
    val uri: Uri,
    val rotationDegrees: Int
)

internal class FastScreenPreviewLoader(private val context: Context) : ModelLoader<FastScreenPreview, Bitmap> {
    override fun buildLoadData(
        model: FastScreenPreview,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<Bitmap> {
        val cacheKey = ObjectKey("${model.uri}-fastpreview-${width}x${height}")
        return ModelLoader.LoadData(cacheKey, FastScreenPreviewFetcher(context, model, width, height))
    }

    override fun handles(model: FastScreenPreview): Boolean = true

    internal class Factory(private val context: Context) : ModelLoaderFactory<FastScreenPreview, Bitmap> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<FastScreenPreview, Bitmap> {
            return FastScreenPreviewLoader(context.applicationContext)
        }

        override fun teardown() {}
    }
}

internal class FastScreenPreviewFetcher(
    private val context: Context,
    private val model: FastScreenPreview,
    private val width: Int,
    private val height: Int
) : DataFetcher<Bitmap> {

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        val originalPriority = try {
            Process.getThreadPriority(Process.myTid())
        } catch (e: Exception) {
            Process.THREAD_PRIORITY_BACKGROUND
        }

        try {
            var bitmap: Bitmap? = null
            val resolver = context.contentResolver

            // 1. Decode bounds
            resolver.openInputStream(model.uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(stream, null, options)

                val srcWidth = options.outWidth
                val srcHeight = options.outHeight
                var inSampleSize = 1
                
                // If requested width/height is invalid, fallback to standard 1080p
                val targetWidth = if (width > 0 && width != com.bumptech.glide.request.target.Target.SIZE_ORIGINAL) width else 1080
                val targetHeight = if (height > 0 && height != com.bumptech.glide.request.target.Target.SIZE_ORIGINAL) height else 1920

                if (srcWidth > targetWidth || srcHeight > targetHeight) {
                    val halfWidth = srcWidth / 2
                    val halfHeight = srcHeight / 2
                    while (halfWidth / inSampleSize >= targetWidth && halfHeight / inSampleSize >= targetHeight) {
                        inSampleSize *= 2
                    }
                }

                // 2. Decode bitmap with calculated inSampleSize and RGB_565 config
                val decodeOptions = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }

                // We must open a fresh InputStream because the previous one was consumed by bounds decoding
                resolver.openInputStream(model.uri)?.use { fallbackStream ->
                    val decoded = BitmapFactory.decodeStream(fallbackStream, null, decodeOptions)
                    if (decoded != null) {
                        bitmap = applyExifOrientation(context, decoded, model.rotationDegrees, false)
                    }
                }
            }

            if (bitmap != null) {
                callback.onDataReady(bitmap)
            } else {
                callback.onLoadFailed(IOException("Failed to fast-decode preview for uri=${model.uri}"))
            }
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        } finally {
            try {
                Process.setThreadPriority(originalPriority)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun cleanup() {}

    override fun cancel() {}

    override fun getDataClass(): Class<Bitmap> = Bitmap::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}
