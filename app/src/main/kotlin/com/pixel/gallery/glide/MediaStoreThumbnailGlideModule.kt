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
import java.util.concurrent.atomic.AtomicBoolean
import android.os.CancellationSignal

data class MediaStoreThumbnail(
    val uri: Uri,
    val mimeType: String,
    val rotationDegrees: Int,
    val dateModifiedMillis: Long,
    val sizeBytes: Long? = null,
    val allowPersistentCache: Boolean = true,
    val traceViewerLoad: Boolean = false,
    val allowSourceFallback: Boolean = true,
    val minimumLongEdgePixels: Int = 0,
) {
    // Persistence and tracing do not change pixels. Keeping those out of identity lets
    // the viewer's cache-only cover reuse the exact 200 px bitmap that Grid just
    // displayed, without starting another source read or decode.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaStoreThumbnail) return false

        return uri == other.uri &&
            mimeType == other.mimeType &&
            rotationDegrees == other.rotationDegrees &&
            dateModifiedMillis == other.dateModifiedMillis &&
            sizeBytes == other.sizeBytes &&
            allowSourceFallback == other.allowSourceFallback &&
            minimumLongEdgePixels == other.minimumLongEdgePixels
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + rotationDegrees
        result = 31 * result + dateModifiedMillis.hashCode()
        result = 31 * result + (sizeBytes?.hashCode() ?: 0)
        result = 31 * result + allowSourceFallback.hashCode()
        result = 31 * result + minimumLongEdgePixels
        return result
    }
}

