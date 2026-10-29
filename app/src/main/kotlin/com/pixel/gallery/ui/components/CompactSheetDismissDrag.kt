package com.pixel.gallery.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates

internal fun compactSheetDismissThreshold(preferred: Float, remainingTravel: Float, touchSlop: Float): Float =
    minOf(preferred, (remainingTravel - touchSlop).coerceAtLeast(0f) * .6f)

internal class CompactSheetPull(startedCollapsed: Boolean, firstDelta: Float) {
    val dismissGesture = startedCollapsed && firstDelta > 0f
    var distance = 0f
        private set
    fun drag(delta: Float) { distance = (distance + delta).coerceAtLeast(0f) }
    val offset: Float get() = distance * .35f
    fun shouldDismiss(threshold: Float): Boolean = dismissGesture && distance >= threshold
}

@Composable
internal fun Modifier.compactSheetDismissDrag(
    panel: VerticalRevealState,
    expanded: Boolean,
    rangePx: Float,
    flingThresholdPx: Float,
    dismissDistancePx: Float,
    onPull: (Float) -> Unit,
    onReleasePull: (Boolean) -> Unit,
): Modifier {
    val currentExpanded by rememberUpdatedState(expanded)
    val range by rememberUpdatedState(rangePx)
    val flingThreshold by rememberUpdatedState(flingThresholdPx)
    val dismissDistance by rememberUpdatedState(dismissDistancePx)
    val pull by rememberUpdatedState(onPull)
    val releasePull by rememberUpdatedState(onReleasePull)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates = it }.pointerInput(panel) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startedCollapsed = !currentExpanded && panel.fraction == 0f && !panel.dragging
            val layout = coordinates
            val remainingTravel = layout?.let {
                it.findRootCoordinates().boundsInWindow().bottom - it.localToWindow(down.position).y
            }
            // Latch the reachable distance before the surface follows the finger.
            val closeThreshold = remainingTravel?.let {
                compactSheetDismissThreshold(dismissDistance, it, viewConfiguration.touchSlop)
            } ?: dismissDistance
            var initialDelta = 0f
            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                initialDelta = overSlop
                change.consume()
            } ?: return@awaitEachGesture
            val gesture = CompactSheetPull(startedCollapsed, initialDelta)
            val velocity = VelocityTracker()
            var lastPosition = coordinates?.localToWindow(start.position) ?: start.position
            velocity.addPosition(start.uptimeMillis, lastPosition)
            if (!gesture.dismissGesture) panel.start()
            fun drag(delta: Float) {
                if (gesture.dismissGesture) {
                    gesture.drag(delta)
                    pull(gesture.offset)
                } else panel.dragBy(delta, range)
            }
            drag(initialDelta)
            var finished = false
            try {
                val released = verticalDrag(start.id) { change ->
                    val position = coordinates?.localToWindow(change.position) ?: change.position
                    drag(position.y - lastPosition.y)
                    velocity.addPosition(change.uptimeMillis, position)
                    lastPosition = position
                    change.consume()
                }
                if (gesture.dismissGesture) releasePull(released && gesture.shouldDismiss(closeThreshold))
                else if (released) panel.finish(velocity.calculateVelocity().y, flingThreshold)
                else panel.cancel()
                finished = true
            } finally {
                if (!finished) {
                    if (gesture.dismissGesture) releasePull(false) else panel.cancel()
                }
            }
        }
    }
}
