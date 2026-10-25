package com.pixel.gallery.ui.quicksort

import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem

internal data class QuickSortGridPresentation(
    val items: List<GridItem>,
    val unavailableIds: Set<Long>,
)

/** Keep session slots fixed; undo may restore the same path with a new MediaStore ID. */
internal fun quickSortGridPresentation(slots: List<GridItem>, latest: List<GridItem>, removedPaths: Set<String> = emptySet()): QuickSortGridPresentation {
    val byPath = latest.filterIsInstance<GridItem.Photo>().associateBy { it.entry.path }
    val unavailable = mutableSetOf<Long>()
    val displayed = buildList {
        var header: GridItem.Header? = null
        for (slot in slots) {
            if (slot is GridItem.Header) {
                header = slot
            } else if (slot is GridItem.Photo && slot.entry.path !in removedPaths) {
                header?.let { add(it); header = null }
                add(byPath[slot.entry.path] ?: slot.also { unavailable += it.entry.contentId })
            }
        }
    }
    return QuickSortGridPresentation(displayed, unavailable)
}
