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
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
        val originalPriority = try {
            Process.getThreadPriority(Process.myTid())
        } catch (e: Exception) {
            Process.THREAD_PRIORITY_BACKGROUND
        }

        try {
            // Force the execution of thumbnail loading/decoding to the lowest priority.
            // This binds the thread to run ONLY on the CPU's LITTLE (efficiency) cores, preventing big cores from waking up.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            } catch (e: Exception) {
                // ignore
            }

            var bitmap: Bitmap? = null
            val resolver = context.contentResolver

            // Double Cache: Persistent Cache for heavy files (>5MB) to prevent expensive re-decoding
            val isLargeFile = model.sizeBytes != null && model.sizeBytes > 5 * 1024 * 1024
            val isGridView = width < 300 && height < 300
            val isHighResRequest = width >= 200 || height >= 200
            val usePersistentCache = isLargeFile && isHighResRequest

            val settingsRepository = com.pixel.gallery.data.repository.SettingsRepository(context.applicationContext)
            var persistentFile: File? = null
            var dirName: String? = null

            if (usePersistentCache) {
                dirName = if (isGridView) "persistent_grid_thumbnails" else "persistent_viewer_thumbnails"
                
                if (!hasCleanedLegacyCaches) {
                    hasCleanedLegacyCaches = true
                    try {
                        val legacyDirs = listOf(
                            "persistent_thumbnails",
                            "persistent_thumbnails_v2",
                            "persistent_thumbnails_v3",
                            "persistent_grid_thumbnails",
                            "persistent_viewer_thumbnails"
                        )
                        for (legacyName in legacyDirs) {
                            val legacyDir = File(context.cacheDir, legacyName)
                            if (legacyDir.exists()) {
                                legacyDir.deleteRecursively()
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                val persistentDir = File(context.cacheDir, dirName)
                if (!persistentDir.exists()) {
                    persistentDir.mkdirs()
                }
                val contentId = model.uri.tryParseId() ?: model.uri.hashCode()
                val cacheFileName = "${contentId}_${model.dateModifiedMillis}_${model.rotationDegrees}.jpg"
                persistentFile = File(persistentDir, cacheFileName)

                if (persistentFile.exists()) {
                    try {
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val cachedBitmap = BitmapFactory.decodeFile(persistentFile.absolutePath, options)
                        if (cachedBitmap != null) {
                            callback.onDataReady(cachedBitmap)
                            return
                        }
                    } catch (e: Exception) {
                        // ignore and reload
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Limit targetSize to a max of 512x512 to hit system pre-generated MINI_KIND/MICRO_KIND caches directly
                val targetSize = Size(
                    if (width > 0) minOf(width, 512) else 512,
                    if (height > 0) minOf(height, 512) else 512
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
                if (usePersistentCache && persistentFile != null) {
                    try {
                        FileOutputStream(persistentFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        // Check and trim persistent cache size if it exceeds the limit
                        val limitFlow = if (isGridView) {
                            settingsRepository.glidePersistentGridCacheSize
                        } else {
                            settingsRepository.glidePersistentViewerCacheSize
                        }
                        val limitMb = runBlocking {
                            limitFlow.first()
                        }
                        val limitBytes = limitMb.toLong() * 1024 * 1024
                        persistentFile.parentFile?.let {
                            trimPersistentCache(it, limitBytes)
                        }
                    } catch (e: Exception) {
                        // ignore write and trim errors
                    }
                }
                callback.onDataReady(bitmap)
            } else {
                callback.onLoadFailed(IOException("Failed to load or decode thumbnail for uri=${model.uri}"))
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
                
                var dstWidth = if (width > 0) minOf(width, 512) else 512
                var dstHeight = if (height > 0) minOf(height, 512) else 512
                
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
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size, bounds)
                        if (bounds.outWidth >= 200 && bounds.outHeight >= 200) {
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
                val targetWidth = if (width > 0) minOf(width, 512) else 512
                val targetHeight = if (height > 0) minOf(height, 512) else 512

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
                    inTempStorage = ByteArray(16 * 1024)
                }

                // Try to reuse memory space from Glide's LruBitmapPool to minimize memory allocations and GC spikes
                try {
                    val targetWidthCalculated = srcWidth / inSampleSize
                    val targetHeightCalculated = srcHeight / inSampleSize
                    if (targetWidthCalculated > 0 && targetHeightCalculated > 0) {
                        val bitmapPool = com.bumptech.glide.Glide.get(context).bitmapPool
                        val reusableBitmap = bitmapPool.getDirty(targetWidthCalculated, targetHeightCalculated, Bitmap.Config.RGB_565)
                        decodeOptions.inBitmap = reusableBitmap
                        decodeOptions.inMutable = true
                    }
                } catch (e: Exception) {
                    // ignore pool acquisition errors
                }

                try {
                    resolver.openInputStream(model.uri)?.use { fallbackStream ->
                        val decoded = BitmapFactory.decodeStream(fallbackStream, null, decodeOptions)
                        if (decoded != null) {
                            applyExifOrientation(context, decoded, model.rotationDegrees, false)
                        } else {
                            null
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    // Fallback: Clear inBitmap and retry if reuse failed (e.g. incompatible dimensions on older devices)
                    decodeOptions.inBitmap = null
                    resolver.openInputStream(model.uri)?.use { fallbackStream ->
                        val decoded = BitmapFactory.decodeStream(fallbackStream, null, decodeOptions)
                        if (decoded != null) {
                            applyExifOrientation(context, decoded, model.rotationDegrees, false)
                        } else {
                            null
                        }
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

    companion object {
        @Volatile
        private var hasCleanedLegacyCaches = false
    }

    private fun trimPersistentCache(persistentDir: File, maxSizeBytes: Long) {
        try {
            val files = persistentDir.listFiles { _, name -> name.endsWith(".jpg") } ?: return
            var currentSize = files.sumOf { it.length() }
            if (currentSize <= maxSizeBytes) {
                return
            }

            // Sort by last modified time, oldest first
            val sortedFiles = files.sortedBy { it.lastModified() }
            for (file in sortedFiles) {
                val length = file.length()
                if (file.delete()) {
                    currentSize -= length
                    if (currentSize <= maxSizeBytes) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
