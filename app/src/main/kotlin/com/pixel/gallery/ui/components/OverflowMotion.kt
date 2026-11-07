package com.pixel.gallery.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.*

internal data class OverflowFrame(
    val bounds: Rect,
    val corner: Float,
    val contentAlpha: Float,
    val surfaceAlpha: Float,
    val glyphAlpha: Float,
)

/** Motion parameters follow FlClash/prism_ui's anchored popup; surfaces remain Gallery's MD3. */
internal object OverflowMotion {
    const val OpenMillis = 450
    const val CloseMillis = 500
    fun closed(anchor: Rect) = OverflowFrame(anchor, anchor.minDimension / 2, 0f, 0f, 1f)
    fun opened(target: Rect, corner: Float) = OverflowFrame(target, corner, 1f, 1f, 0f)

    private fun response(t: Float, stiffness: Float, damping: Float): Float {
        val w = sqrt(stiffness)
        val wd = w * sqrt(1 - damping * damping)
        return 1 - exp(-damping * w * t) * (cos(wd * t) + damping * w / wd * sin(wd * t))
    }
    private fun bump(t: Float, peak: Float, sharpness: Float): Float {
        if (t <= 0f) return 0f
        val x = t / peak
        return x.pow(sharpness) * exp(sharpness * (1 - x))
    }
    private fun mix(a: Float, b: Float, p: Float) = a + (b - a) * p

    fun frame(from: OverflowFrame, to: OverflowFrame, fraction: Float, opening: Boolean, density: Float): OverflowFrame {
        if (fraction <= 0f) return from
        if (fraction >= 1f) return to
        val duration = if (opening) .45f else .5f
        val t = fraction * duration
        val stiffness = if (opening) 400f else 165f
        val damping = if (opening) .754f else .8f
        // Remove only the remaining tail; interruption always starts at the last displayed frame.
        val p = response(t, stiffness, damping) + (1 - response(duration, stiffness, damping)) * fraction
        val width = mix(from.bounds.width, to.bounds.width, p).coerceAtLeast(1f)
        val height = mix(from.bounds.height, to.bounds.height, p).coerceAtLeast(1f)
        val distance = to.bounds.center.y - from.bounds.center.y
        val dip = if (opening) sign(distance) * (min(1f, abs(distance) / (30f * density)) * 23f * density + .124f * abs(distance)) * bump(t, .098f, 3.3f)
            else -sign(distance) * min(.21f * abs(distance), 22f * density) * bump(t, .072f, 2.4f)
        val center = Offset(mix(from.bounds.center.x, to.bounds.center.x, p), mix(from.bounds.center.y, to.bounds.center.y, p) + dip)
        val cornerP = if (opening) {
            val ct = (t - .07f).coerceAtLeast(0f)
            response(ct, 324f, .75f) + (1 - response(.38f, 324f, .75f)) * (ct / .38f)
        } else p
        val corner = (if (opening) {
            val peak = from.corner * to.bounds.width / from.bounds.width.coerceAtLeast(1f) - to.corner
            (to.corner + peak * (1 - cornerP)) * width / to.bounds.width.coerceAtLeast(1f)
        } else {
            mix(from.corner, from.bounds.width / 2, p) * width / from.bounds.width.coerceAtLeast(1f)
        }).coerceIn(0f, min(width, height) / 2)
        val content = if (opening) mix(from.contentAlpha, 1f, p.coerceIn(0f, 1f))
            else from.contentAlpha * (1 - (t / .1f).coerceIn(0f, 1f)).pow(2)
        val surface = if (opening) mix(from.surfaceAlpha, 1f, (t / .1f).coerceIn(0f, 1f))
            else from.surfaceAlpha * (1 - ((fraction - .6f) / .3f).coerceIn(0f, 1f))
        val glyph = if (opening) from.glyphAlpha * (1 - (p / .25f).coerceIn(0f, 1f))
            else mix(from.glyphAlpha, 1f, (t / .47f).coerceIn(0f, 1f))
        return OverflowFrame(Rect(center.x - width / 2, center.y - height / 2, center.x + width / 2, center.y + height / 2), corner, content, surface, glyph)
    }
}
