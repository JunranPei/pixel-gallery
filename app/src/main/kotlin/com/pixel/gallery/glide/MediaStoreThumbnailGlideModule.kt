package com.pixel.gallery.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import com.pixel.gallery.utils.BitmapUtils.applyExifOrientation
import com.pixel.gallery.utils.MimeTypes.isVideo
import com.pixel.gallery.utils.MimeTypes.needRotationAfterContentResolverThumbnail
import com.pixel.gallery.utils.StorageUtils.openMetadataRetriever
import com.pixel.gallery.utils.UriUtils.tryParseId
import android.os.Process
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

data class MediaStoreThumbnail(
    val uri: Uri,
    val mimeType: String,
    val rotationDegrees: Int,
    val dateModifiedMillis: Long,
    val sizeBytes: Long? = null
)

internal class MediaStoreThumbnailLoader(private val context: Context) : ModelLoader<MediaStoreThumbnail, Bitmap> {
    override fun buildLoadData(
        model: MediaStoreThumbnail,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<Bitmap> {
        val cacheKey = ObjectKey("${model.uri}-${model.dateModifiedMillis}-${model.rotationDegrees}-${width}x${height}")
        return ModelLoader.LoadData(cacheKey, MediaStoreThumbnailFetcher(context, model, width, height))
    }

    override fun handles(model: MediaStoreThumbnail): Boolean = true

    internal class Factory(private val context: Context) : ModelLoaderFactory<MediaStoreThumbnail, Bitmap> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<MediaStoreThumbnail, Bitmap> {
            return MediaStoreThumbnailLoader(context.applicationContext)
        }

        override fun teardown() {}
    }
}

internal class MediaStoreThumbnailFetcher(
    private val context: Context,
    private val model: MediaStoreThumbnail,
    private val width: Int,
    private val height: Int
) : DataFetcher<Bitmap> {

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        // Force the execution of thumbnail loading/decoding to the lowest priority.
        // This binds the thread to run ONLY on the CPU's LITTLE (efficiency) cores, preventing big cores from waking up.
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
        } catch (e: Exception) {
            // ignore
        }

        try {
            var bitmap: Bitmap? = null
            val resolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Limit targetSize to a max of 180x180 to hit system pre-generated MINI_KIND/MICRO_KIND caches directly
                val targetSize = Size(
                    if (width > 0) minOf(width, 180) else 180,
                    if (height > 0) minOf(height, 180) else 180
                )
                try {
                    bitmap = resolver.loadThumbnail(model.uri, targetSize, null)
                } catch (e: Exception) {
                    // ignore and fallback
                }

                if (bitmap != null && needRotationAfterContentResolverThumbnail(model.mimeType)) {
                    bitmap = applyExifOrientation(context, bitmap, model.rotationDegrees, false)
                }
            } else {
                val contentId = model.uri.tryParseId()
                if (contentId != null) {
                    try {
                        bitmap = if (isVideo(model.mimeType)) {
                            @Suppress("deprecation")
                            MediaStore.Video.Thumbnails.getThumbnail(
                                resolver,
                                contentId,
                                MediaStore.Video.Thumbnails.MINI_KIND,
                                null
                            )
                        } else {
                            @Suppress("deprecation")
                            MediaStore.Images.Thumbnails.getThumbnail(
                                resolver,
                                contentId,
                                MediaStore.Images.Thumbnails.MINI_KIND,
                                null
                            )
                        }
                    } catch (e: Exception) {
                        // ignore and fallback
                    }

                    if (bitmap != null) {
                        bitmap = applyExifOrientation(context, bitmap, model.rotationDegrees, false)
                    }
                }
            }

            // If system thumbnail loading fails, perform a fallback manual decode
            if (bitmap == null) {
                bitmap = if (isVideo(model.mimeType)) {
                    decodeVideoFallback()
                } else {
                    decodeFallbackStream()
                }
            }

            if (bitmap != null) {
                callback.onDataReady(bitmap)
            } else {
                callback.onLoadFailed(IOException("Failed to load or decode thumbnail for uri=${model.uri}"))
            }
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    private fun decodeVideoFallback(): Bitmap? {
        val retriever = openMetadataRetriever(context, model.uri) ?: return null
        return try {
            var videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
            var videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
            if (videoWidth != null && videoHeight != null) {
                val rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val isRotated = rotationDegrees % 180 == 90
                if (isRotated) {
                    val temp = videoWidth
                    videoWidth = videoHeight
                    videoHeight = temp
                }
                
                var dstWidth = if (width > 0) minOf(width, 180) else 180
                var dstHeight = if (height > 0) minOf(height, 180) else 180
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val params = MediaMetadataRetriever.BitmapParams().apply {
                            preferredConfig = Bitmap.Config.RGB_565
                        }
                        retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstWidth, dstHeight, params)
                    } else {
                        retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstWidth, dstHeight)
                    }
                } else {
                    retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } else {
                retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun decodeFallbackStream(): Bitmap? {
        val resolver = context.contentResolver
        
        // 1. Try extracting EXIF thumbnail directly (extremely fast and low-power)
        try {
            resolver.openInputStream(model.uri)?.use { stream ->
                val exifInterface = ExifInterface(stream)
                if (exifInterface.hasThumbnail()) {
                    val thumbnailBytes = exifInterface.thumbnail
                    if (thumbnailBytes != null) {
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val decoded = BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size, options)
                        if (decoded != null) {
                            return applyExifOrientation(context, decoded, model.rotationDegrees, false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore and fallback to manual decode
        }

        // 2. Full downsampled decode fallback
        return try {
            resolver.openInputStream(model.uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(stream, null, options)

                val srcWidth = options.outWidth
                val srcHeight = options.outHeight
                var inSampleSize = 1
                val targetWidth = if (width > 0) minOf(width, 180) else 180
                val targetHeight = if (height > 0) minOf(height, 180) else 180

                if (srcWidth > targetWidth || srcHeight > targetHeight) {
                    val halfWidth = srcWidth / 2
                    val halfHeight = srcHeight / 2
                    while (halfWidth / inSampleSize >= targetWidth && halfHeight / inSampleSize >= targetHeight) {
                        inSampleSize *= 2
                    }
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }

                resolver.openInputStream(model.uri)?.use { fallbackStream ->
                    val decoded = BitmapFactory.decodeStream(fallbackStream, null, decodeOptions)
                    if (decoded != null) {
                        applyExifOrientation(context, decoded, model.rotationDegrees, false)
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun cleanup() {}

    override fun cancel() {}

    override fun getDataClass(): Class<Bitmap> = Bitmap::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}
