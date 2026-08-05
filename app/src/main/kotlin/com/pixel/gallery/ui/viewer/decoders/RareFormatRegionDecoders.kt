package com.pixel.gallery.ui.viewer.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.utils.SVGAndroidRenderer
import com.davemorrissey.labs.subscaleview.ImageRegionDecoder
import com.pixel.gallery.metadata.SVGParserBufferedInputStream
import com.pixel.gallery.metadata.SvgHelper.IMAGE_BASE64_SIZE_DANGER_THRESHOLD
import com.pixel.gallery.metadata.SvgHelper.normalizeSize
import com.pixel.gallery.utils.StorageUtils
import io.github.indexedtiff.IndexedTiffRegionDecoder
import io.github.indexedtiff.IndexedTiffStore
import org.beyka.tiffbitmapfactory.DecodeArea
import org.beyka.tiffbitmapfactory.TiffBitmapFactory
import java.io.File
import kotlin.math.ceil

class TiffRegionDecoder : ImageRegionDecoder {
    private var store: IndexedTiffStore? = null
    private var indexedDecoder: IndexedTiffRegionDecoder? = null
    private var indexedGeneration = Long.MIN_VALUE
    private var indexedDecodeFailed = false
    private var localSourcePath: String? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var width = 0
    private var height = 0
    private val lock = Any()

    override fun init(context: Context, uri: Uri): Point = synchronized(lock) {
        store = IndexedTiffStore(context.applicationContext)
        localSourcePath = if (uri.scheme == null || uri.scheme == "file") {
            val path = uri.path ?: uri.toString()
            File(path).takeIf { it.isFile && it.canRead() }?.absolutePath
        } else {
            null
        }
        descriptor = openRareFormatDescriptor(context, uri)
            ?: throw IllegalArgumentException("Unable to open TIFF uri=$uri")
        val options = TiffBitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inUseOrientationTag = false
            inThrowException = true
        }
        TiffBitmapFactory.decodeFileDescriptor(descriptor!!.fd, options)
        width = options.outWidth
        height = options.outHeight
        if (width <= 0 || height <= 0) {
            recycle()
            throw IllegalArgumentException("Invalid TIFF dimensions for uri=$uri")
        }
        Point(width, height)
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
        val pfd = descriptor ?: throw IllegalStateException("TIFF decoder is recycled")
        val rect = Rect(sRect).apply { intersect(0, 0, width, height) }
        if (rect.isEmpty) throw IllegalArgumentException("Empty TIFF region: $sRect")
        val options = TiffBitmapFactory.Options().apply {
            inUseOrientationTag = false
            inThrowException = true
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = TiffBitmapFactory.ImageConfig.ARGB_8888
            inDecodeArea = DecodeArea(rect.left, rect.top, rect.width(), rect.height())
        }
        TiffBitmapFactory.decodeFileDescriptor(pfd.fd, options)
            ?: throw IllegalStateException("TIFF region decode returned null for $rect")
    }

    override fun isReady(): Boolean = descriptor != null && width > 0 && height > 0

    override fun recycle() = synchronized(lock) {
        indexedDecoder?.close()
        indexedDecoder = null
        store = null
        indexedGeneration = Long.MIN_VALUE
        indexedDecodeFailed = false
        localSourcePath = null
        descriptor?.close()
        descriptor = null
        width = 0
        height = 0
    }

    private fun refreshIndexedDecoder(): IndexedTiffRegionDecoder? {
        val activeStore = store ?: return null
        val sourcePath = localSourcePath ?: return null
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

class SvgRegionDecoder : ImageRegionDecoder {
    private var picture: Picture? = null
    private var width = 0
    private var height = 0
    private val lock = Any()

    override fun init(context: Context, uri: Uri): Point = synchronized(lock) {
        val svg = StorageUtils.openInputStream(context, uri)?.use { input ->
            SVG.getFromInputStream(SVGParserBufferedInputStream(input))
        } ?: throw IllegalArgumentException("Unable to parse SVG uri=$uri")
        svg.normalizeSize()
        val viewBox = svg.documentViewBox
            ?: throw IllegalArgumentException("SVG has no usable viewBox for uri=$uri")
        width = ceil(viewBox.width().toDouble()).toInt().coerceAtLeast(1)
        height = ceil(viewBox.height().toDouble()).toInt().coerceAtLeast(1)
        SVGAndroidRenderer.setImageBase64StringMaxSize(IMAGE_BASE64_SIZE_DANGER_THRESHOLD)
        picture = svg.renderToPicture(width, height)
        Point(width, height)
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap = synchronized(lock) {
        val source = picture ?: throw IllegalStateException("SVG decoder is recycled")
        val sample = sampleSize.coerceAtLeast(1)
        val rect = Rect(sRect).apply { intersect(0, 0, width, height) }
        if (rect.isEmpty) throw IllegalArgumentException("Empty SVG region: $sRect")
        val outWidth = ceil(rect.width().toDouble() / sample).toInt().coerceAtLeast(1)
        val outHeight = ceil(rect.height().toDouble() / sample).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                scale(1f / sample, 1f / sample)
                translate(-rect.left.toFloat(), -rect.top.toFloat())
                drawPicture(source)
            }
        }
    }

    override fun isReady(): Boolean = picture != null

    override fun recycle() = synchronized(lock) {
        picture = null
        width = 0
        height = 0
    }
}

private fun openRareFormatDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
    return if (uri.scheme == null || uri.scheme == "file") {
        val path = uri.path ?: uri.toString()
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    } else {
        context.contentResolver.openFileDescriptor(uri, "r")
    }
}
