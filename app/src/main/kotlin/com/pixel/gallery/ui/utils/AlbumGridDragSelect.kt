package com.pixel.gallery.ui.utils

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
    selected: Set<K>,
    onToggle: (K) -> Unit,
    leftPaddingPx: Float,
    columns: Int,
    enabled: Boolean = true,
    keyForGridKey: (Any) -> K? = { key -> keys.firstOrNull { it == key } },
): Modifier {
    val selection by rememberUpdatedState(selected)
    val toggle by rememberUpdatedState(onToggle)
    val resolve by rememberUpdatedState(keyForGridKey)
    val haptics = LocalHapticFeedback.current
    return pointerInput(state, keys, columns, enabled, leftPaddingPx) {
        if (!enabled) return@pointerInput
        coroutineScope {
            var anchor: K? = null
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
                val next = albumRangeSelection(initial, keys, start, end, selecting)
                ((applied - next) + (next - applied)).forEach(toggle)
                applied = next
            }
            fun stop() {
                scrollJob?.cancel()
                scrollJob = null
                anchor = null
            }
            try {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        val key = hit(position)
                        if (key != null) {
                            anchor = key
                            initial = selection
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
                                    if (speed != 0f) {
                                        state.scrollBy(speed)
                                        update()
                                    }
                                    delay(16)
                                }
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        if (anchor != null) {
                            change.consume()
                            pointer = change.position
                            update()
                        }
                    },
                    onDragEnd = { stop() },
                    onDragCancel = { stop() },
                )
            } finally { stop() }
        }
    }
}
