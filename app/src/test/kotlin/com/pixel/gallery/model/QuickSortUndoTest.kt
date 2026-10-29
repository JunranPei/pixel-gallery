package com.pixel.gallery.model

import com.google.gson.Gson
import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.*
import org.junit.Test

class QuickSortUndoTest {
    private val destination = TransferDestination("target", "Target", "/target")
    private fun moved(id: Long, sourcePath: String) = QuickSortQueueItem(
        MediaEntry(id, "content://media/$id", sourcePath, "image/jpeg", 100, 100, 0, 42, 10, 1000),
        status = QuickSortItemStatus.Succeeded,
        checkpoint = QuickMoveCheckpoint(
            MediaEntry(id, "content://media/$id", sourcePath, "image/jpeg", 100, 100, 0, 42, 10, 1000),
            "/target/${id}_renamed.jpg", null, 1000, 42, "original-hash", QuickMoveStage.Completed,
            "content://media/${id + 100}"),
        receipt = TransferReceipt(id, "content://media/$id", sourcePath, id + 100,
            "content://media/${id + 100}", "/target/${id}_renamed.jpg", 1000, null,
            true, true, true, true),
    )
    private fun job(id: String, session: String, items: List<QuickSortQueueItem> = listOf(moved(1, "/source/one.jpg"))) =
        QuickSortJob(id, destination, items, 1, QuickSortJobStatus.Completed, sessionId = session)

    @Test fun `undo does not reach a prior sort session or historical journals`() {
        val state = QuickSortQueueState(jobs = listOf(job("old", "previous"), job("new", "current")))
        assertEquals("new", latestUndoableQuickSortJob(state, "current")?.id)
        assertNull(latestUndoableQuickSortJob(state, "next"))
        assertNull(latestUndoableQuickSortJob(state, null))
        assertNull(latestUndoableQuickSortJob(QuickSortQueueState(jobs = listOf(job("legacy", "current").copy(sessionId = null))), "current"))
    }

    @Test fun `undo request cancels unstarted items but awaits committed running receipt`() {
        val running = moved(2, "/second/two.jpg").copy(status = QuickSortItemStatus.Running, receipt = null)
        val queued = moved(3, "/third/three.jpg").copy(status = QuickSortItemStatus.Queued, receipt = null)
        val original = job("batch", "current", listOf(moved(1, "/first/one.jpg"), running, queued))
            .copy(status = QuickSortJobStatus.Running)
        val cancelled = cancelQuickSortJob(original).copy(undoRequested = true)
        assertEquals(listOf(QuickSortItemStatus.Succeeded, QuickSortItemStatus.Running, QuickSortItemStatus.Cancelled), cancelled.items.map { it.status })
        assertNull(latestUndoableQuickSortJob(QuickSortQueueState(jobs = listOf(cancelled)), "current"))
        assertEquals(1, quickSortUndoItems(cancelled).size)
    }

    @Test fun `reverse items keep original names source albums hashes and newest IDs`() {
        val original = job("batch", "current", listOf(moved(1, "/first/same.jpg"), moved(2, "/second/same.jpg")))
        val reverse = quickSortUndoItems(original)
        assertEquals(listOf(102L, 101L), reverse.map { it.entry.contentId })
        assertEquals(listOf("/target/2_renamed.jpg", "/target/1_renamed.jpg"), reverse.map { it.entry.path })
        assertEquals(listOf("/second/same.jpg", "/first/same.jpg"), reverse.map { it.restoreCheckpoint!!.source.path })
        assertTrue(reverse.all { it.restoreCheckpoint!!.sourceSha256 == "original-hash" })
        assertTrue(reverse.all { it.checkpoint == null && it.status == QuickSortItemStatus.Queued })
        assertEquals(reverse, Gson().fromJson(Gson().toJson(original.copy(items = reverse)), QuickSortJob::class.java).items)
    }

    @Test fun `completed reversal cannot itself be undone and older session action remains eligible`() {
        val first = job("first", "current")
        val second = job("second", "current").copy(undoRequested = true, undoJobId = "reverse")
        val reverse = job("reverse", "current").copy(undoOfJobId = "second")
        assertEquals("first", latestUndoableQuickSortJob(QuickSortQueueState(jobs = listOf(first, second, reverse)), "current")?.id)
        assertNull(latestUndoableQuickSortJob(QuickSortQueueState(jobs = listOf(first, second,
            reverse.copy(status = QuickSortJobStatus.Running))), "current"))
    }

    @Test fun `replacement undo returns incoming file before restoring displaced destination`() {
        val incoming = moved(1, "/source/same.jpg")
        val displaced = moved(2, "/target/same.jpg")
        val original = job("replacement", "current", listOf(incoming.copy(
            replacementCheckpoint = displaced.checkpoint, replacementReceipt = displaced.receipt)))
        val reverse = quickSortUndoItems(original)
        assertEquals(listOf("/source/same.jpg", "/target/same.jpg"), reverse.map { it.restoreCheckpoint!!.source.path })
        assertNull(latestUndoableQuickSortJob(QuickSortQueueState(jobs = listOf(original.copy(sessionClosed = true))), "current"))
        assertEquals(1, quickSortUndoItems(original.copy(items = listOf(original.items.single().copy(replacementReleased = true)))).size)
    }
}
