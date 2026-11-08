package com.pixel.gallery.ui.viewmodel

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.MediaTypeFilter
import java.lang.ref.WeakReference
import java.util.TimeZone

/** Shares immutable browse data between windows without retaining closed windows' libraries. */
internal class MediaBrowseIndexCache(private val capacity: Int = 4) {
    private class Slot(
        val monthly: Boolean,
        val sort: PhotoSortOrder,
        val zone: TimeZone,
        val index: WeakReference<MediaBrowseIndex>,
    )

    private val slots = ArrayList<Slot>()

    init { require(capacity > 0) }

    // Only one window builds a given generation at a time. Callers run on Default;
    // cancellation never publishes a partially built index to another window.
    @Synchronized
    fun getOrBuild(
        entries: List<MediaEntry>,
        columns: Int,
        sort: PhotoSortOrder,
        timeZone: TimeZone,
        checkCancelled: () -> Unit = {},
    ): MediaBrowseIndex {
        checkCancelled()
        if (entries.isEmpty()) return MediaBrowseIndex.Empty
        slots.removeAll { it.index.get() == null }
        val monthly = columns >= 6
        for (position in slots.indices.reversed()) {
            val slot = slots[position]
            if (slot.monthly != monthly || slot.sort != sort || slot.zone != timeZone) continue
            val index = slot.index.get() ?: continue
            val previous = index.library[MediaTypeFilter.ALL].media.photos
            if (!sameEntries(previous, entries, checkCancelled)) continue
            slots.removeAt(position)
            slots.add(slot)
            return index
        }
        val zone = timeZone.clone() as TimeZone
        val index = MediaBrowseIndex.build(entries, columns, sort, zone, checkCancelled)
        checkCancelled()
        if (slots.size == capacity) slots.removeAt(0)
        slots.add(Slot(monthly, sort, zone, WeakReference(index)))
        return index
    }

    private fun sameEntries(
        previous: List<MediaEntry>,
        current: List<MediaEntry>,
        checkCancelled: () -> Unit,
    ): Boolean {
        if (previous === current) return true
        if (previous.size != current.size) return false
        for (i in previous.indices) {
            if (i % 512 == 0) checkCancelled()
            if (previous[i] != current[i]) return false
        }
        return true
    }
}

internal object SharedMediaBrowseIndexes {
    val cache = MediaBrowseIndexCache()
}
