package com.pixel.gallery.ui.quicksort

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import org.junit.Assert.*
import org.junit.Test

class QuickSortGridPresentationTest {
    private fun photo(id: Long, path: String = "/source/$id.jpg") = GridItem.Photo(
        MediaEntry(id, "content://media/$id", path, "image/jpeg", 200, 200, 0, 100, 1, 1)
    )
    private val header = GridItem.Header("Today", 1)
    private val slots = listOf(header, photo(1), photo(2), photo(3), photo(4))

    @Test fun `per item removal preserves all slot indices and ongoing range selection`() {
        var live = slots
        for (id in listOf(1L, 2L)) {
            live = live.filterNot { (it as? GridItem.Photo)?.entry?.contentId == id }
            val result = quickSortGridPresentation(slots, live)
            assertEquals(slots, result.items)
            val ids = result.items.map { (it as? GridItem.Photo)?.entry?.contentId }
            assertEquals(setOf(3L, 4L), quickSortRangeSelection(emptySet(), ids, 3, 4, result.unavailableIds))
        }
    }

    @Test fun `home grid detects a same id move by source path`() {
        val result = quickSortGridPresentation(slots, listOf(header, photo(1, "/target/1.jpg"), photo(2), photo(3), photo(4)))
        assertEquals(setOf(1L), result.unavailableIds)
        assertEquals(slots, result.items)
    }

    @Test fun `failed and cancelled moves leave originals selectable`() {
        val result = quickSortGridPresentation(slots, slots)
        assertTrue(result.unavailableIds.isEmpty())
        assertEquals(slots, result.items)
    }

    @Test fun `undo restores original slot even with a new id`() {
        val restored = photo(91, "/source/1.jpg")
        val result = quickSortGridPresentation(slots, listOf(photo(4), restored, photo(3), photo(2)))
        assertEquals(listOf(header, restored, photo(2), photo(3), photo(4)), result.items)
        assertTrue(result.unavailableIds.isEmpty())
    }

    @Test fun `new files and reordering do not move session slots`() {
        val result = quickSortGridPresentation(slots, listOf(photo(99)) + slots.reversed())
        assertEquals(slots, result.items)
    }

    @Test fun `all removed items retain placeholders until session ends`() {
        val result = quickSortGridPresentation(slots, emptyList())
        assertEquals(slots, result.items)
        assertEquals(setOf(1L, 2L, 3L, 4L), result.unavailableIds)
    }
}
