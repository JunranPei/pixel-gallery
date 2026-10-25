package com.pixel.gallery.ui.viewer

import android.view.MotionEvent
import android.app.Activity
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val LocalViewerDismissGesture = staticCompositionLocalOf<ViewerDismissGesture?> { null }

/** Eligibility is queried on DOWN, never polled. Only the settled image registers here. */
@Composable
internal fun ViewerDismissImageEligibility(bounds: () -> Rect? = { null }, canDismiss: () -> Boolean) {
    val gesture = LocalViewerDismissGesture.current
    val current by rememberUpdatedState(canDismiss)
    val currentBounds by rememberUpdatedState(bounds)
    DisposableEffect(gesture) {
        val token = Any()
        gesture?.imageEligibility?.put(token) { if (current()) currentBounds() else null }
        onDispose { gesture?.imageEligibility?.remove(token) }
    }
}

internal class ViewerDismissGesture(
    touchSlop: Float,
    private val density: Float,
    private val maxFlingVelocity: Float,
    private val scope: CoroutineScope,
) {
    var enabled: () -> Boolean = { false }
    var onDismiss: () -> Unit = {}
    val imageEligibility = mutableMapOf<Any, () -> Rect?>()
    var onReveal: () -> Unit = {}
    var findTarget: () -> ViewerThumbnailTarget? = { null }
    var windowOrigin = Offset.Zero
    var visual by mutableStateOf(ViewerDismissVisual())
        private set
    var isRevealing by mutableStateOf(false)
        private set
    private var sourceBounds: Rect? = null
    private var downX = 0f
    private val tracker = DismissGestureTracker(touchSlop, density)
    private var velocity: VelocityTracker? = null
    private var pointerId = -1
    private var claimed = false
    private var blockedStroke = false
    private var settling = false
    var viewportHeight = 0f
        private set
    var viewportWidth = 0
        private set
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
        visual = ViewerDismissVisual()
        isRevealing = false
        tracker.cancel()
        velocity?.recycle()
        velocity = null
        // If the child already received CANCEL, continue swallowing that stroke.
    }

    private fun settle(dismiss: Boolean) {
        val generation = ++animationGeneration
        animation?.cancel()
        val source = sourceBounds ?: return
        val start = visual
        if (!isRevealing) return
        val target = if (dismiss) findTarget() else null
        ViewerLoadMetrics.event("VIEWER_DISMISS_SETTLE",
            "dismiss=$dismiss target=${target?.bounds} source=$source")
        settling = true
        animation = scope.launch {
            try {
                animate(0f, 1f, animationSpec = tween(if (dismiss && target != null) 240 else 180)) { value, _ ->
                    visual = if (dismiss) dismissCloseVisual(source, start, target, value)
                        else dismissReturnVisual(start, value)
                }
                if (dismiss) {
                    // Present the final thumbnail-aligned frame before removing the viewer.
                    withFrameNanos { }
                    onDismiss()
                } else {
                    visual = ViewerDismissVisual()
                    isRevealing = false
                }
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
            downX = event.x
            val eligible = !blockedStroke && enabled() && viewportHeight > 0f &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && inMedia(event.x, event.y)
            sourceBounds = if (eligible) imageEligibility.values.firstNotNullOfOrNull { it() }
                ?.takeIf { it.width > 0 && it.height > 0 } else null
            tracker.begin(event.x, event.y, sourceBounds != null)
            if (sourceBounds != null) velocity = VelocityTracker.obtain()
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
                    onReveal()
                    isRevealing = true
                }
                visual = dismissDragVisual(sourceBounds!!, event.x - downX,
                    tracker.offset.coerceIn(-viewportHeight, viewportHeight), viewportHeight, density)
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
internal fun rememberViewerDismissGesture(key: ViewerTransitionKey?, isForeground: Boolean, enabled: Boolean,
    onDismiss: () -> Unit): ViewerDismissGesture {
    val context = LocalContext.current
    val host = context as? Activity
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val registry = LocalViewerTransitions.current
    val currentEnabled by rememberUpdatedState(enabled && isForeground)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val gesture = remember(key, context, registry) {
        val config = ViewConfiguration.get(context)
        ViewerDismissGesture(config.scaledTouchSlop.toFloat(), context.resources.displayMetrics.density,
            config.scaledMaximumFlingVelocity.toFloat(), scope).apply {
            this.enabled = { currentEnabled && host?.hasWindowFocus() == true && !host.isInPictureInPictureMode }
            this.onDismiss = { if (this.enabled()) currentDismiss() else reset() }
            this.onReveal = { registry?.beginReveal() }
            this.findTarget = { registry?.target(key, windowOrigin, viewportWidth, viewportHeight) }
        }
    }
    DisposableEffect(registry, gesture, isForeground) {
        if (isForeground) registry?.active = ViewerTransitionRegistry.Active(key, gesture)
        onDispose { if (registry?.active?.gesture === gesture) registry.active = null }
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

/** Transform only existing image pixels. The native image keeps its layout/decoder/tile geometry. */
internal fun Modifier.viewerDismissMotion(gesture: ViewerDismissGesture): Modifier =
    graphicsLayer {
        val frame = gesture.visual
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
        scaleX = frame.scale
        scaleY = frame.scale
        translationX = frame.translation.x
        translationY = frame.translation.y
        alpha = frame.imageAlpha
        compositingStrategy = CompositingStrategy.ModulateAlpha
        clip = frame.clip != null
        shape = frame.clip?.let { DismissImageClip(it, frame.cornerRadius) }
            ?: androidx.compose.ui.graphics.RectangleShape
    }

private class DismissImageClip(val rect: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        if (radius <= 0f) Outline.Rectangle(rect) else Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}
