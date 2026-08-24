package com.davemorrissey.labs.subscaleview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri

interface ImageRegionDecoder {
    fun isReady(): Boolean

    fun init(context: Context, uri: Uri): Point

    fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap

    fun recycle()
}

/**
 * Describes which source-miss optimisations are useful for a batched decoder.
 *
 * A persistent tile pyramid has already paid the cost of splitting and storing regions. Merging
 * its misses into a larger bitmap and persisting the decoded result again only adds allocations
 * and I/O. Sequential source codecs keep the original behaviour through these defaults.
 */
data class RegionDecoderCapabilities(
    val batchSourceMisses: Boolean = true,
    val persistDecodedTiles: Boolean = true,
)

/** Optional extension for amortizing adjacent source-cache misses. */
interface BatchedImageRegionDecoder : ImageRegionDecoder {
    fun capabilities(): RegionDecoderCapabilities = RegionDecoderCapabilities()

    fun isRegionCached(sRect: Rect, sampleSize: Int): Boolean

    fun decodeRegions(sRects: List<Rect>, sampleSize: Int): List<Bitmap>

    /** Persist a decoded tile after SSIV confirms the viewport has remained stable. */
    fun cacheRegion(sRect: Rect, sampleSize: Int, bitmap: Bitmap): Boolean
}
