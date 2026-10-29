package com.pixel.gallery.ui.viewer

import kotlin.math.abs
import kotlin.math.sign

/** One finger, one direction decision. Rejected strokes can never become dismissals. */
internal class DismissGestureTracker(private val touchSlop: Float, private val density: Float) {
    enum class Intent { UNDECIDED, DISMISS, REJECTED }
    var intent = Intent.REJECTED
        private set
    var offset = 0f
        private set
    private var downX = 0f
    private var downY = 0f

    fun begin(x: Float, y: Float, eligible: Boolean) {
        downX = x
        downY = y
        offset = 0f
        intent = if (eligible) Intent.UNDECIDED else Intent.REJECTED
    }

    fun move(x: Float, y: Float) {
        if (intent == Intent.REJECTED) return
        val dx = x - downX
        val dy = y - downY
        if (intent == Intent.UNDECIDED && maxOf(abs(dx), abs(dy)) > touchSlop) {
            intent = if (abs(dy) > abs(dx) * 1.3f) Intent.DISMISS else Intent.REJECTED
        }
        if (intent == Intent.DISMISS) offset = sign(dy) * (abs(dy) - touchSlop).coerceAtLeast(0f)
    }

    fun finish(viewportHeight: Float, velocityY: Float): Boolean {
        val distance = abs(offset)
        val flingSpeed = 1200f * density
        val reversing = velocityY * offset < 0f && abs(velocityY) >= flingSpeed
        val distanceThreshold = (viewportHeight * .22f).coerceIn(64f * density, 160f * density)
        val dismiss = intent == Intent.DISMISS && !reversing &&
            (distance >= distanceThreshold ||
                (distance >= 32f * density && abs(velocityY) >= flingSpeed && velocityY * offset > 0f))
        cancel()
        return dismiss
    }

    fun cancel() { intent = Intent.REJECTED; offset = 0f }
}
