package com.pixel.gallery.ui.viewer

import android.view.MotionEvent
import android.app.Activity
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sign

internal val LocalViewerDismissGesture = staticCompositionLocalOf<ViewerDismissGesture?> { null }

/** Eligibility is queried on DOWN, never polled. Only the settled image registers here. */
@Composable
internal fun ViewerDismissImageEligibility(canDismiss: () -> Boolean) {
    val gesture = LocalViewerDismissGesture.current
    val current by rememberUpdatedState(canDismiss)
    DisposableEffect(gesture) {
        val token = Any()
        gesture?.imageEligibility?.put(token) { current() }
        onDispose { gesture?.imageEligibility?.remove(token) }
    }
}

internal class ViewerDismissGesture(
    touchSlop: Float,
    density: Float,
    private val maxFlingVelocity: Float,
    private val scope: CoroutineScope,
) {
    var enabled: () -> Boolean = { false }
    var onDismiss: () -> Unit = {}
    val imageEligibility = mutableMapOf<Any, () -> Boolean>()
    var offset by mutableFloatStateOf(0f)
        private set
    private val tracker = DismissGestureTracker(touchSlop, density)
    private var velocity: VelocityTracker? = null
    private var pointerId = -1
    private var claimed = false
    private var blockedStroke = false
    private var settling = false
    private var viewportHeight = 0f
    private var viewportWidth = 0
    private var animation: Job? = null
    private var animationGeneration = 0

    fun resize(width: Int, height: Int) {
        if (viewportWidth != width || viewportHeight != height.toFloat()) reset()
        viewportWidth = width
        viewportHeight = height.toFloat()
    }

    fun reset() {
        animationGeneration++
        animation?.cancel()
        animation = null
        settling = false
        offset = 0f
        tracker.cancel()
        velocity?.recycle()
        velocity = null
        // If the child already received CANCEL, continue swallowing that stroke.
    }

    private fun settle(dismiss: Boolean) {
        val generation = ++animationGeneration
        animation?.cancel()
        val start = offset
        if (start == 0f) return
        settling = true
        animation = scope.launch {
            try {
                animate(start, if (dismiss) sign(start) * viewportHeight else 0f,
                    animationSpec = tween(if (dismiss) 180 else 150)) { value, _ -> offset = value }
                if (dismiss) onDismiss()
            } finally {
                if (generation == animationGeneration) settling = false
            }
        }
    }

    fun dispatch(event: MotionEvent, inMedia: (Float, Float) -> Boolean,
        toContent: (MotionEvent) -> Boolean): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            claimed = false
            blockedStroke = settling
            velocity?.recycle()
            velocity = null
            pointerId = event.getPointerId(0)
            val eligible = !blockedStroke && enabled() && viewportHeight > 0f &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && inMedia(event.x, event.y) &&
                imageEligibility.values.any { it() }
            tracker.begin(event.x, event.y, eligible)
            if (eligible) velocity = VelocityTracker.obtain()
        }
        if (blockedStroke) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) blockedStroke = false
            return true
        }
        velocity?.addMovement(event)
        if (!enabled() || action == MotionEvent.ACTION_CANCEL || event.pointerCount != 1 ||
            action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP ||
            event.getPointerId(0) != pointerId) {
            tracker.cancel()
            if (claimed && !settling) settle(false)
        }
        if (action == MotionEvent.ACTION_MOVE) {
            tracker.move(event.x, event.y)
            if (tracker.intent == DismissGestureTracker.Intent.DISMISS) {
                if (!claimed) {
                    claimed = true
                    MotionEvent.obtain(event).also { cancel ->
                        cancel.action = MotionEvent.ACTION_CANCEL
                        try { toContent(cancel) } finally { cancel.recycle() }
                    }
                }
                offset = tracker.offset.coerceIn(-viewportHeight, viewportHeight)
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            val consume = claimed
            if (consume && !settling) {
                velocity?.computeCurrentVelocity(1000, maxFlingVelocity)
                val dismiss = action == MotionEvent.ACTION_UP && enabled() &&
                    tracker.finish(viewportHeight, velocity?.getYVelocity(pointerId) ?: 0f)
                settle(dismiss)
            }
            tracker.cancel()
            velocity?.recycle()
            velocity = null
            claimed = false
            return if (consume) true else toContent(event)
        }
        return if (claimed) true else toContent(event)
    }
}

@Composable
internal fun rememberViewerDismissGesture(key: Any?, isForeground: Boolean, enabled: Boolean,
    onDismiss: () -> Unit): ViewerDismissGesture {
    val context = LocalContext.current
    val host = context as? Activity
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentEnabled by rememberUpdatedState(enabled && isForeground)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val gesture = remember(key, context) {
        val config = ViewConfiguration.get(context)
        ViewerDismissGesture(config.scaledTouchSlop.toFloat(), context.resources.displayMetrics.density,
            config.scaledMaximumFlingVelocity.toFloat(), scope).apply {
            this.enabled = { currentEnabled && host?.hasWindowFocus() == true && !host.isInPictureInPictureMode }
            this.onDismiss = { if (this.enabled()) currentDismiss() else reset() }
        }
    }
    LaunchedEffect(gesture, enabled) { if (!enabled) gesture.reset() }
    DisposableEffect(gesture, lifecycle, isForeground) {
        if (!isForeground) gesture.reset()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) gesture.reset()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); gesture.reset() }
    }
    return gesture
}

/** Only a RenderNode translation changes during a drag: no image resize, scale or alpha layer. */
internal fun Modifier.viewerDismissMotion(gesture: ViewerDismissGesture): Modifier =
    graphicsLayer { translationY = gesture.offset }
