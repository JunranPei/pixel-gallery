package com.pixel.gallery.ui.quicksort

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.pixel.gallery.ui.utils.photoGridItemIndexAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Only installed in quick-sort mode. A first long press on an unselected item
 * still selects a range. A later long press on an already-selected item lifts
 * an immutable snapshot; only a pointer-up inside an actual target can submit it.
 */
@Composable
internal fun Modifier.quickSortPhotoGestures(
    gridState: LazyGridState,
    idsByIndex: List<Long?>,
    selectedIds: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    config: QuickSortUiConfig,
    gestures: QuickSortGestureState,
    gridOriginInWindow: Offset,
    scrollbarDragging: Boolean,
    leftContentPaddingPx: Float,
): Modifier {
    val currentIds by rememberUpdatedState(idsByIndex)
    val currentSelected by rememberUpdatedState(selectedIds)
    val currentSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentConfig by rememberUpdatedState(config)
    val currentOrigin by rememberUpdatedState(gridOriginInWindow)
    val currentScrollbar by rememberUpdatedState(scrollbarDragging)
    val currentLeftPadding by rememberUpdatedState(leftContentPaddingPx)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { 64.dp.toPx() }
    val maximumScrollPx = with(LocalDensity.current) { 15.dp.toPx() }
    var scrollSpeed by remember { mutableFloatStateOf(0f) }
    var lastPosition by remember { mutableStateOf(Offset.Unspecified) }
    var rangeStart by remember { mutableIntStateOf(-1) }
    var rangeInitial by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var gestureIds by remember { mutableStateOf<List<Long?>>(emptyList()) }

    fun updateRange(point: Offset) {
        if (rangeStart < 0 || currentIds != gestureIds) return
        gridState.photoGridItemIndexAt(point, currentLeftPadding)?.let { end ->
            currentSelectionChange(
                quickSortRangeSelection(rangeInitial, gestureIds, rangeStart, end, currentConfig.reservedIds),
            )
        }
    }

    LaunchedEffect(scrollSpeed) {
        while (scrollSpeed != 0f && isActive) {
            gridState.scrollBy(scrollSpeed)
            if (gestures.interaction == QuickSortInteraction.Selecting && lastPosition != Offset.Unspecified) {
                updateRange(lastPosition)
            }
            delay(16)
        }
    }

    DisposableEffect(gestures) {
        onDispose { gestures.clearPhotoGesture() }
    }

    return pointerInput(gestures) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.isConsumed || gestures.busy || currentScrollbar) return@awaitEachGesture
            val startIndex = gridState.photoGridItemIndexAt(down.position, currentLeftPadding)
            if (startIndex == null) {
                // A header is also a grid item. Only actual gaps/padding may
                // exit, and a photo-origin gesture never enters this branch.
                val startOrigin = currentOrigin
                val startPadding = currentLeftPadding
                val startScrollIndex = gridState.firstVisibleItemIndex
                val startScrollOffset = gridState.firstVisibleItemScrollOffset
                val startViewportOffset = gridState.layoutInfo.viewportStartOffset
                val layout = gridState.layoutInfo
                if (gridState.isScrollInProgress || layout.orientation != Orientation.Vertical || layout.reverseLayout ||
                    gestures.dockBounds?.contains(startOrigin + down.position) == true
                ) return@awaitEachGesture

                val blankTap = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    // Observe Final, including this down, so child clicks and
                    // parent scrolling/pinching get first refusal. Never consume.
                    var event = awaitPointerEvent(PointerEventPass.Final)
                    while (true) {
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer == null || event.changes.any { it.id != down.id || it.isConsumed } ||
                            (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                            pointer.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis ||
                            currentScrollbar || gestures.busy || gridState.isScrollInProgress ||
                            currentOrigin != startOrigin || currentLeftPadding != startPadding ||
                            gridState.firstVisibleItemIndex != startScrollIndex ||
                            gridState.firstVisibleItemScrollOffset != startScrollOffset ||
                            gridState.layoutInfo.viewportStartOffset != startViewportOffset ||
                            pointer.position.x < 0f || pointer.position.x >= size.width ||
                            pointer.position.y < 0f || pointer.position.y >= size.height ||
                            gestures.dockBounds?.contains(currentOrigin + pointer.position) == true ||
                            gridState.photoGridItemIndexAt(pointer.position, currentLeftPadding) != null
                        ) return@withTimeoutOrNull false
                        if (!pointer.pressed) return@withTimeoutOrNull pointer.previousPressed
                        event = awaitPointerEvent(PointerEventPass.Final)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                if (blankTap == true) currentConfig.onExit()
                return@awaitEachGesture
            }
            val startId = currentIds.getOrNull(startIndex) ?: return@awaitEachGesture
            if (startId in currentConfig.reservedIds) return@awaitEachGesture

            var last = down.position
            val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.count { it.pressed } > 1 || pointer == null ||
                        !pointer.pressed || pointer.isConsumed ||
                        (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                        currentScrollbar || gestures.busy
                    ) return@withTimeoutOrNull true
                    last = pointer.position
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (cancelled == true || currentIds.getOrNull(startIndex) != startId ||
                startId in currentConfig.reservedIds || currentScrollbar || gestures.busy
            ) return@awaitEachGesture
            // The dock/queue can change the content padding while the long press
            // is pending. Never lift a file that has moved away from the finger.
            val indexAfterLongPress = gridState.photoGridItemIndexAt(last, currentLeftPadding)
            if (indexAfterLongPress == null || currentIds.getOrNull(indexAfterLongPress) != startId) {
                return@awaitEachGesture
            }

            val lifting = startId in currentSelected
            gestureIds = currentIds.toList()
            rangeInitial = currentSelected.toSet()
            rangeStart = if (lifting) -1 else startIndex
            gestures.interaction = if (lifting) QuickSortInteraction.Moving else QuickSortInteraction.Selecting
            if (lifting) {
                gestures.draggedIds = frozenQuickSortSelection(
                    currentSelected, gestureIds.filterNotNull().toSet(), currentConfig.reservedIds,
                )
                if (gestures.draggedIds.isEmpty()) {
                    gestures.clearPhotoGesture()
                    return@awaitEachGesture
                }
            } else {
                updateRange(last)
            }
            gestures.pointerInWindow = currentOrigin + last
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch { gridState.stopScroll() }

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    // A second pointer cancels the whole gesture; do not turn a
                    // pending move into a pinch, scrollbar drag or other move.
                    if (event.changes.count { it.pressed } > 1 || pointer == null ||
                        pointer.isConsumed || currentScrollbar
                    ) {
                        event.changes.forEach { it.consume() }
                        scrollSpeed = 0f
                        while (currentEvent.changes.any { it.pressed }) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                        break
                    }
                    pointer.consume()
                    lastPosition = pointer.position
                    val pointInWindow = currentOrigin + pointer.position
                    gestures.pointerInWindow = pointInWindow
                    val overDock = gestures.dockBounds?.contains(pointInWindow) == true
                    gestures.hoveredTargetKey = if (lifting) gestures.targetAt(pointInWindow) else null
                    scrollSpeed = if (overDock || !pointer.pressed) 0f else when {
                        pointer.position.y < edgePx -> -maximumScrollPx *
                            ((edgePx - pointer.position.y) / edgePx).coerceIn(0f, 1f)
                        pointer.position.y > size.height - edgePx -> maximumScrollPx *
                            ((pointer.position.y - size.height + edgePx) / edgePx).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (!pointer.pressed) {
                        if (lifting) {
                            val target = currentConfig.targets.firstOrNull {
                                it.stableKey == gestures.hoveredTargetKey
                            }
                            val ids = frozenQuickSortSelection(
                                gestures.draggedIds, currentIds.filterNotNull().toSet(), currentConfig.reservedIds,
                            )
                            if (target != null && ids.isNotEmpty()) currentConfig.onMove(ids, target)
                        }
                        break
                    }
                    if (!lifting) updateRange(pointer.position)
                }
            } finally {
                scrollSpeed = 0f
                lastPosition = Offset.Unspecified
                rangeStart = -1
                gestures.clearPhotoGesture()
            }
        }
    }
}
