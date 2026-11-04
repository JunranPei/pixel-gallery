package com.pixel.gallery.ui.utils

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/** Range selection uses stable item keys, so headers never shift album identity. */
internal fun <K : Any> albumRangeSelection(
    initial: Set<K>, keys: List<K>, anchor: K, end: K, selecting: Boolean,
): Set<K> {
    val a = keys.indexOf(anchor)
    val b = keys.indexOf(end)
    if (a < 0 || b < 0) return initial
    val range = keys.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
    return if (selecting) initial + range else initial - range
}

@Composable
internal fun <K : Any> Modifier.albumGridDragSelect(
    state: LazyGridState,
    keys: List<K>,
    rangeKeys: List<K> = keys,
    selected: Set<K>,
    onToggle: (K) -> Unit,
    leftPaddingPx: Float,
    columns: Int,
    enabled: Boolean = true,
    canReorder: (K) -> Boolean = { false },
    reorderOnLongPress: (K) -> Boolean = { false },
    reorderTarget: (K, K) -> K? = { _, target -> target },
    onReorder: (K, K) -> Unit = { _, _ -> },
    collapseGroupForKey: (K) -> String? = { null },
    onSwipeCollapse: (String) -> Unit = {},
    keyForGridKey: (Any) -> K? = { key -> keys.firstOrNull { it == key } },
): Modifier {
    val reorderAllowed by rememberUpdatedState(canReorder)
    val reorder by rememberUpdatedState(onReorder)
    val resolveReorderTarget by rememberUpdatedState(reorderTarget)
    val longPressReorder by rememberUpdatedState(reorderOnLongPress)
    val currentKeys by rememberUpdatedState(rangeKeys)
    val selection by rememberUpdatedState(selected)
    val toggle by rememberUpdatedState(onToggle)
    val resolve by rememberUpdatedState(keyForGridKey)
    val collapseGroup by rememberUpdatedState(collapseGroupForKey)
    val collapse by rememberUpdatedState(onSwipeCollapse)
    val haptics = LocalHapticFeedback.current
    return pointerInput(state, columns, enabled, leftPaddingPx) {
        if (!enabled) return@pointerInput
        coroutineScope {
            var anchor: K? = null
            var moving = false
            var lastTarget: K? = null
            var initial = emptySet<K>()
            var applied = emptySet<K>()
            var selecting = true
            var pointer = Offset.Zero
            var scrollJob: Job? = null
            fun hit(position: Offset): K? {
                val index = state.photoGridItemIndexAt(position, leftPaddingPx) ?: return null
                return state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key?.let(resolve)
            }
            fun update() {
                val start = anchor ?: return
                val end = hit(pointer) ?: return
                if (moving) {
                    val target = resolveReorderTarget(start, end) ?: return
                    if (target != start && target != lastTarget) { reorder(start, target); lastTarget = target }
                    return
                }
                val next = if (start !in currentKeys) { if (selecting) initial + start else initial - start }
                    else albumRangeSelection(initial, currentKeys, start, end, selecting)
                ((applied - next) + (next - applied)).forEach(toggle)
                applied = next
            }
            fun stop() {
                scrollJob?.cancel()
                scrollJob = null
                anchor = null
            }
            fun begin(key: K, position: Offset) {
                anchor = key
                initial = selection
                moving = reorderAllowed(key) && (key in initial || longPressReorder(key))
                if (moving && key !in initial) toggle(key)
                lastTarget = null
                applied = initial
                selecting = key !in initial
                pointer = position
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                update()
                scrollJob = launch {
                    val edge = 64.dp.toPx()
                    while (true) {
                        val speed = when {
                            pointer.y < edge -> -((edge - pointer.y) / edge).coerceIn(0f, 1f)
                            pointer.y > size.height - edge -> ((pointer.y - size.height + edge) / edge).coerceIn(0f, 1f)
                            else -> 0f
                        } * 16.dp.toPx()
                        if (speed != 0f) { state.scrollBy(speed); update() }
                        delay(16)
                    }
                }
            }
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val key = hit(down.position) ?: return@awaitEachGesture
                    var claimed = false
                    var swiping = false
                    var swipeCancelled = false
                    val directReorder = key in selection && reorderAllowed(key)
                    val startGroup = if (directReorder) null else collapseGroup(key)
                    var crossedMember = false
                    var lastMember = key
                    val hold = launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        claimed = true
                        begin(key, down.position)
                    }
                    try {
                        do {
                            // Claim release before the card can interpret it as a second click.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (event.changes.count { it.pressed } > 1) {
                                swipeCancelled = true
                                hold.cancel()
                                stop()
                            }
                            if (!claimed && directReorder && change != null && change.pressed &&
                                !change.isConsumed && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                hold.cancel()
                                claimed = true
                                begin(key, down.position)
                            }
                            if (!claimed && change != null) {
                                val delta = change.position - down.position
                                val horizontal = -delta.x > viewConfiguration.touchSlop &&
                                    -delta.x > kotlin.math.abs(delta.y) * 1.5f
                                if (!swiping && !swipeCancelled && startGroup != null && horizontal && !change.isConsumed) {
                                    swiping = true
                                    hold.cancel()
                                } else if (!swiping && delta.getDistance() > viewConfiguration.touchSlop && !horizontal) {
                                    swipeCancelled = true
                                }
                                if (swiping) {
                                    change.consume()
                                    val member = hit(change.position)
                                    if (member != null) {
                                        if (collapseGroup(member) != startGroup) swipeCancelled = true
                                        if (member != key) crossedMember = true
                                        lastMember = member
                                    } else if (state.photoGridItemIndexAt(change.position, leftPaddingPx) != null) {
                                        swipeCancelled = true
                                    }
                                    if (kotlin.math.abs(delta.y) > maxOf(32.dp.toPx(), -delta.x * .6f)) swipeCancelled = true
                                    if (!change.pressed && !swipeCancelled && crossedMember && lastMember != key &&
                                        -delta.x >= 48.dp.toPx() && collapseGroup(lastMember) == startGroup) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        collapse(startGroup!!)
                                    }
                                }
                            }
                            if (claimed) {
                                event.changes.forEach { it.consume() }
                                if (change != null && change.pressed && anchor != null) {
                                    pointer = change.position
                                    update()
                                }
                            } else if (change == null || !change.pressed || change.isConsumed ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                hold.cancel()
                            }
                        } while (event.changes.any { it.pressed })
                    } finally { hold.cancel(); stop() }
                }
            } finally { stop() }
        }
    }
}
