package com.pixel.gallery.ui.quicksort

import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import com.pixel.gallery.data.local.entity.MediaEntry

internal data class QuickSortGridPresentation(
    val items: List<GridItem>,
    val unavailableIds: Set<Long>,
    val pendingIds: Set<Long> = emptySet(),
    val keys: List<String>? = null,
)

private fun sessionItemKey(item: GridItem): String = when (item) {
    is GridItem.Header -> "header_${item.title}_${item.timestamp}"
    is GridItem.Photo -> "photo_${item.entry.path}"
}

/** Keep session slots fixed; undo may restore the same path with a new MediaStore ID. */
internal fun quickSortGridPresentation(
    slots: List<GridItem>,
    latest: List<GridItem>,
    removedPaths: Set<String> = emptySet(),
    heldPaths: Set<String> = emptySet(),
    retainMovedItems: Boolean = false,
    relocatedEntries: Map<Pair<Long, String>, MediaEntry> = emptyMap(),
): QuickSortGridPresentation {
    val byPath = latest.filterIsInstance<GridItem.Photo>().associateBy { it.entry.path }
    val unavailable = mutableSetOf<Long>()
    val pending = mutableSetOf<Long>()
    val keys = mutableListOf<String>()
    val displayed = buildList {
        var header: GridItem.Header? = null
        for (slot in slots) {
            if (slot is GridItem.Header) {
                header = slot
            } else if (slot is GridItem.Photo && (retainMovedItems || slot.entry.path !in removedPaths)) {
                val relocated = if (retainMovedItems) relocatedEntries[slot.entry.contentId to slot.entry.path] else null
                val current = relocated?.let { GridItem.Photo(it) } ?: slot
                val atPath = byPath[current.entry.path]
                val live = atPath?.takeIf { !retainMovedItems || it.entry.contentId == current.entry.contentId }
                // An occupied path can now belong to a different file after replacement.
                if (retainMovedItems && relocated == null && atPath != null && live == null &&
                    current.entry.path !in heldPaths && slot.entry.path !in heldPaths) continue
                header?.let { add(it); keys += sessionItemKey(it); header = null }
                keys += sessionItemKey(slot)
                add(if (current.entry.path in heldPaths || slot.entry.path in heldPaths) {
                    current.also { pending += it.entry.contentId }
                } else live ?: if (relocated != null) current
                    else slot.also { unavailable += it.entry.contentId })
            }
        }
    }
    return QuickSortGridPresentation(displayed, unavailable, pending, keys)
}
