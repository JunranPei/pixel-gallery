package com.pixel.gallery.ui.quicksort

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Observes both dock and grid siblings before either receives a second pointer. */
internal fun Modifier.quickSortGestureArbitration(gestures: QuickSortGestureState): Modifier =
    pointerInput(gestures) {
        awaitPointerEventScope {
            var cancelUntilAllUp = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (gestures.busy && event.changes.count { it.pressed } > 1) cancelUntilAllUp = true
                if (cancelUntilAllUp) event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) cancelUntilAllUp = false
            }
        }
    }

/** One-pointer control drag. The click modifier remains responsible for short taps. */
@Composable
internal fun Modifier.quickSortControlDrag(
    gestureKey: Any,
    longPress: Boolean,
    onStart: (Offset) -> Boolean,
    onMove: (Offset) -> Unit,
    onFinish: (cancelled: Boolean) -> Unit,
): Modifier {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val finish by rememberUpdatedState(onFinish)
    return pointerInput(gestureKey, longPress) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.isConsumed) return@awaitEachGesture
            var last = down.position
            var cancelled = false
            if (longPress) {
                cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (event.changes.count { it.pressed } > 1 || pointer == null ||
                            !pointer.pressed || pointer.isConsumed ||
                            (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) return@withTimeoutOrNull true
                        last = pointer.position
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                } == true
            } else {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.count { it.pressed } > 1 || pointer == null ||
                        !pointer.pressed || pointer.isConsumed
                    ) {
                        cancelled = true
                        break
                    }
                    last = pointer.position
                    if ((last - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
            }
            if (cancelled || !start(last)) return@awaitEachGesture
            var finished = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.count { it.pressed } > 1 || pointer == null || pointer.isConsumed) {
                        event.changes.forEach { it.consume() }
                        finish(true)
                        finished = true
                        while (currentEvent.changes.any { it.pressed }) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                        break
                    }
                    pointer.consume()
                    move(pointer.position)
                    if (!pointer.pressed) {
                        finish(false)
                        finished = true
                        break
                    }
                }
            } finally {
                if (!finished) finish(true)
            }
        }
    }
}

/**
 * The compact dock observes only its gaps. Child capsules get Main-pass events
 * first; a consumed down or a down within their measured bounds is never claimed.
 * A gap press becomes a dock drag only after touch slop, or exits on a short tap.
 */
@Composable
internal fun Modifier.quickSortDockGapGestures(
    gestureKey: Any,
    acceptsDown: (Offset) -> Boolean,
    onTap: (Offset) -> Unit,
    onStart: (Offset) -> Boolean,
    onMove: (Offset) -> Unit,
    onFinish: (cancelled: Boolean) -> Unit,
): Modifier {
    val accepts by rememberUpdatedState(acceptsDown)
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val finish by rememberUpdatedState(onFinish)
    return pointerInput(gestureKey) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            if (down.isConsumed || !accepts(down.position)) return@awaitEachGesture
            var dragging = false
            var finished = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.count { it.pressed } > 1 || pointer == null || pointer.isConsumed) {
                        // A second pointer cannot turn a gap drag into a photo pinch.
                        event.changes.forEach { it.consume() }
                        if (dragging) {
                            finish(true)
                            finished = true
                        }
                        while (currentEvent.changes.any { it.pressed }) {
                            awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
                        }
                        break
                    }
                    if (!dragging) {
                        val passedSlop = (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop
                        if (!pointer.pressed) {
                            if (!passedSlop && pointer.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                                accepts(pointer.position)
                            ) {
                                pointer.consume()
                                tap(pointer.position)
                            }
                            break
                        }
                        if (!passedSlop) continue
                        if (!start(down.position)) break
                        dragging = true
                    }
                    pointer.consume()
                    move(pointer.position)
                    if (!pointer.pressed) {
                        finish(false)
                        finished = true
                        break
                    }
                }
            } finally {
                if (dragging && !finished) finish(true)
            }
        }
    }
}
