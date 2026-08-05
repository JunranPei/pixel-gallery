package com.pixel.gallery.ui.viewer.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.davemorrissey.labs.subscaleview.ImageRegionDecoder
import io.github.indexedraw.IndexedRawRegionDecoder
import io.github.indexedraw.IndexedRawStore
import java.io.File
import java.util.LinkedHashMap

internal data class RawEmbeddedPreview(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/** Compressed-byte LRU only: no RAW preview is written to disk. */
internal object RawEmbeddedPreviewStore {
    private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 96 * 1024 * 1024
    private val cache = LinkedHashMap<String, RawEmbeddedPreview>(4, 0.75f, true)
    private var cachedBytes = 0

    fun load(context: Context, source: Uri, key: String): RawEmbeddedPreview? {
        synchronized(cache) { cache[key]?.let { return it } }
        val extracted = openRawDescriptor(context, source)?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            val candidates = buildList {
                exif.getAttributeBytes(ExifInterface.TAG_RW2_JPG_FROM_RAW)?.let(::add)
                exif.getAttributeBytes(ExifInterface.TAG_ORF_THUMBNAIL_IMAGE)?.let(::add)
                exif.thumbnailBytes?.let(::add)
            }
            candidates.asSequence()
                .filter { it.size in 4..MAX_ENTRY_BYTES && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
                .mapNotNull(::inspectJpeg)
                .maxByOrNull { it.width.toLong() * it.height.toLong() }
        } ?: return null

        synchronized(cache) {
            cache[key]?.let { return it }
            cache[key] = extracted
            cachedBytes += extracted.bytes.size
            val iterator = cache.entries.iterator()
            while (cachedBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
                val oldest = iterator.next()
                if (oldest.key == key && cache.size == 1) break
                cachedBytes -= oldest.value.bytes.size
                iterator.remove()
            }
        }
        return extracted
    }

    fun peek(key: String): RawEmbeddedPreview? = synchronized(cache) { cache[key] }

    private fun inspectJpeg(bytes: ByteArray): RawEmbeddedPreview? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return RawEmbeddedPreview(bytes, options.outWidth, options.outHeight)
    }
}

internal class RawEmbeddedPreviewRegionDecoder(
    private val sourceKey: String,
    private val sourcePath: String,
) : ImageRegionDecoder {
    private var rawStore: IndexedRawStore? = null
    private var indexedDecoder: IndexedRawRegionDecoder? = null
    private var indexedGeneration = Long.MIN_VALUE
    private var indexedDecodeFailed = false
    private var decoder: BitmapRegionDecoder? = null
    private val lock = Any()

    override fun init(context: Context, uri: Uri): Point = synchronized(lock) {
        rawStore = IndexedRawStore(context.applicationContext)
        refreshIndexedDecoder()?.let { indexed ->
            return Point(indexed.sourceWidth, indexed.sourceHeight)
        }
        val preview = RawEmbeddedPreviewStore.peek(sourceKey)
            ?: throw IllegalStateException("RAW embedded preview was evicted before decoder init")
        @Suppress("DEPRECATION")
        val regionDecoder = BitmapRegionDecoder.newInstance(preview.bytes, 0, preview.bytes.size, false)
            ?: throw IllegalArgumentException("Unable to region-decode RAW embedded preview")
        decoder = regionDecoder
        Point(regionDecoder.width, regionDecoder.height)
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap = synchronized(lock) {
        refreshIndexedDecoder()?.let { indexed ->
            val bitmap = try {
                indexed.decodeRegion(sRect, sampleSize.coerceAtLeast(1))
            } catch (_: Throwable) {
                null
            }
            if (bitmap != null) return bitmap
            indexed.close()
            indexedDecoder = null
            indexedDecodeFailed = true
        }
        val active = decoder ?: throw IllegalStateException("RAW preview decoder is recycled")
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        active.decodeRegion(sRect, options)
            ?: throw IllegalStateException("RAW embedded preview region decode returned null")
    }

    override fun isReady(): Boolean = indexedDecoder != null || decoder?.isRecycled == false

    override fun recycle() = synchronized(lock) {
        indexedDecoder?.close()
        indexedDecoder = null
        rawStore = null
        indexedGeneration = Long.MIN_VALUE
        indexedDecodeFailed = false
        decoder?.recycle()
        decoder = null
    }

    private fun refreshIndexedDecoder(): IndexedRawRegionDecoder? {
        val activeStore = rawStore ?: return null
        val generation = activeStore.currentGeneration
        if (indexedGeneration != generation) {
            indexedDecoder?.close()
            indexedDecoder = null
            indexedGeneration = generation
            indexedDecodeFailed = false
        }
        if (indexedDecodeFailed) return null
        indexedDecoder?.let { return it }
        indexedDecoder = try {
            activeStore.openDecoder(sourcePath)
        } catch (_: Throwable) {
            null
        }
        if (indexedDecoder == null) indexedDecodeFailed = true
        return indexedDecoder
    }
}

private fun openRawDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
    return if (uri.scheme == null || uri.scheme == "file") {
        ParcelFileDescriptor.open(File(uri.path ?: uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)
    } else {
        context.contentResolver.openFileDescriptor(uri, "r")
    }
}
