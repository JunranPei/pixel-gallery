package com.pixel.gallery.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max

internal data class ViewerDismissVisual(
    val scale: Float = 1f,
    val translation: Offset = Offset.Zero,
    val clip: Rect? = null,
    val cornerRadius: Float = 0f,
    val imageAlpha: Float = 1f,
    val backdropAlpha: Float = 1f,
    val chromeAlpha: Float = 1f,
)

internal data class ViewerThumbnailTarget(val bounds: Rect, val cornerRadius: Float)

internal fun dismissDragVisual(source: Rect, dx: Float, dy: Float, height: Float, density: Float): ViewerDismissVisual {
    val progress = (abs(dy) / (height * .4f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val scale = 1f - .14f * progress
    return ViewerDismissVisual(
        scale = scale,
        translation = source.center * (1f - scale) + Offset(dx * .8f, dy * .8f),
        clip = source,
        backdropAlpha = (1f - progress).coerceAtLeast(.08f),
        chromeAlpha = (1f - abs(dy) / (64f * density)).coerceIn(0f, 1f),
    )
}

internal fun dismissReturnVisual(start: ViewerDismissVisual, fraction: Float): ViewerDismissVisual {
    val p = fraction.coerceIn(0f, 1f)
    return start.copy(
        scale = mix(start.scale, 1f, p),
        translation = start.translation * (1f - p),
        cornerRadius = start.cornerRadius * (1f - p),
        imageAlpha = mix(start.imageAlpha, 1f, p),
        backdropAlpha = mix(start.backdropAlpha, 1f, p),
        chromeAlpha = mix(start.chromeAlpha, 1f, p),
    )
}

/** Keep Fit pixels intact, then continuously crop them into the grid's Crop bounds. */
internal fun dismissCloseVisual(source: Rect, start: ViewerDismissVisual,
    target: ViewerThumbnailTarget?, fraction: Float): ViewerDismissVisual {
    val p = fraction.coerceIn(0f, 1f)
    if (target == null) {
        val scale = start.scale * (1f - .06f * p)
        val center = source.center * start.scale + start.translation
        return start.copy(scale = scale, translation = center - source.center * scale,
            imageAlpha = 1f - p, backdropAlpha = start.backdropAlpha * (1f - p),
            chromeAlpha = start.chromeAlpha * (1f - p))
    }
    val endScale = max(target.bounds.width / source.width, target.bounds.height / source.height)
    val scale = mix(start.scale, endScale, p).coerceAtLeast(.0001f)
    val startCenter = source.center * start.scale + start.translation
    val center = startCenter * (1f - p) + target.bounds.center * p
    val translation = center - source.center * scale
    val startClip = source.scaledAndTranslated(start.scale, start.translation)
    val screenClip = Rect(mix(startClip.left, target.bounds.left, p),
        mix(startClip.top, target.bounds.top, p), mix(startClip.right, target.bounds.right, p),
        mix(startClip.bottom, target.bounds.bottom, p))
    return ViewerDismissVisual(scale = scale, translation = translation,
        clip = Rect((screenClip.left - translation.x) / scale, (screenClip.top - translation.y) / scale,
            (screenClip.right - translation.x) / scale, (screenClip.bottom - translation.y) / scale),
        cornerRadius = mix(start.cornerRadius * start.scale, target.cornerRadius, p) / scale,
        backdropAlpha = start.backdropAlpha * (1f - p), chromeAlpha = start.chromeAlpha * (1f - p))
}

internal fun Rect.scaledAndTranslated(scale: Float, translation: Offset) =
    Rect(left * scale + translation.x, top * scale + translation.y,
        right * scale + translation.x, bottom * scale + translation.y)

private fun mix(a: Float, b: Float, fraction: Float) = a + (b - a) * fraction
