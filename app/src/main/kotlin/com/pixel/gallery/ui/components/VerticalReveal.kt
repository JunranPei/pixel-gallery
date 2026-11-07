package com.pixel.gallery.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Drag updates are synchronous; animation is reserved for settling after release. */
@Stable
class VerticalRevealState internal constructor(
    expanded: Boolean,
    private val scope: CoroutineScope,
    private val onExpandedChange: (Boolean) -> Unit,
) {
    var fraction by mutableFloatStateOf(if (expanded) 1f else 0f)
        private set
    var dragging by mutableStateOf(false)
        private set
    private var animation: Job? = null
    private var lastRange = 0f
    private var speed = 0f
    private var target = if (expanded) 1f else 0f
    private var startExpanded = expanded

    fun start() {
        animation?.cancel()
        startExpanded = fraction >= .5f
        speed = 0f
        dragging = true
    }

    fun dragBy(delta: Float, range: Float) {
        lastRange = range
        if (range > 0f) fraction = (fraction - delta / range).coerceIn(0f, 1f)
    }

    fun finish(velocity: Float = 0f, flingThreshold: Float = Float.MAX_VALUE) {
        dragging = false
        val expanded = when {
            velocity < -flingThreshold -> true
            velocity > flingThreshold -> false
            else -> fraction >= .5f
        }
        speed = if (lastRange > 0f) -velocity / lastRange else 0f
        settle(expanded)
        onExpandedChange(expanded)
    }

    fun cancel() {
        dragging = false
        onExpandedChange(startExpanded)
        settle(startExpanded)
    }

    fun settle(expanded: Boolean) {
        if (dragging) return
        val next = if (expanded) 1f else 0f
        if (animation?.isActive == true && target == next) return
        animation?.cancel()
        target = next
        animation = scope.launch {
            animate(fraction, next, initialVelocity = speed, animationSpec = GalleryMotion.fraction()) { value, velocity ->
                fraction = value.coerceIn(0f, 1f)
                speed = velocity
            }
            fraction = next
            speed = 0f
        }
    }
}

@Composable
fun rememberVerticalRevealState(expanded: Boolean, onExpandedChange: (Boolean) -> Unit): VerticalRevealState {
    val scope = rememberCoroutineScope()
    val onChange by rememberUpdatedState(onExpandedChange)
    val state = remember { VerticalRevealState(expanded, scope) { onChange(it) } }
    LaunchedEffect(expanded) { state.settle(expanded) }
    return state
}

@Composable
fun Modifier.verticalRevealDrag(
    state: VerticalRevealState,
    rangePx: Float,
    enabled: Boolean = true,
    flingThresholdPx: Float,
): Modifier {
    val range by rememberUpdatedState(rangePx)
    val threshold by rememberUpdatedState(flingThresholdPx)
    return if (!enabled) this else pointerInput(state) {
        val velocity = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = { velocity.resetTracking(); state.start() },
            onVerticalDrag = { change, delta ->
                change.consume()
                velocity.addPosition(change.uptimeMillis, change.position)
                state.dragBy(delta, range)
            },
            onDragEnd = { state.finish(velocity.calculateVelocity().y, threshold) },
            onDragCancel = state::cancel,
        )
    }
}

/** Measure at full size, then reveal a continuous fraction without remounting the actions. */
fun Modifier.revealHeight(fraction: Float, onFullHeight: (Int) -> Unit = {}): Modifier =
    clipToBounds()
        .alpha(fraction)
        .then(if (fraction == 0f) Modifier.clearAndSetSemantics {} else Modifier)
        .layout { measurable, constraints ->
            val content = measurable.measure(constraints.copy(minHeight = 0))
            onFullHeight(content.height)
            layout(content.width, (content.height * fraction).roundToInt()) {
                content.placeRelative(0, 0)
            }
        }
