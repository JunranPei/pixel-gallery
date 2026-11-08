package com.pixel.gallery.ui.home

import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import java.util.RandomAccess

/** Reads IDs from the existing grid snapshot instead of boxing the whole library per window. */
internal class PhotoGridItemIds(private val items: List<GridItem>) : AbstractList<Long?>(), RandomAccess {
    override val size: Int get() = items.size
    override fun get(index: Int): Long? = (items[index] as? GridItem.Photo)?.entry?.contentId
}

/** A date header owns a range in the shared snapshot, not a second list of photo IDs. */
internal class PhotoGridHeaderRange(items: List<GridItem>, headerIndex: Int) {
    private val items = items
    private val start = headerIndex + 1
    private val end: Int

    init {
        require(headerIndex in items.indices && items[headerIndex] is GridItem.Header)
        var next = start
        while (next < items.size && items[next] !is GridItem.Header) next++
        end = next
    }

    fun isSelected(selectedIds: Set<Long>, reservedIds: Set<Long>): Boolean {
        if (selectedIds.isEmpty()) return false
        var hasSelectablePhoto = false
        for (index in start until end) {
            val id = (items[index] as GridItem.Photo).entry.contentId
            if (id in reservedIds) continue
            if (id !in selectedIds) return false
            hasSelectablePhoto = true
        }
        return hasSelectablePhoto
    }

    fun toggleSelection(selectedIds: Set<Long>, reservedIds: Set<Long>): Set<Long> {
        val deselect = isSelected(selectedIds, reservedIds)
        return selectedIds.toMutableSet().apply {
            for (index in start until end) {
                val id = (items[index] as GridItem.Photo).entry.contentId
                if (id !in reservedIds) {
                    if (deselect) remove(id) else add(id)
                }
            }
            removeAll(reservedIds)
        }
    }
}
