package com.pixel.gallery.ui.viewmodel

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.isInsideMediaFolder
import java.lang.ref.WeakReference

/** Immutable library generations can be filtered/sorted once for every window. */
internal class MediaPhotoListCache(private val capacity: Int = 4) {
    private class Slot(
        val source: WeakReference<List<MediaEntry>>,
        val hidden: Set<String>,
        val sort: PhotoSortOrder?,
        val hiddenOnly: Boolean,
        val result: WeakReference<List<MediaEntry>>,
    )

    private val slots = ArrayList<Slot>()

    init { require(capacity > 0) }

    @Synchronized
    fun get(
        source: List<MediaEntry>,
        hidden: Set<String>,
        sort: PhotoSortOrder? = null,
        hiddenOnly: Boolean = false,
    ): List<MediaEntry> {
        if (source.isEmpty() || hiddenOnly && hidden.isEmpty()) return emptyList()
        slots.removeAll { it.source.get() == null || it.result.get() == null }
        for (i in slots.indices.reversed()) {
            val slot = slots[i]
            if (slot.source.get() !== source || slot.hidden != hidden ||
                slot.sort != sort || slot.hiddenOnly != hiddenOnly) continue
            val result = slot.result.get() ?: continue
            slots.removeAt(i)
            slots.add(slot)
            return result
        }
        val visible = if (hidden.isEmpty()) source else source.filter { entry ->
            hidden.any { entry.path.isInsideMediaFolder(it) } == hiddenOnly
        }
        val result = if (sort == null) visible else sortMediaEntries(visible, sort)
        if (slots.size == capacity) slots.removeAt(0)
        slots.add(Slot(WeakReference(source), hidden.toSet(), sort, hiddenOnly, WeakReference(result)))
        return result
    }
}

internal object SharedMediaPhotoLists {
    val cache = MediaPhotoListCache()
}
