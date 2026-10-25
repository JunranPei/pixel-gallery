package com.pixel.gallery.ui.quicksort

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixel.gallery.model.TransferDestination

/** Host-owned targets, preferences and transfer queue. The grid never performs file IO. */
data class QuickSortUiConfig(
    val targets: List<TransferDestination>,
    val dockTop: Boolean,
    val onDockTopChange: (Boolean) -> Unit,
    val onReorderTargets: (List<TransferDestination>) -> Unit,
    val onManageTargets: () -> Unit,
    val onMove: (Set<Long>, TransferDestination) -> Unit,
    val dockBottomInset: Dp = 0.dp,
    val reservedIds: Set<Long> = emptySet(),
    val onExit: () -> Unit = {},
    val handedness: com.pixel.gallery.model.Handedness = com.pixel.gallery.model.Handedness.RIGHT,
    val undoAvailable: Boolean = false,
    val onUndo: () -> Unit = {},
    val sessionKey: String? = null,
    val completedBatchPaths: Set<String> = emptySet(),
)

internal enum class QuickSortInteraction { Idle, Selecting, Moving, Dock, Reordering }

@Stable
internal class QuickSortGestureState {
    var interaction by mutableStateOf(QuickSortInteraction.Idle)
    var draggedIds by mutableStateOf<Set<Long>>(emptySet())
    var pointerInWindow by mutableStateOf(Offset.Unspecified)
    var hoveredTargetKey by mutableStateOf<String?>(null)
    var dockBounds by mutableStateOf<Rect?>(null)
    var containerBounds by mutableStateOf(Rect.Zero)
    var scrollbarDragging by mutableStateOf(false)
    val targetBounds = mutableStateMapOf<String, Rect>()

    val busy: Boolean get() = interaction != QuickSortInteraction.Idle

    fun targetAt(point: Offset): String? = quickSortDropTarget(point, targetBounds)

    fun clearPhotoGesture() {
        draggedIds = emptySet()
        pointerInWindow = Offset.Unspecified
        hoveredTargetKey = null
        if (interaction == QuickSortInteraction.Moving || interaction == QuickSortInteraction.Selecting) {
            interaction = QuickSortInteraction.Idle
        }
    }
}

internal fun frozenQuickSortSelection(
    selectedIds: Set<Long>,
    visibleIds: Set<Long>,
    reservedIds: Set<Long>,
): Set<Long> = selectedIds.intersect(visibleIds) - reservedIds

internal fun quickSortRangeSelection(
    initial: Set<Long>,
    idsByIndex: List<Long?>,
    start: Int,
    end: Int,
    reservedIds: Set<Long>,
): Set<Long> {
    if (start !in idsByIndex.indices || end !in idsByIndex.indices) return initial - reservedIds
    return (initial + idsByIndex.subList(minOf(start, end), maxOf(start, end) + 1).filterNotNull()) - reservedIds
}

internal fun <T> moveQuickSortTarget(values: List<T>, from: Int, to: Int): List<T> {
    if (from !in values.indices || to !in values.indices || from == to) return values
    return values.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun quickSortDockAtTop(pointerY: Float, containerTop: Float, containerHeight: Float): Boolean =
    pointerY < containerTop + containerHeight / 2f

internal fun quickSortDropTarget(point: Offset, bounds: Map<String, Rect>): String? =
    bounds.entries.firstOrNull { (_, rect) -> rect.contains(point) }?.key

/** Shortest distinguishing parent-path suffix for names repeated across storage roots. */
internal fun quickSortPathSuffix(target: TransferDestination, targets: List<TransferDestination>): String {
    fun parentParts(value: TransferDestination) = value.path.trimEnd('/').substringBeforeLast('/')
        .split('/').filter(String::isNotEmpty)
    val parts = parentParts(target)
    val others = targets.filter { it.stableKey != target.stableKey && it.displayName == target.displayName }
    for (count in 1..parts.size) {
        val suffix = parts.takeLast(count).joinToString("/")
        if (others.none { parentParts(it).takeLast(count).joinToString("/") == suffix }) return suffix
    }
    return parts.joinToString("/").ifEmpty { target.path }
}
