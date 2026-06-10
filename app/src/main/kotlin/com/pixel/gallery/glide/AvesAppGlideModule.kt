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
import kotlinx.coroutines.flow.first
// import com.pixel.gallery.utils.compatRemoveIf // Helper missing in Lumina, using inline logic or manual loop

@GlideModule
class AvesAppGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // hide noisy warning (e.g. for images that can't be decoded)
        builder.setLogLevel(Log.ERROR)

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
        val settingsRepository = com.pixel.gallery.data.repository.SettingsRepository(context.applicationContext)
        val cacheSizeMb = kotlinx.coroutines.runBlocking {
            settingsRepository.glideCacheSize.first()
        }
        val diskCacheSize = cacheSizeMb * 1024 * 1024
        val internalCacheDiskCacheFactory = InternalCacheDiskCacheFactory(context, DiskCache.Factory.DEFAULT_DISK_CACHE_DIR, diskCacheSize.toLong())
        builder.setDiskCache(internalCacheDiskCacheFactory)

        // Hard-limit background thread count for image decoding to 2 threads and disk cache reading to 1 thread.
        // This is a direct physical throttle on CPU usage, preventing excessive power draw during rapid scroll.
        val sourceExec = GlideExecutor.newSourceExecutor(2, "source-throttled", GlideExecutor.UncaughtThrowableStrategy.DEFAULT)
        _sourceExecutor = sourceExec
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
    }

    override fun isManifestParsingEnabled(): Boolean = false

    companion object {
        private val LOG_TAG = LogUtils.createTag<AvesAppGlideModule>()
        private var _sourceExecutor: GlideExecutor? = null

        fun updateThreadCount(threads: Int) {
            val source = _sourceExecutor ?: return
            
            // Try updating the delegate ThreadPoolExecutor inside GlideExecutor via reflection
            try {
                var updated = false
                var currentClass: Class<*>? = source.javaClass
                while (currentClass != null && currentClass != Any::class.java) {
                    for (field in currentClass.declaredFields) {
                        try {
                            field.isAccessible = true
                            val value = field.get(source)
                            if (value is java.util.concurrent.ThreadPoolExecutor) {
                                val currentCore = value.corePoolSize
                                if (threads > currentCore) {
                                    value.maximumPoolSize = threads
                                    value.corePoolSize = threads
                                } else {
                                    value.corePoolSize = threads
                                    value.maximumPoolSize = threads
                                }
                                android.util.Log.d("AvesAppGlideModule", "Updated Glide delegate executor ($field) thread count to $threads via reflection")
                                updated = true
                                break
                            }
                        } catch (e: Exception) {
                            // ignore security exceptions for individual fields
                        }
                    }
                    if (updated) break
                    currentClass = currentClass.superclass
                }
                if (!updated) {
                    android.util.Log.e("AvesAppGlideModule", "Could not find ThreadPoolExecutor delegate in GlideExecutor")
                }
            } catch (e: Exception) {
                android.util.Log.e("AvesAppGlideModule", "Failed to update Glide thread count via reflection", e)
            }
        }

        // request a fresh image with the highest quality format
        val uncachedFullImageOptions = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)

        fun getModel(
            context: Context,
            uri: Uri,
            mimeType: String,
            pageId: Int?,
            sizeBytes: Long? = null,
            isThumbnail: Boolean = false,
            rotationDegrees: Int = 0,
            dateModifiedMillis: Long = 0L
        ): Any {
            /*if (pageId != null && MultiPageImage.isSupported(mimeType)) {
                MultiPageImage(context, uri, mimeType, pageId)
            } else if (mimeType == MimeTypes.TIFF) {
                TiffImage(context, uri, pageId)
            } else*/ 
            return if (mimeType == MimeTypes.SVG) {
                SvgImage(context, uri)
            } else if (isThumbnail && StorageUtils.isMediaStoreContentUri(uri)) {
                MediaStoreThumbnail(uri, mimeType, rotationDegrees, dateModifiedMillis, sizeBytes)
            } else if (isVideo(mimeType)) {
                VideoThumbnail(context, uri)
            } else {
                StorageUtils.getGlideSafeUri(context, uri, mimeType, sizeBytes)
            }
        }
    }
}
