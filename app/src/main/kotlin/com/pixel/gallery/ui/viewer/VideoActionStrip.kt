package com.pixel.gallery.ui.viewer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

// Cumulative widths after mute: rotation (48), speed (64), capture (48), PiP (48).
private val ActionStops = listOf(0f, 48f / 208f, 112f / 208f, 160f / 208f, 1f)

private fun nearestActionStop(fraction: Float): Int =
    ActionStops.indices.minBy { abs(ActionStops[it] - fraction) }

/** The row moves, not the toolbar or viewer layout. Only mute remains inside the clip at rest. */
@Composable
internal fun VideoActionStrip(session: VideoPlaybackSession, onBusyChanged: (Boolean) -> Unit,
    content: @Composable RowScope.(visibleActions: Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var fraction by remember(session) { mutableFloatStateOf(0f) }
    var dragging by remember(session) { mutableStateOf(false) }
    var settling by remember(session) { mutableStateOf(false) }
    var animation by remember(session) { mutableStateOf<Job?>(null) }
    val range = with(LocalDensity.current) { 208.dp.toPx() }
    val flingThreshold = with(LocalDensity.current) { 600.dp.toPx() }
    val latestBusy by rememberUpdatedState(onBusyChanged)
    val showLabel = stringResource(R.string.video_show_actions)
    val hideLabel = stringResource(R.string.video_hide_actions)

    fun settle(stop: Int) {
        animation?.cancel()
        animation = scope.launch {
            settling = true
            try {
                animate(fraction, ActionStops[stop.coerceIn(ActionStops.indices)], animationSpec = tween(180)) { value, _ -> fraction = value }
            } finally { settling = false }
        }
    }
    DisposableEffect(dragging, settling) {
        latestBusy(dragging || settling)
        onDispose { latestBusy(false) }
    }
    DisposableEffect(session) { onDispose { animation?.cancel() } }

    Box(Modifier.width(256.dp).height(48.dp).clipToBounds()
        .semantics {
            val stop = nearestActionStop(fraction)
            customActions = buildList {
                if (stop < ActionStops.lastIndex) add(CustomAccessibilityAction(showLabel) {
                    settle(stop + 1); true
                })
                if (stop > 0) add(CustomAccessibilityAction(hideLabel) {
                    settle(stop - 1); true
                })
            }
        }
        .pointerInput(session, range, flingThreshold) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // The empty area to the left of a collapsed strip is not a drag target.
                if (down.position.x < range * (1f - fraction)) return@awaitEachGesture
                val startedStop = nearestActionStop(fraction)
                val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    animation?.cancel()
                    dragging = true
                    change.consume()
                    fraction = (fraction - overSlop / range).coerceIn(0f, 1f)
                } ?: return@awaitEachGesture
                velocity.addPosition(start.uptimeMillis, start.position)
                try {
                    val released = horizontalDrag(start.id) { change ->
                        velocity.addPosition(change.uptimeMillis, change.position)
                        fraction = (fraction - (change.position.x - change.previousPosition.x) / range).coerceIn(0f, 1f)
                        change.consume()
                    }
                    val speed = velocity.calculateVelocity().x
                    settle(if (!released) startedStop else when {
                        // Fling to the next section in that direction, never straight to an end.
                        speed < -flingThreshold -> ActionStops.indexOfFirst { it > fraction + .001f }
                            .takeIf { it >= 0 } ?: ActionStops.lastIndex
                        speed > flingThreshold -> ActionStops.indexOfLast { it < fraction - .001f }.coerceAtLeast(0)
                        else -> nearestActionStop(fraction)
                    })
                } finally { dragging = false }
            }
        }) {
        Row(Modifier.requiredWidth(256.dp).height(48.dp).graphicsLayer {
            translationX = range * (1f - fraction)
        }, verticalAlignment = Alignment.CenterVertically) {
            content(1 + ActionStops.drop(1).count { fraction >= it - .001f })
        }
    }
}
