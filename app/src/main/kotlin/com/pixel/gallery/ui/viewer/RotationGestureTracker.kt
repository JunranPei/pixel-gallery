package com.pixel.gallery.ui.viewer

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

internal data class RotationPointer(val id: Int, val x: Float, val y: Float)

/** One fixed pair per stroke. No clocks, animation, renderer state or persistent settings. */
internal class RotationGestureTracker(
    private val touchSlop: Float,
    private val minimumSpan: Float,
) {
    enum class Intent { UNDECIDED, ROTATION, TRANSFORM, REJECTED }

    var intent = Intent.REJECTED
        private set
    private var firstId = -1
    private var secondId = -1
    private var startSpan = 0f
    private var startX = 0f
    private var startY = 0f
    private var lastAngle = 0f
    var angle = 0f
        private set
    private var pairReleased = false

    fun begin(first: RotationPointer, second: RotationPointer) {
        cancel()
        val span = hypot(second.x - first.x, second.y - first.y)
        if (!span.isFinite() || span < minimumSpan || first.id == second.id) return
        firstId = first.id
        secondId = second.id
        startSpan = span
        startX = (first.x + second.x) / 2f
        startY = (first.y + second.y) / 2f
        lastAngle = direction(first, second)
        angle = 0f
        pairReleased = false
        intent = Intent.UNDECIDED
    }

    fun move(first: RotationPointer, second: RotationPointer) {
        if (pairReleased || intent == Intent.REJECTED || intent == Intent.TRANSFORM) return
        if (first.id != firstId || second.id != secondId) { cancel(); return }
        val span = hypot(second.x - first.x, second.y - first.y)
        if (!span.isFinite() || span < minimumSpan) { cancel(); return }
        val nextAngle = direction(first, second)
        var delta = nextAngle - lastAngle
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        angle += delta
        lastAngle = nextAngle
        if (intent != Intent.UNDECIDED) return

        val arc = abs(angle) * (Math.PI.toFloat() / 180f) * startSpan / 2f
        val pinch = abs(span - startSpan) / 2f
        val pan = hypot((first.x + second.x) / 2f - startX, (first.y + second.y) / 2f - startY)
        val translation = max(pinch, pan)
        if (arc > touchSlop && arc > translation * 1.2f) {
            intent = Intent.ROTATION
        } else if (translation > touchSlop && (translation > arc || translation > touchSlop * 2f)) {
            // Once a pinch/pan wins, later finger drift cannot turn it into a rotation.
            intent = Intent.TRANSFORM
        }
    }

    fun releasePair() { pairReleased = true }

    fun finish(): Boolean {
        return finishQuarterTurn() != 0
    }

    fun finishQuarterTurn(): Int {
        val rotate = if (intent == Intent.ROTATION && abs(angle) >= 35f) {
            if (angle > 0f) 1 else -1
        } else 0
        cancel()
        return rotate
    }

    fun cancel() { intent = Intent.REJECTED; pairReleased = true }

    private fun direction(first: RotationPointer, second: RotationPointer) =
        Math.toDegrees(atan2((second.y - first.y).toDouble(), (second.x - first.x).toDouble())).toFloat()
}
