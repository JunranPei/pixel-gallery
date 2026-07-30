package com.pixel.gallery.glide

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.ImageHeaderParser
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPoolAdapter
import com.bumptech.glide.load.engine.bitmap_recycle.LruArrayPool
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.DiskCache
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.load.resource.bitmap.ExifInterfaceImageHeaderParser
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.pixel.gallery.utils.LogUtils
import com.pixel.gallery.utils.MimeTypes
import com.pixel.gallery.utils.MimeTypes.isVideo
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.pixel.gallery.utils.StorageUtils
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import kotlinx.coroutines.flow.first
// import com.pixel.gallery.utils.compatRemoveIf // Helper missing in Lumina, using inline logic or manual loop

@GlideModule
class AvesAppGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // hide noisy warning (e.g. for images that can't be decoded)
        builder.setLogLevel(Log.ERROR)

        // Read settings before creating Glide's immutable executors.
        val settingsRepository = com.pixel.gallery.data.repository.SettingsRepository(context.applicationContext)
        val sourceThreadCount = kotlinx.coroutines.runBlocking {
            settingsRepository.glideThreadCount.first()
        }.coerceIn(1, 8)

        // sizing
        val memorySizeCalculator = MemorySizeCalculator.Builder(context).build()
        builder.setMemorySizeCalculator(memorySizeCalculator)
        val size: Int = memorySizeCalculator.bitmapPoolSize
        if (size > 0) {
            builder.setBitmapPool(LruBitmapPool(size.toLong()))
        } else {
            builder.setBitmapPool(BitmapPoolAdapter())
        }
        builder.setArrayPool(LruArrayPool(memorySizeCalculator.arrayPoolSizeInBytes))
        builder.setMemoryCache(LruResourceCache(memorySizeCalculator.memoryCacheSize.toLong()))

        // Read custom disk cache size from settings (requires runBlocking for synchronous load during initialization)
        val cacheSizeMb = kotlinx.coroutines.runBlocking {
            settingsRepository.glideCacheSize.first()
        }
        val diskCacheSize = cacheSizeMb * 1024 * 1024
        val internalCacheDiskCacheFactory = InternalCacheDiskCacheFactory(context, DiskCache.Factory.DEFAULT_DISK_CACHE_DIR, diskCacheSize.toLong())
        builder.setDiskCache(internalCacheDiskCacheFactory)

        // Glide executors cannot be safely resized after initialization. Apply the persisted
        // source-decode setting here; disk-cache I/O remains serialized by design.
        val sourceExec = GlideExecutor.newSourceExecutor(sourceThreadCount, "source-configured", GlideExecutor.UncaughtThrowableStrategy.DEFAULT)
        builder.setSourceExecutor(sourceExec)
        builder.setDiskCacheExecutor(GlideExecutor.newDiskCacheExecutor(1, "disk-cache-throttled", GlideExecutor.UncaughtThrowableStrategy.DEFAULT))

        fun toMb(bytes: Int) = Formatter.formatFileSize(context, bytes.toLong())
        Log.d(
            LOG_TAG, "Glide disk cache size=${toMb(diskCacheSize)}" +
                    ", memory cache size=${toMb(memorySizeCalculator.memoryCacheSize)}" +
                    ", bitmap pool size=${toMb(memorySizeCalculator.bitmapPoolSize)}" +
                    ", array pool size=${toMb(memorySizeCalculator.arrayPoolSizeInBytes)}"
        )
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // prevent ExifInterface error logs
        // cf https://github.com/bumptech/glide/issues/3383
        val parsersToRemove = registry.imageHeaderParsers.filter { it is ExifInterfaceImageHeaderParser }
        parsersToRemove.forEach { registry.imageHeaderParsers.remove(it) }

        registry.append(MediaStoreThumbnail::class.java, Bitmap::class.java, MediaStoreThumbnailLoader.Factory(context))
        registry.append(FastScreenPreview::class.java, Bitmap::class.java, FastScreenPreviewLoader.Factory(context))
    }

    override fun isManifestParsingEnabled(): Boolean = false

    companion object {
        private val LOG_TAG = LogUtils.createTag<AvesAppGlideModule>()
        // request a fresh image with the highest quality format
        // [Legacy/Original Code commented out per user request]
        /*
        val uncachedFullImageOptions = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
        */
        val uncachedFullImageOptions = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .skipMemoryCache(false)

        fun getModel(
            context: Context,
            uri: Uri,
            mimeType: String,
            pageId: Int?,
            sizeBytes: Long? = null,
            isThumbnail: Boolean = false,
            isFastScreenPreview: Boolean = false,
            rotationDegrees: Int = 0,
            dateModifiedMillis: Long = 0L
        ): Any {
            /*if (pageId != null && MultiPageImage.isSupported(mimeType)) {
                MultiPageImage(context, uri, mimeType, pageId)
            } else if (mimeType == MimeTypes.TIFF) {
                TiffImage(context, uri, pageId)
            } else*/ 
            val model = if (mimeType == MimeTypes.SVG) {
                SvgImage(context, uri)
            } else if (isFastScreenPreview && StorageUtils.isMediaStoreContentUri(uri)) {
                FastScreenPreview(uri, rotationDegrees)
            } else if (isThumbnail && StorageUtils.isMediaStoreContentUri(uri)) {
                MediaStoreThumbnail(uri, mimeType, rotationDegrees, dateModifiedMillis, sizeBytes)
            } else if (isVideo(mimeType)) {
                VideoThumbnail(context, uri)
            } else {
                StorageUtils.getGlideSafeUri(context, uri, mimeType, sizeBytes)
            }
            if (ViewerLoadMetrics.currentEntryId() != 0L) {
                ViewerLoadMetrics.event(
                    "GLIDE_MODEL_RESOLVED",
                    "mime=$mimeType thumbnail=$isThumbnail fastPreview=$isFastScreenPreview " +
                        "sizeBytes=$sizeBytes model=${model.javaClass.simpleName}",
                    imageKey = "$uri:$dateModifiedMillis",
                )
            }
            return model
        }
    }
}
