package com.pixel.gallery.ui.viewer.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.ImageRegionDecoder
import io.github.indexedbmp.IndexedBmpRegionDecoder
import io.github.indexedbmp.IndexedBmpStore
import java.io.File

/** SSIV adapter for an explicitly activated uncompressed BMP row decoder. */
internal class BmpRegionDecoder(
    private val sourcePath: String,
) : ImageRegionDecoder {
    private var decoder: IndexedBmpRegionDecoder? = null
    private val lock = Any()

    override fun init(context: Context, uri: Uri): Point = synchronized(lock) {
        val localPath = sourcePath.takeIf { File(it).let { file -> file.isFile && file.canRead() } }
            ?: throw IllegalArgumentException("Indexed BMP requires a readable local file")
        val opened = IndexedBmpStore(context.applicationContext).openDecoder(localPath)
            ?: throw IllegalStateException("BMP activation is missing or no longer matches the source")
        decoder = opened
        Point(opened.sourceWidth, opened.sourceHeight)
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap = synchronized(lock) {
        decoder?.decodeRegion(sRect, sampleSize.coerceAtLeast(1))
            ?: throw IllegalStateException("Indexed BMP region decode failed")
    }

    override fun isReady(): Boolean = decoder != null

    override fun recycle() = synchronized(lock) {
        decoder?.close()
        decoder = null
    }
}
