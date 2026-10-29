package com.pixel.gallery.ui.quicksort

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import org.junit.Assert.*
import org.junit.Test

class QuickSortBatchVisibilityTest {
    private fun entry(id: Long) = MediaEntry(id, "content://media/$id", "/source/$id.jpg", "image/jpeg", 100, 100, 0, 10, 1, 1)
    private fun item(id: Long, status: QuickSortItemStatus = QuickSortItemStatus.Succeeded) = QuickSortQueueItem(
        entry(id), status, receipt = if (status == QuickSortItemStatus.Succeeded)
            TransferReceipt(id, "content://media/$id", "/source/$id.jpg", id + 100, "content://media/${id + 100}",
                "/target/$id.jpg", 1, null, true, true, true, true) else null,
    )
    private fun job(id: String, items: List<QuickSortQueueItem>, status: QuickSortJobStatus = QuickSortJobStatus.Completed) =
        QuickSortJob(id, TransferDestination("target", "Target", "/target"), items, 1, status, sessionId = "session")

    @Test fun `completed items stay until the entire batch is terminal`() {
        val tracker = QuickSortBatchVisibility()
        val running = job("a", listOf(item(1), item(2, QuickSortItemStatus.Running)), QuickSortJobStatus.Running)
        assertTrue(tracker.update(listOf(running), "session").isEmpty())
        assertEquals(setOf("/source/1.jpg", "/source/2.jpg"), tracker.update(listOf(running.copy(
            status = QuickSortJobStatus.Completed, items = listOf(item(1), item(2)))), "session"))
    }
    @Test fun `failure and cancellation retain their original slots`() {
        val result = QuickSortBatchVisibility().update(listOf(job("a", listOf(item(1), item(2, QuickSortItemStatus.Failed),
            item(3, QuickSortItemStatus.Cancelled)), QuickSortJobStatus.Cancelled)), "session")
        assertEquals(setOf("/source/1.jpg"), result)
    }
    @Test fun `undo restores only its verified successes when its batch finishes`() {
        val tracker = QuickSortBatchVisibility()
        val forward = job("a", listOf(item(1), item(2)))
        tracker.update(listOf(forward), "session")
        val reverseItem = item(1).let { it.copy(receipt = it.receipt!!.copy(sourcePath = "/target/1.jpg", targetPath = "/source/1.jpg")) }
        val undo = job("undo", listOf(reverseItem), QuickSortJobStatus.Running).copy(undoOfJobId = "a")
        assertEquals(2, tracker.update(listOf(forward, undo), "session").size)
        assertEquals(setOf("/source/2.jpg"), tracker.update(listOf(forward, undo.copy(status = QuickSortJobStatus.Completed)), "session"))
    }
    @Test fun `trimmed queue history never brings back completed slots or replays jobs`() {
        val tracker = QuickSortBatchVisibility()
        for (id in 1L..45L) tracker.update(listOf(job("$id", listOf(item(id)))), "session")
        assertEquals(45, tracker.update(listOf(job("45", listOf(item(45)))), "session").size)
        assertTrue(QuickSortBatchVisibility().update(listOf(job("old", listOf(item(1)))), "another-session").isEmpty())
    }
    @Test fun `batch removal drops empty headers and leaves failed files in order`() {
        val slots = listOf(GridItem.Header("A", 1), GridItem.Photo(entry(1)), GridItem.Header("B", 2),
            GridItem.Photo(entry(2)), GridItem.Photo(entry(3)))
        val result = quickSortGridPresentation(slots, slots, setOf("/source/1.jpg", "/source/2.jpg"))
        assertEquals(listOf(GridItem.Header("B", 2), GridItem.Photo(entry(3))), result.items)
    }

    @Test fun `overview relocation survives queue trimming subsequent moves and undo`() {
        val tracker = QuickSortBatchVisibility()
        val first = item(1)
        tracker.update(listOf(job("first", listOf(first))), "session")
        val moved = tracker.relocatedEntries.getValue(1L to "/source/1.jpg")
        assertEquals(101L, moved.contentId)
        assertEquals("/target/1.jpg", moved.path)
        val second = first.copy(entry = moved, receipt = first.receipt!!.copy(
            sourceId = 101, sourceUri = moved.uri, sourcePath = moved.path,
            targetId = 201, targetUri = "content://media/201", targetPath = "/next/1.jpg"))
        val running = job("second", listOf(second), QuickSortJobStatus.Running)
        tracker.update(listOf(running), "session")
        assertEquals(moved, tracker.relocatedEntries.getValue(1L to "/source/1.jpg"))
        tracker.update(listOf(running.copy(status = QuickSortJobStatus.Completed)), "session")
        val twiceMoved = tracker.relocatedEntries.getValue(1L to "/source/1.jpg")
        assertEquals(201L, twiceMoved.contentId)
        assertEquals("/next/1.jpg", twiceMoved.path)
        assertEquals(twiceMoved, tracker.relocatedEntries[101L to "/target/1.jpg"])
        assertNull(tracker.relocatedEntries[90L to "/target/1.jpg"])
        val reverse = second.copy(entry = twiceMoved, receipt = second.receipt!!.copy(
            sourceId = 201, sourceUri = twiceMoved.uri, sourcePath = twiceMoved.path,
            targetId = 301, targetUri = "content://media/301", targetPath = "/target/1.jpg"))
        tracker.update(listOf(job("undo", listOf(reverse)).copy(undoOfJobId = "second")), "session")
        val restored = tracker.relocatedEntries.getValue(1L to "/source/1.jpg")
        assertEquals(301L, restored.contentId)
        assertEquals("/target/1.jpg", restored.path)
        val result = quickSortGridPresentation(listOf(GridItem.Photo(entry(1))), emptyList(),
            retainMovedItems = true, relocatedEntries = tracker.relocatedEntries)
        assertEquals(listOf(GridItem.Photo(restored)), result.items)
        assertTrue(result.unavailableIds.isEmpty())
    }

    @Test fun `failed cancelled and foreign-session moves do not remap overview photos`() {
        val tracker = QuickSortBatchVisibility()
        tracker.update(listOf(job("failed", listOf(item(1, QuickSortItemStatus.Failed),
            item(2, QuickSortItemStatus.Cancelled)))), "session")
        tracker.update(listOf(job("foreign", listOf(item(3)))), "other")
        assertTrue(tracker.relocatedEntries.isEmpty())
    }
}
