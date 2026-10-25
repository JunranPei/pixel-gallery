package com.pixel.gallery.ui.viewer

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import com.pixel.gallery.MainActivity
import kotlin.math.hypot

/**
 * Arbitrate before either Compose or AndroidView receives MOVE. Both renderers get
 * one CANCEL when rotation wins, so no image tiles or video transforms follow the
 * fingers. Only an all-fingers-up event can request a screen orientation change.
 */
class ViewerRotationGesture internal constructor(private val touchSlop: Float, minimumSpan: Float) {
    internal var enabled: () -> Boolean = { false }
    internal var onRotate: () -> Unit = {}
    internal var dismissGesture: ViewerDismissGesture? = null
    private val tracker = RotationGestureTracker(touchSlop, minimumSpan)
    private val areas = mutableMapOf<Any, Pair<LayoutCoordinates, Boolean>>()
    private var eligible = false
    private var hadPair = false
    private var pairEnded = false
    private var claimed = false
    private var firstId = -1
    private var secondId = -1
    private var downX = 0f
    private var downY = 0f

    internal fun area(key: Any, coordinates: LayoutCoordinates, excluded: Boolean) {
        areas[key] = coordinates to excluded
    }
    internal fun removeArea(key: Any) { areas.remove(key) }
    private fun inMedia(x: Float, y: Float): Boolean {
        val point = Offset(x, y)
        var inside = false
        for ((coordinates, excluded) in areas.values) {
            if (coordinates.isAttached && coordinates.boundsInWindow().contains(point)) {
                if (excluded) return false
                inside = true
            }
        }
        return inside
    }

    fun dispatch(event: MotionEvent, toContent: (MotionEvent) -> Boolean): Boolean {
        val dismiss = dismissGesture
        return if (dismiss != null) {
            dismiss.dispatch(event, { x, y -> enabled() && inMedia(x, y) }) { dispatchRotation(it, toContent) }
        } else dispatchRotation(event, toContent)
    }

    private fun dispatchRotation(event: MotionEvent, toContent: (MotionEvent) -> Boolean): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            tracker.cancel()
            claimed = false
            hadPair = false
            pairEnded = false
            firstId = event.getPointerId(0)
            downX = event.x
            downY = event.y
            eligible = enabled() && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && inMedia(event.x, event.y)
        }
        if (!enabled() || action == MotionEvent.ACTION_CANCEL) {
            eligible = false
            tracker.cancel()
        }
        if (eligible && !hadPair && action == MotionEvent.ACTION_MOVE &&
            hypot(event.x - downX, event.y - downY) > touchSlop) {
            // A drag that already belongs to the pager or a zoomed image keeps it.
            eligible = false
            tracker.cancel()
        }

        if (eligible && action == MotionEvent.ACTION_POINTER_DOWN) {
            if (hadPair || event.pointerCount != 2 ||
                (0 until event.pointerCount).any {
                    event.getToolType(it) != MotionEvent.TOOL_TYPE_FINGER || !inMedia(event.getX(it), event.getY(it))
                }) {
                eligible = false
                tracker.cancel()
            } else {
                hadPair = true
                secondId = event.getPointerId(event.actionIndex)
                val first = event.findPointerIndex(firstId)
                if (first >= 0) tracker.begin(event.pointer(first), event.pointer(event.actionIndex))
            }
        }
        if (eligible && hadPair && !pairEnded &&
            (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_POINTER_UP)) {
            val first = event.findPointerIndex(firstId)
            val second = event.findPointerIndex(secondId)
            if (first < 0 || second < 0 || event.pointerCount != 2) {
                tracker.cancel()
            } else tracker.move(event.pointer(first), event.pointer(second))
        }
        if (!claimed && tracker.intent == RotationGestureTracker.Intent.ROTATION) {
            claimed = true
            MotionEvent.obtain(event).also { cancel ->
                cancel.action = MotionEvent.ACTION_CANCEL
                try { toContent(cancel) } finally { cancel.recycle() }
            }
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            pairEnded = true
            tracker.releasePair()
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            val consume = claimed
            val rotate = eligible && action == MotionEvent.ACTION_UP && tracker.finish()
            tracker.cancel()
            eligible = false
            claimed = false
            val handled = if (consume) true else toContent(event)
            if (rotate) onRotate()
            return handled
        }
        if (claimed) return true
        // A few ambiguous MOVE events are held until rotation vs pinch/pan is clear.
        // The winner receives the full movement from the original pointer positions.
        if (eligible && hadPair && !pairEnded && action == MotionEvent.ACTION_MOVE &&
            tracker.intent == RotationGestureTracker.Intent.UNDECIDED) return true
        return toContent(event)
    }

    private fun MotionEvent.pointer(index: Int) = RotationPointer(getPointerId(index), getX(index), getY(index))
}

@Composable
internal fun rememberViewerRotationGesture(
    isForeground: Boolean,
    enabled: Boolean,
    onRotate: () -> Unit,
): ViewerRotationGesture {
    val context = LocalContext.current
    val host = context as? MainActivity
    val currentEnabled by rememberUpdatedState(enabled)
    val currentRotate by rememberUpdatedState(onRotate)
    val gesture = remember(context) {
        ViewerRotationGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            48f * context.resources.displayMetrics.density).apply {
            this.enabled = { currentEnabled && host?.hasWindowFocus() == true && !host.isInPictureInPictureMode && !host.isInMultiWindowMode }
            this.onRotate = { currentRotate() }
        }
    }
    DisposableEffect(host, isForeground, gesture) {
        if (isForeground) host?.viewerRotationGesture = gesture
        onDispose { if (host?.viewerRotationGesture === gesture) host.viewerRotationGesture = null }
    }
    return gesture
}

internal fun Modifier.viewerRotationArea(gesture: ViewerRotationGesture?, excluded: Boolean = false): Modifier =
    if (gesture == null) this else composed {
        val key = remember { Any() }
        DisposableEffect(gesture) { onDispose { gesture.removeArea(key) } }
        onGloballyPositioned { gesture.area(key, it, excluded) }
    }
