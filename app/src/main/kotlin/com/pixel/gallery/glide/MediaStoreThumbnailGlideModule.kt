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
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

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
    private val activeMetricsToken = AtomicReference<ViewerLoadMetrics.WorkToken?>()

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        val metricsKey = "${model.uri}:${model.dateModifiedMillis}"
        val metricsToken = ViewerLoadMetrics.workStarted(
            type = "MEDIASTORE_THUMB_FETCH",
            imageKey = metricsKey,
            detail = "requested=${width}x$height priority=$priority bytes=${model.sizeBytes} " +
                "rotation=${model.rotationDegrees}",
        )
        activeMetricsToken.getAndSet(metricsToken)?.let {
            ViewerLoadMetrics.workCleared(it, "fetch-replaced")
        }
        var metricsCompleted = false
        fun metricsReady(source: String, bitmap: Bitmap, detail: String = "") {
            if (metricsCompleted) return
            metricsCompleted = true
            activeMetricsToken.compareAndSet(metricsToken, null)
            ViewerLoadMetrics.workReady(
                metricsToken,
                source = source,
                detail = "bitmap=${bitmap.width}x${bitmap.height} config=${bitmap.config} " +
                    "allocation=${bitmap.allocationByteCount} $detail",
            )
        }
        fun metricsFailed(error: String) {
            if (metricsCompleted) return
            metricsCompleted = true
            activeMetricsToken.compareAndSet(metricsToken, null)
            ViewerLoadMetrics.workFailed(metricsToken, error)
        }
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
            ViewerLoadMetrics.event(
                "MEDIASTORE_THUMB_POLICY",
                "requested=${width}x$height large=$isLargeFile grid=$isGridView " +
                    "highRes=$isHighResRequest persistent=$usePersistentCache",
                imageKey = metricsKey,
            )

            var persistentFile: File? = null
            var dirName: String? = null

            if (usePersistentCache) {
                dirName = if (isGridView) "persistent_grid_thumbnails" else "persistent_viewer_thumbnails"
                
                if (!hasCleanedLegacyCaches) {
                    hasCleanedLegacyCaches = true
                    val cleanupToken = ViewerLoadMetrics.workStarted(
                        "THUMB_LEGACY_CACHE_CLEANUP",
                        metricsKey,
                    )
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
                                val bytes = legacyDir.walkTopDown()
                                    .filter { it.isFile }
                                    .sumOf { it.length() }
                                ViewerLoadMetrics.event(
                                    "THUMB_LEGACY_CACHE_DELETE",
                                    "directory=$legacyName bytes=$bytes",
                                    imageKey = metricsKey,
                                )
                                legacyDir.deleteRecursively()
                            }
                        }
                        ViewerLoadMetrics.workReady(cleanupToken)
                    } catch (e: Exception) {
                        ViewerLoadMetrics.workFailed(cleanupToken, e.javaClass.simpleName)
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
                    val readStartedAt = SystemClock.elapsedRealtimeNanos()
                    try {
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val cachedBitmap = BitmapFactory.decodeFile(persistentFile.absolutePath, options)
                        if (cachedBitmap != null) {
                            metricsReady(
                                "PERSISTENT_JPEG",
                                cachedBitmap,
                                "readMs=${(SystemClock.elapsedRealtimeNanos() - readStartedAt) / 1_000_000L} " +
                                    "fileBytes=${persistentFile.length()}",
                            )
                            callback.onDataReady(cachedBitmap)
                            return
                        }
                    } catch (e: Exception) {
                        ViewerLoadMetrics.event(
                            "PERSISTENT_THUMB_READ_FAILED",
                            "error=${e.javaClass.simpleName}",
                            imageKey = metricsKey,
                        )
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Limit targetSize to a max of 512x512 to hit system pre-generated MINI_KIND/MICRO_KIND caches directly
                val targetSize = Size(
                    if (width > 0) minOf(width, 512) else 512,
                    if (height > 0) minOf(height, 512) else 512
                )
                val loadThumbnailStartedAt = SystemClock.elapsedRealtimeNanos()
                try {
                    bitmap = resolver.loadThumbnail(model.uri, targetSize, null)
                } catch (e: Exception) {
                    ViewerLoadMetrics.event(
                        "MEDIASTORE_LOAD_THUMBNAIL_FAILED",
                        "target=${targetSize.width}x${targetSize.height} error=${e.javaClass.simpleName}",
                        imageKey = metricsKey,
                    )
                }
                ViewerLoadMetrics.event(
                    "MEDIASTORE_LOAD_THUMBNAIL_DONE",
                    "target=${targetSize.width}x${targetSize.height} " +
                        "result=${bitmap?.width ?: 0}x${bitmap?.height ?: 0} " +
                        "duration=${(SystemClock.elapsedRealtimeNanos() - loadThumbnailStartedAt) / 1_000_000L}ms",
                    imageKey = metricsKey,
                )

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
                ViewerLoadMetrics.event(
                    "MEDIASTORE_THUMB_FALLBACK_START",
                    "video=${isVideo(model.mimeType)}",
                    imageKey = metricsKey,
                )
                bitmap = if (isVideo(model.mimeType)) {
                    decodeVideoFallback()
                } else {
                    decodeFallbackStream()
                }
            }

            if (bitmap != null) {
                if (usePersistentCache && persistentFile != null) {
                    val writeToken = ViewerLoadMetrics.workStarted(
                        "PERSISTENT_THUMB_WRITE_TRIM",
                        metricsKey,
                        "directory=$dirName",
                    )
                    try {
                        FileOutputStream(persistentFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        // Check and trim persistent cache size if it exceeds the limit
                        val settingsRepository = com.pixel.gallery.data.repository.SettingsRepository(context.applicationContext)
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
                        ViewerLoadMetrics.workReady(
                            writeToken,
                            detail = "fileBytes=${persistentFile.length()} limitBytes=$limitBytes",
                        )
                    } catch (e: Exception) {
                        ViewerLoadMetrics.workFailed(writeToken, e.javaClass.simpleName)
                    }
                }
                metricsReady(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "MEDIASTORE_OR_FALLBACK"
                    } else {
                        "LEGACY_MEDIASTORE_OR_FALLBACK"
                    },
                    bitmap,
                )
                callback.onDataReady(bitmap)
            } else {
                metricsFailed("no-bitmap")
                callback.onLoadFailed(IOException("Failed to load or decode thumbnail for uri=${model.uri}"))
            }
        } catch (e: Exception) {
            metricsFailed(e.javaClass.simpleName)
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
        val metricsKey = "${model.uri}:${model.dateModifiedMillis}"
        
        // 1. Try extracting EXIF thumbnail directly (extremely fast and low-power)
        val exifToken = ViewerLoadMetrics.workStarted("EXIF_THUMBNAIL_READ", metricsKey)
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
                                ViewerLoadMetrics.workReady(
                                    exifToken,
                                    source = "EXIF_EMBEDDED",
                                    detail = "bytes=${thumbnailBytes.size} bitmap=${decoded.width}x${decoded.height}",
                                )
                                return applyExifOrientation(context, decoded, model.rotationDegrees, false)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ViewerLoadMetrics.workFailed(exifToken, e.javaClass.simpleName)
        }
        ViewerLoadMetrics.workCleared(exifToken, "missing-or-too-small")

        // 2. Full downsampled decode fallback
        val streamToken = ViewerLoadMetrics.workStarted(
            "THUMB_STREAM_BOUNDS_AND_DECODE",
            metricsKey,
            "requested=${width}x$height",
        )
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
                            ViewerLoadMetrics.workReady(
                                streamToken,
                                source = "STREAM_DECODE",
                                detail = "source=${srcWidth}x$srcHeight sample=$inSampleSize " +
                                    "bitmap=${decoded.width}x${decoded.height} inBitmap=${decodeOptions.inBitmap != null}",
                            )
                            applyExifOrientation(context, decoded, model.rotationDegrees, false)
                        } else {
                            ViewerLoadMetrics.workFailed(streamToken, "decode-null")
                            null
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    // Fallback: Clear inBitmap and retry if reuse failed (e.g. incompatible dimensions on older devices)
                    decodeOptions.inBitmap = null
                    resolver.openInputStream(model.uri)?.use { fallbackStream ->
                        val decoded = BitmapFactory.decodeStream(fallbackStream, null, decodeOptions)
                        if (decoded != null) {
                            ViewerLoadMetrics.workReady(
                                streamToken,
                                source = "STREAM_DECODE_RETRY",
                                detail = "source=${srcWidth}x$srcHeight sample=$inSampleSize " +
                                    "bitmap=${decoded.width}x${decoded.height}",
                            )
                            applyExifOrientation(context, decoded, model.rotationDegrees, false)
                        } else {
                            ViewerLoadMetrics.workFailed(streamToken, "retry-decode-null")
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ViewerLoadMetrics.workFailed(streamToken, e.javaClass.simpleName)
            null
        }
    }

    override fun cleanup() {
        activeMetricsToken.getAndSet(null)?.let {
            ViewerLoadMetrics.workCleared(it, "datafetcher-cleanup")
        }
    }

    override fun cancel() {
        activeMetricsToken.getAndSet(null)?.let {
            ViewerLoadMetrics.workCleared(it, "datafetcher-cancel")
        }
    }

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
            val beforeSize = currentSize
            var deletedFiles = 0
            if (currentSize <= maxSizeBytes) {
                ViewerLoadMetrics.event(
                    "PERSISTENT_THUMB_TRIM_SKIP",
                    "directory=${persistentDir.name} files=${files.size} bytes=$currentSize limit=$maxSizeBytes",
                    imageKey = "${model.uri}:${model.dateModifiedMillis}",
                )
                return
            }

            // Sort by last modified time, oldest first
            val sortedFiles = files.sortedBy { it.lastModified() }
            for (file in sortedFiles) {
                val length = file.length()
                if (file.delete()) {
                    deletedFiles++
                    currentSize -= length
                    if (currentSize <= maxSizeBytes) {
                        break
                    }
                }
            }
            ViewerLoadMetrics.event(
                "PERSISTENT_THUMB_TRIM_DONE",
                "directory=${persistentDir.name} files=${files.size} before=$beforeSize " +
                    "after=$currentSize deleted=$deletedFiles limit=$maxSizeBytes",
                imageKey = "${model.uri}:${model.dateModifiedMillis}",
            )
        } catch (e: Exception) {
            ViewerLoadMetrics.event(
                "PERSISTENT_THUMB_TRIM_FAILED",
                "error=${e.javaClass.simpleName}",
                imageKey = "${model.uri}:${model.dateModifiedMillis}",
            )
        }
    }
}
