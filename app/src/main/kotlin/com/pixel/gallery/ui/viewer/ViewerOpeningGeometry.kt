package com.pixel.gallery.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.pixel.gallery.model.ViewerOpeningEffect

internal fun openingFitBounds(width: Float, height: Float, ratio: Float): Rect? {
    if (width <= 0f || height <= 0f || !ratio.isFinite() || ratio <= 0f) return null
    val w = minOf(width, height * ratio)
    val h = w / ratio
    return Rect((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
}

/** Reverse the existing fit-to-crop geometry; never stretch the image to the cell's aspect ratio. */
internal fun openingVisual(effect: ViewerOpeningEffect, source: Rect,
    thumbnail: ViewerThumbnailTarget?, fraction: Float): ViewerDismissVisual {
    val p = fraction.coerceIn(0f, 1f)
    if (effect == ViewerOpeningEffect.NONE || p == 1f) return ViewerDismissVisual()
    if (effect == ViewerOpeningEffect.THUMBNAIL && thumbnail != null) {
        return dismissCloseVisual(source, ViewerDismissVisual(), thumbnail, 1f - p)
    }
    val scale = .94f + .06f * p
    return ViewerDismissVisual(scale = scale, translation = source.center * (1f - scale),
        imageAlpha = p, backdropAlpha = p, chromeAlpha = p)
}
