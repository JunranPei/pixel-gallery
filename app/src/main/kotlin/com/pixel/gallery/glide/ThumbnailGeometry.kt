package com.pixel.gallery.glide

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** BitmapFactory rounds sampled JPEG dimensions up; never lend a smaller allocation. */
internal fun thumbnailSampledDimension(dimension: Int, sample: Int): Int {
    require(dimension > 0 && sample > 0)
    return dimension / sample + if (dimension % sample == 0) 0 else 1
}

/** A requested preview must contain real pixels, capped by the original image's long edge. */
internal fun thumbnailMeetsMinimumLongEdge(
    thumbnailWidth: Int,
    thumbnailHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    minimumLongEdgePixels: Int,
): Boolean {
    if (minimumLongEdgePixels <= 0) return true
    if (thumbnailWidth <= 0 || thumbnailHeight <= 0) return false
    val sourceLongEdge = max(sourceWidth, sourceHeight)
        .takeIf { sourceWidth > 0 && sourceHeight > 0 } ?: minimumLongEdgePixels
    return max(thumbnailWidth, thumbnailHeight) >= min(minimumLongEdgePixels, sourceLongEdge)
}

/** Compare oriented image bounds while allowing one pixel of thumbnail rounding per axis. */
internal fun thumbnailMatchesSourceAspect(
    thumbnailWidth: Int,
    thumbnailHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
): Boolean {
    if (thumbnailWidth <= 0 || thumbnailHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return true
    val quarterTurn = ((rotationDegrees % 180) + 180) % 180 == 90
    val orientedWidth = if (quarterTurn) sourceHeight else sourceWidth
    val orientedHeight = if (quarterTurn) sourceWidth else sourceHeight
    val difference = abs(thumbnailWidth.toLong() * orientedHeight - thumbnailHeight.toLong() * orientedWidth)
    return difference <= orientedWidth.toLong() + orientedHeight
}

/** FIT_CENTER needs the long edge to fit, including very wide panoramas. */
internal fun thumbnailFitSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (width / sample / 2 >= targetWidth || height / sample / 2 >= targetHeight) sample *= 2
    return sample
}