internal class MediaStoreThumbnailLoader(private val context: Context) : ModelLoader<MediaStoreThumbnail, Bitmap> {
    override fun buildLoadData(
        model: MediaStoreThumbnail,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<Bitmap> {
        val decoderVersion = if (com.pixel.gallery.BuildConfig.JPEG_DC_PREVIEW_ENABLED && model.minimumLongEdgePixels > 0) "dc-v1-" else ""
        val minimumVersion = if (model.minimumLongEdgePixels > 0) "${decoderVersion}min${model.minimumLongEdgePixels}-" else ""
        val version = if (isVideo(model.mimeType)) "" else "aspect-v2-${model.allowSourceFallback}-$minimumVersion"
        val cacheKey = ObjectKey("$version${model.uri}-${model.dateModifiedMillis}-${model.rotationDegrees}-${width}x${height}")
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
    private val cancelled = AtomicBoolean()
    private val cancellationSignal = CancellationSignal()
    private val activeSourceStream = AtomicReference<ThumbnailSourceStream?>()

    private fun closeSourceStream() {
        try { activeSourceStream.getAndSet(null)?.close() } catch (_: IOException) { }
    }

    private inline fun <T> withSourceStream(block: (ThumbnailSourceStream) -> T): T? {
        if (cancelled.get()) return null
        val source = context.contentResolver.openInputStream(model.uri) ?: return null
        val stream = ThumbnailSourceStream(source, cancelled::get)
        activeSourceStream.set(stream)
        return try {
            if (cancelled.get()) null else stream.use(block)
        } finally {
            activeSourceStream.compareAndSet(stream, null)
            stream.close()
        }
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        if (cancelled.get()) return
        val metricsKey = "${model.uri}:${model.dateModifiedMillis}"
        val metricsToken = if (model.traceViewerLoad) {
            ViewerLoadMetrics.workStarted(
                type = "MEDIASTORE_THUMB_FETCH",
                imageKey = metricsKey,
                detail = "requested=${width}x$height priority=$priority bytes=${model.sizeBytes} " +
                    "rotation=${model.rotationDegrees}",
            )
        } else {
            null
        }
        if (metricsToken != null) {
            activeMetricsToken.getAndSet(metricsToken)?.let {
                ViewerLoadMetrics.workCleared(it, "fetch-replaced")
            }
        }
        var metricsCompleted = false
        fun metricsReady(source: String, bitmap: Bitmap, detail: String = "") {
            if (metricsCompleted || metricsToken == null) return
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
            if (metricsCompleted || metricsToken == null) return
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
            // Keep thumbnail work at background priority; this does not pin it to specific CPU cores.
            try {
                if (model.minimumLongEdgePixels <= 0) Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            } catch (e: Exception) {
                // ignore
            }

            var bitmap: Bitmap? = null
            val resolver = context.contentResolver

            // Double Cache: Persistent Cache for heavy files (>5MB) to prevent expensive re-decoding
            val isLargeFile = model.sizeBytes != null && model.sizeBytes > 5 * 1024 * 1024
            val isGridView = width < 300 && height < 300
            val isHighResRequest = width >= 200 || height >= 200
            val usePersistentCache =
                model.allowPersistentCache && isLargeFile && isHighResRequest
            if (model.traceViewerLoad) {
                ViewerLoadMetrics.event(
                    "MEDIASTORE_THUMB_POLICY",
                    "requested=${width}x$height large=$isLargeFile grid=$isGridView " +
                        "highRes=$isHighResRequest allowPersistent=${model.allowPersistentCache} " +
                        "persistent=$usePersistentCache minimumLongEdge=${model.minimumLongEdgePixels} " +
                        "threadPriority=${Process.getThreadPriority(Process.myTid())}",
                    imageKey = metricsKey,
                )
            }

            var persistentFile: File? = null
            var dirName: String? = null

            if (usePersistentCache) {
                dirName = if (isGridView) "persistent_grid_thumbnails" else "persistent_viewer_thumbnails"
                
                if (!hasCleanedLegacyCaches) {
                    hasCleanedLegacyCaches = true
                    val cleanupToken = if (model.traceViewerLoad) {
                        ViewerLoadMetrics.workStarted(
                            "THUMB_LEGACY_CACHE_CLEANUP",
                            metricsKey,
                        )
                    } else {
                        null
                    }
                    try {
                        val legacyDirs = listOf(
                            "persistent_thumbnails",
                            "persistent_thumbnails_v2",
                            "persistent_thumbnails_v3",
                        )
                        for (legacyName in legacyDirs) {
                            val legacyDir = File(context.cacheDir, legacyName)
                            if (legacyDir.exists()) {
                                if (model.traceViewerLoad) {
                                    val bytes = legacyDir.walkTopDown()
                                        .filter { it.isFile }
                                        .sumOf { it.length() }
                                    ViewerLoadMetrics.event(
                                        "THUMB_LEGACY_CACHE_DELETE",
                                        "directory=$legacyName bytes=$bytes",
                                        imageKey = metricsKey,
                                    )
                                }
                                legacyDir.deleteRecursively()
                            }
                        }
                        cleanupToken?.let { ViewerLoadMetrics.workReady(it) }
                    } catch (e: Exception) {
                        cleanupToken?.let { ViewerLoadMetrics.workFailed(it, e.javaClass.simpleName) }
                    }
                }

                val persistentDir = File(context.cacheDir, dirName)
                if (!persistentDir.exists()) {
                    persistentDir.mkdirs()
                }
                val contentId = model.uri.tryParseId() ?: model.uri.hashCode()
                val decoderVersion = if (com.pixel.gallery.BuildConfig.JPEG_DC_PREVIEW_ENABLED && model.minimumLongEdgePixels > 0) "dc_v1_" else ""
                val minimumVersion = if (model.minimumLongEdgePixels > 0) "${decoderVersion}min${model.minimumLongEdgePixels}_${width}x${height}_" else ""
                val version = if (isVideo(model.mimeType)) "" else "aspect_v2_${model.allowSourceFallback}_$minimumVersion"
                val cacheFileName = "${version}${contentId}_${model.dateModifiedMillis}_${model.rotationDegrees}" +
                    if (isGridView) "_q75.jpg" else ".jpg"
                persistentFile = File(persistentDir, cacheFileName)

                if (persistentFile.exists()) {
                    val readStartedAt =
                        if (model.traceViewerLoad) SystemClock.elapsedRealtimeNanos() else 0L
                    try {
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val cachedBitmap = BitmapFactory.decodeFile(persistentFile.absolutePath, options)
                        val sourceBounds = if (model.minimumLongEdgePixels > 0 && !isVideo(model.mimeType)) readSourceBounds() else null
                        val cachedSizeAccepted = cachedBitmap != null && thumbnailMeetsMinimumLongEdge(
                            cachedBitmap.width, cachedBitmap.height,
                            sourceBounds?.first ?: 0, sourceBounds?.second ?: 0,
                            model.minimumLongEdgePixels,
                        )
                        if (cachedBitmap != null && cachedSizeAccepted) {
                            if (cancelled.get()) { cachedBitmap.recycle(); return }
                            metricsReady(
                                "PERSISTENT_JPEG",
                                cachedBitmap,
                                "readMs=${(SystemClock.elapsedRealtimeNanos() - readStartedAt) / 1_000_000L} " +
                                    "fileBytes=${persistentFile.length()}",
                            )
                            callback.onDataReady(cachedBitmap)
                            return
                        }
                        cachedBitmap?.recycle()
                    } catch (e: Exception) {
                        if (model.traceViewerLoad) {
                            ViewerLoadMetrics.event(
                                "PERSISTENT_THUMB_READ_FAILED",
                                "error=${e.javaClass.simpleName}",
                                imageKey = metricsKey,
                            )
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Limit targetSize to a max of 512x512 to hit system pre-generated MINI_KIND/MICRO_KIND caches directly
                val targetSize = Size(
                    if (width > 0) minOf(width, 512) else 512,
                    if (height > 0) minOf(height, 512) else 512
                )
                val loadThumbnailStartedAt =
                    if (model.traceViewerLoad) SystemClock.elapsedRealtimeNanos() else 0L
                try {
                    bitmap = resolver.loadThumbnail(model.uri, targetSize, cancellationSignal)
                } catch (e: Exception) {
                    if (model.traceViewerLoad) {
                        ViewerLoadMetrics.event(
                            "MEDIASTORE_LOAD_THUMBNAIL_FAILED",
                            "target=${targetSize.width}x${targetSize.height} error=${e.javaClass.simpleName}",
                            imageKey = metricsKey,
                        )
                    }
                }
                if (model.traceViewerLoad) {
                    ViewerLoadMetrics.event(
                        "MEDIASTORE_LOAD_THUMBNAIL_DONE",
                        "target=${targetSize.width}x${targetSize.height} " +
                            "result=${bitmap?.width ?: 0}x${bitmap?.height ?: 0} " +
                            "duration=${(SystemClock.elapsedRealtimeNanos() - loadThumbnailStartedAt) / 1_000_000L}ms",
                        imageKey = metricsKey,
                    )
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

            // Tiny decorative covers can decline expensive original/video decoding.
            if (cancelled.get()) { bitmap?.recycle(); return }
            if (bitmap == null && model.allowSourceFallback) {
                if (model.traceViewerLoad) {
                    ViewerLoadMetrics.event(
                        "MEDIASTORE_THUMB_FALLBACK_START",
                        "video=${isVideo(model.mimeType)}",
                        imageKey = metricsKey,
                    )
                }
                bitmap = if (isVideo(model.mimeType)) {
                    decodeVideoFallback()
                } else {
                    decodeFallbackStream()
                }
            }

            if (bitmap != null && !isVideo(model.mimeType) &&
                (model.allowSourceFallback || model.minimumLongEdgePixels > 0)) {
                // Providers can return a padded/cropped EXIF thumbnail instead of the original
                // image's aspect ratio. Re-decode a small original preview rather than crop
                // dark pixels: actual black borders in the photograph must remain intact.
                val sourceBounds = readSourceBounds()
                val aspectMismatch = sourceBounds != null && !thumbnailMatchesSourceAspect(
                        bitmap.width, bitmap.height, sourceBounds.first, sourceBounds.second, model.rotationDegrees,
                    )
                val resolutionMismatch = !thumbnailMeetsMinimumLongEdge(
                    bitmap.width, bitmap.height, sourceBounds?.first ?: 0, sourceBounds?.second ?: 0,
                    model.minimumLongEdgePixels,
                )
                if (aspectMismatch || resolutionMismatch) {
                    val originalThumbnail = bitmap
                    if (!model.allowSourceFallback) {
                        originalThumbnail.recycle()
                        metricsFailed("thumbnail-below-minimum-without-source-fallback")
                        callback.onLoadFailed(IOException("Thumbnail is below the requested minimum resolution for ${model.uri}"))
                        return
                    }
                    val corrected = decodeFallbackStream(allowEmbeddedThumbnail = false, knownSourceBounds = sourceBounds)
                    if (cancelled.get()) { corrected?.recycle(); originalThumbnail.recycle(); return }
                    if (corrected == null || !thumbnailMeetsMinimumLongEdge(
                            corrected.width, corrected.height, sourceBounds?.first ?: 0, sourceBounds?.second ?: 0,
                            model.minimumLongEdgePixels,
                        ) || (sourceBounds != null && !thumbnailMatchesSourceAspect(
                            corrected.width, corrected.height, sourceBounds.first, sourceBounds.second, model.rotationDegrees,
                        ))) {
                        corrected?.takeIf { it !== originalThumbnail }?.recycle()
                        originalThumbnail.recycle()
                        metricsFailed("thumbnail-quality-correction-failed")
                        callback.onLoadFailed(IOException("Could not decode a sufficiently detailed, aspect-correct thumbnail for ${model.uri}"))
                        return
                    }
                    bitmap = corrected
                    if (corrected !== originalThumbnail) originalThumbnail.recycle()
                    if (model.traceViewerLoad) ViewerLoadMetrics.event(
                        if (aspectMismatch) "THUMB_ASPECT_REDECODE" else "THUMB_RESOLUTION_REDECODE",
                        "source=${sourceBounds?.first ?: 0}x${sourceBounds?.second ?: 0} " +
                            "rotation=${model.rotationDegrees} corrected=true minimumLongEdge=${model.minimumLongEdgePixels} " +
                            "result=${bitmap.width}x${bitmap.height}",
                        imageKey = metricsKey,
                    )
                }
            }

            if (cancelled.get()) { bitmap?.recycle(); return }
            if (bitmap != null) {
                if (usePersistentCache && persistentFile != null) {
                    val writeToken = if (model.traceViewerLoad) {
                        ViewerLoadMetrics.workStarted(
                            "PERSISTENT_THUMB_WRITE_TRIM",
                            metricsKey,
                            "directory=$dirName",
                        )
                    } else {
                        null
                    }
                    try {
                        val jpegQuality = if (isGridView) 75 else 85
                        FileOutputStream(persistentFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
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
                        writeToken?.let {
                            ViewerLoadMetrics.workReady(
                                it,
                                detail = "fileBytes=${persistentFile.length()} limitBytes=$limitBytes",
                            )
                        }
                    } catch (e: Exception) {
                        writeToken?.let { ViewerLoadMetrics.workFailed(it, e.javaClass.simpleName) }
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
            if (!cancelled.get()) {
                metricsFailed(e.javaClass.simpleName)
                callback.onLoadFailed(e)
            }
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

    private fun readSourceBounds(): Pair<Int, Int>? {
        try {
            context.contentResolver.query(
                model.uri,
                arrayOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val width = cursor.getInt(0)
                    val height = cursor.getInt(1)
                    if (width > 0 && height > 0) return width to height
                }
            }
        } catch (_: Exception) {
            // Some providers omit dimensions even when the original stream is readable.
        }
        return try {
            withSourceStream { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
            }
        } catch (_: Exception) { null }
    }

    private fun decodeFallbackStream(allowEmbeddedThumbnail: Boolean = true, knownSourceBounds: Pair<Int, Int>? = null): Bitmap? {
        if (cancelled.get()) return null
        val resolver = context.contentResolver
        val metricsKey = "${model.uri}:${model.dateModifiedMillis}"
        
        // 1. Try extracting EXIF thumbnail directly (extremely fast and low-power)
        val exifToken = if (allowEmbeddedThumbnail && model.traceViewerLoad) {
            ViewerLoadMetrics.workStarted("EXIF_THUMBNAIL_READ", metricsKey)
        } else {
            null
        }
        try {
            if (allowEmbeddedThumbnail) withSourceStream { stream ->
                val exifInterface = ExifInterface(stream)
                if (exifInterface.hasThumbnail()) {
                    val thumbnailBytes = exifInterface.thumbnail
                    if (thumbnailBytes != null) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size, bounds)
                        if (bounds.outWidth >= 200 && bounds.outHeight >= 200 &&
                            thumbnailMeetsMinimumLongEdge(bounds.outWidth, bounds.outHeight, 0, 0, model.minimumLongEdgePixels)) {
                            val options = BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                            val decoded = BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size, options)
                            if (decoded != null) {
                                exifToken?.let {
                                    ViewerLoadMetrics.workReady(
                                        it,
                                        source = "EXIF_EMBEDDED",
                                        detail = "bytes=${thumbnailBytes.size} bitmap=${decoded.width}x${decoded.height}",
                                    )
                                }
                                return applyExifOrientation(context, decoded, model.rotationDegrees, false)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            exifToken?.let { ViewerLoadMetrics.workFailed(it, e.javaClass.simpleName) }
        }
        exifToken?.let { ViewerLoadMetrics.workCleared(it, "missing-or-too-small") }

        // 2. Full downsampled decode fallback
        val streamToken = if (model.traceViewerLoad) {
            ViewerLoadMetrics.workStarted(
                "THUMB_STREAM_BOUNDS_AND_DECODE",
                metricsKey,
                "requested=${width}x$height",
            )
        } else {
            null
        }
        var borrowedBitmap: Bitmap? = null
        var decodedBitmap: Bitmap? = null
        return try {
            val sourceBounds = knownSourceBounds ?: withSourceStream { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                bounds.outWidth to bounds.outHeight
            } ?: return null
            val (srcWidth, srcHeight) = sourceBounds
            if (srcWidth <= 0 || srcHeight <= 0 || cancelled.get()) return null
            if (com.pixel.gallery.BuildConfig.JPEG_DC_PREVIEW_ENABLED &&
                model.minimumLongEdgePixels > 0 && maxOf(srcWidth, srcHeight) >= 8192) {
                val native = io.github.indexedjpeg.DcJpegPreview.decode(
                    resolver, model.uri, model.minimumLongEdgePixels, cancelled,
                )
                if (cancelled.get()) { native?.recycle(); return null }
                if (native != null) {
                    streamToken?.let {
                        ViewerLoadMetrics.workReady(it, source = "NATIVE_DC_PREVIEW",
                            detail = "source=${srcWidth}x${srcHeight} bitmap=${native.width}x${native.height} turbo=3.1.3 fusedAc=12")
                    }
                    return applyExifOrientation(context, native, model.rotationDegrees, false).also {
                        if (it !== native && !native.isRecycled) com.bumptech.glide.Glide.get(context).bitmapPool.put(native)
                    }
                }
                if (model.traceViewerLoad) ViewerLoadMetrics.event(
                    "NATIVE_DC_PREVIEW_FALLBACK", "unsupported-or-invalid", imageKey = metricsKey,
                )
            }
            val targetWidth = if (width > 0) minOf(width, 512) else 512
            val targetHeight = if (height > 0) minOf(height, 512) else 512
            val sample = thumbnailFitSampleSize(srcWidth, srcHeight, targetWidth, targetHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
                inTempStorage = ByteArray(16 * 1024)
            }
            try {
                borrowedBitmap = com.bumptech.glide.Glide.get(context).bitmapPool.getDirty(
                    thumbnailSampledDimension(srcWidth, sample),
                    thumbnailSampledDimension(srcHeight, sample), Bitmap.Config.RGB_565,
                )
                options.inBitmap = borrowedBitmap
                options.inMutable = true
            } catch (_: Exception) { }

            var retried = false
            fun decode(): Bitmap? = withSourceStream { stream ->
                try {
                    BitmapFactory.decodeStream(stream, null, options)
                } finally {
                    if (model.traceViewerLoad) ViewerLoadMetrics.event(
                        "THUMB_SOURCE_IO",
                        "bytes=${stream.sourceBytesRead} reads=${stream.sourceReadCalls} " +
                            "buffer=65536 cancelled=${cancelled.get()} retry=$retried",
                        imageKey = metricsKey,
                    )
                }
            }
            decodedBitmap = try { decode() } catch (error: IllegalArgumentException) {
                if (cancelled.get()) return null
                if (model.traceViewerLoad) ViewerLoadMetrics.event(
                    "THUMB_REUSE_RETRY",
                    "error=${error.message} allocation=${borrowedBitmap?.allocationByteCount ?: 0} " +
                        "source=${srcWidth}x${srcHeight} sample=$sample",
                    imageKey = metricsKey,
                )
                options.inBitmap = null
                retried = true
                decode()
            }
            val decoded = decodedBitmap
            if (cancelled.get()) {
                decoded?.recycle()
                null
            } else if (decoded != null) {
                streamToken?.let {
                    ViewerLoadMetrics.workReady(
                        it, source = if (retried) "STREAM_DECODE_RETRY" else "STREAM_DECODE",
                        detail = "source=${srcWidth}x${srcHeight} sample=$sample " +
                            "bitmap=${decoded.width}x${decoded.height} inBitmap=${options.inBitmap != null}",
                    )
                }
                applyExifOrientation(context, decoded, model.rotationDegrees, false).also { oriented ->
                    if (oriented !== decoded && !decoded.isRecycled) com.bumptech.glide.Glide.get(context).bitmapPool.put(decoded)
                }
            } else {
                streamToken?.let { ViewerLoadMetrics.workFailed(it, "decode-null") }
                null
            }
        } catch (error: Exception) {
            streamToken?.let {
                if (!cancelled.get()) ViewerLoadMetrics.workFailed(it, error.javaClass.simpleName)
            }
            null
        } finally {
            // Cancellation can make the native decoder reject inBitmap before returning.
            // Cover early returns from that retry guard as well as normal read failures.
            if (cancelled.get()) streamToken?.let { ViewerLoadMetrics.workCleared(it, "cancelled") }
            borrowedBitmap?.let { borrowed ->
                if (borrowed !== decodedBitmap && !borrowed.isRecycled) com.bumptech.glide.Glide.get(context).bitmapPool.put(borrowed)
            }
        }
    }

    override fun cleanup() {
        closeSourceStream()
        activeMetricsToken.getAndSet(null)?.let {
            ViewerLoadMetrics.workCleared(it, "datafetcher-cleanup")
        }
    }

    override fun cancel() {
        cancelled.set(true)
        cancellationSignal.cancel()
        closeSourceStream()
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
                if (model.traceViewerLoad) {
                    ViewerLoadMetrics.event(
                        "PERSISTENT_THUMB_TRIM_SKIP",
                        "directory=${persistentDir.name} files=${files.size} bytes=$currentSize limit=$maxSizeBytes",
                        imageKey = "${model.uri}:${model.dateModifiedMillis}",
                    )
                }
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
            if (model.traceViewerLoad) {
                ViewerLoadMetrics.event(
                    "PERSISTENT_THUMB_TRIM_DONE",
                    "directory=${persistentDir.name} files=${files.size} before=$beforeSize " +
                        "after=$currentSize deleted=$deletedFiles limit=$maxSizeBytes",
                    imageKey = "${model.uri}:${model.dateModifiedMillis}",
                )
            }
        } catch (e: Exception) {
            if (model.traceViewerLoad) {
                ViewerLoadMetrics.event(
                    "PERSISTENT_THUMB_TRIM_FAILED",
                    "error=${e.javaClass.simpleName}",
                    imageKey = "${model.uri}:${model.dateModifiedMillis}",
                )
            }
        }
    }
}
