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

    @Test fun `source album grid detects a same id move by source path`() {
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

    @Test fun `a thousand individual commits keep identical thumbnails and pending covers`() {
        val batchSlots = listOf(header) + (1L..1000L).map { photo(it) }
        val heldPaths = batchSlots.filterIsInstance<GridItem.Photo>().mapTo(mutableSetOf()) { it.entry.path }
        val pendingIds = (1L..1000L).toSet()
        val before = quickSortGridPresentation(batchSlots, batchSlots, heldPaths = heldPaths)
        var live = batchSlots
        for (id in 1L..1000L) {
            live = live.filterNot { (it as? GridItem.Photo)?.entry?.contentId == id }
            val result = quickSortGridPresentation(batchSlots, live, heldPaths = heldPaths)
            assertEquals(before, result)
            assertSame(batchSlots[1], result.items[1])
            assertEquals(pendingIds, result.pendingIds)
            assertTrue(result.unavailableIds.isEmpty())
        }
        assertTrue(quickSortGridPresentation(batchSlots, live, removedPaths = heldPaths).items.isEmpty())
    }

    @Test fun `finished batch waiting for a drag keeps covers then restores failed originals`() {
        val heldPaths = setOf("/source/1.jpg", "/source/2.jpg")
        val live = slots.filterNot { (it as? GridItem.Photo)?.entry?.contentId == 1L }
        val deferred = quickSortGridPresentation(slots, live, heldPaths = heldPaths)
        assertEquals(slots, deferred.items)
        assertEquals(setOf(1L, 2L), deferred.pendingIds)
        assertTrue(deferred.unavailableIds.isEmpty())
        val finished = quickSortGridPresentation(slots, live, removedPaths = setOf("/source/1.jpg"))
        assertEquals(listOf(header, photo(2), photo(3), photo(4)), finished.items)
        assertTrue(finished.pendingIds.isEmpty())
        assertTrue(finished.unavailableIds.isEmpty())
    }

    @Test fun `a same id moved to another path cannot replace a pending thumbnail`() {
        val result = quickSortGridPresentation(slots,
            listOf(header, photo(1, "/target/1.jpg"), photo(2), photo(3), photo(4)),
            heldPaths = setOf("/source/1.jpg"))
        assertSame(slots[1], result.items[1])
        assertEquals(setOf(1L), result.pendingIds)
        assertTrue(result.unavailableIds.isEmpty())
    }

    @Test fun `photos overview retains completed moves with stable keys and new selectable identities`() {
        val moved = photo(91, "/target/1.jpg")
        val mapping = mapOf((1L to "/source/1.jpg") to moved.entry)
        val result = quickSortGridPresentation(slots, listOf(header, moved, photo(2), photo(3), photo(4)),
            removedPaths = setOf("/source/1.jpg"), retainMovedItems = true, relocatedEntries = mapping)
        assertEquals(listOf(header, moved, photo(2), photo(3), photo(4)), result.items)
        assertEquals(quickSortGridPresentation(slots, slots).keys, result.keys)
        assertTrue(result.unavailableIds.isEmpty())
        assertTrue(result.pendingIds.isEmpty())
    }

    @Test fun `photos overview bridges queue completion before library refresh without a disappearing frame`() {
        val moved = photo(1, "/target/1.jpg")
        val result = quickSortGridPresentation(slots, slots, removedPaths = setOf("/source/1.jpg"),
            retainMovedItems = true, relocatedEntries = mapOf((1L to "/source/1.jpg") to moved.entry))
        assertEquals(moved, result.items[1])
        assertEquals(slots.size, result.items.size)
        assertTrue(result.unavailableIds.isEmpty())
    }

    @Test fun `a moved overview photo is covered for its next batch and stays in its original slot`() {
        val moved = photo(91, "/target/1.jpg")
        val result = quickSortGridPresentation(slots, listOf(moved),
            heldPaths = setOf(moved.entry.path), retainMovedItems = true,
            relocatedEntries = mapOf((1L to "/source/1.jpg") to moved.entry))
        assertEquals(moved, result.items[1])
        assertEquals(setOf(91L), result.pendingIds)
        assertEquals("photo_/source/1.jpg", result.keys!![1])
    }

    @Test fun `source albums still remove the finished batch even with relocation metadata`() {
        val moved = photo(91, "/target/1.jpg")
        val result = quickSortGridPresentation(slots, slots.drop(2), removedPaths = setOf("/source/1.jpg"),
            relocatedEntries = mapOf((1L to "/source/1.jpg") to moved.entry))
        assertEquals(listOf(header, photo(2), photo(3), photo(4)), result.items)
    }

    @Test fun `replaced destination cannot make two overview slots point at the incoming file`() {
        val moved = photo(1, "/target/1.jpg")
        val oldDestination = photo(90, "/target/1.jpg")
        val original = listOf(header, photo(1), oldDestination)
        val result = quickSortGridPresentation(original, listOf(header, moved), retainMovedItems = true,
            relocatedEntries = mapOf((1L to "/source/1.jpg") to moved.entry))
        assertEquals(listOf(header, moved), result.items)
        assertTrue(result.unavailableIds.isEmpty())
    }

    @Test fun `stale destination row cannot replace the verified incoming file`() {
        val moved = photo(91, "/target/1.jpg")
        val stale = photo(90, "/target/1.jpg")
        val result = quickSortGridPresentation(slots, slots + stale, retainMovedItems = true,
            relocatedEntries = mapOf((1L to "/source/1.jpg") to moved.entry))
        assertEquals(moved, result.items[1])
    }
}
