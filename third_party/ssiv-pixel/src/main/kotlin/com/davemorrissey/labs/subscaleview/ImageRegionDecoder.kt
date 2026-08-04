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

/** Optional extension for amortizing adjacent source-cache misses. */
interface BatchedImageRegionDecoder : ImageRegionDecoder {
    fun isRegionCached(sRect: Rect, sampleSize: Int): Boolean

    fun decodeRegions(sRects: List<Rect>, sampleSize: Int): List<Bitmap>
}
