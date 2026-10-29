package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.*
import org.junit.Test

class QuickSortPersistenceTest {
    private val entry = MediaEntry(7, "content://media/7", "/source/photo.jpg", "image/jpeg", 10, 10, 0, 42, 1, 1000)
    private val target = TransferDestination("target", "Target", "/target")
    private val checkpoint = QuickMoveCheckpoint(entry, "/target/photo.jpg", null, 1000, 42, "sha")
    private val receipt = TransferReceipt(7, entry.uri, entry.path, 8, "content://media/8", "/target/photo.jpg", 1000, null, true, true, true, true)
    private fun job(item: QuickSortQueueItem) = QuickSortJob("move", target, listOf(item), 0, QuickSortJobStatus.Completed)

    @Test fun `finished ordinary failures are not saved as tasks`() {
        for (stage in listOf(null, QuickMoveStage.Planned, QuickMoveStage.TargetReady, QuickMoveStage.Completed)) {
            val failed = job(QuickSortQueueItem(entry, QuickSortItemStatus.Failed,
                checkpoint = stage?.let { checkpoint.copy(stage = it) }, error = "failed"))
            assertTrue(quickSortJobsToSave(listOf(failed)).isEmpty())
        }
    }

    @Test fun `unfinished moves remain recoverable`() {
        val running = job(QuickSortQueueItem(entry, QuickSortItemStatus.Running, checkpoint)).copy(status = QuickSortJobStatus.Running)
        assertEquals(listOf(running), quickSortJobsToSave(listOf(running)))
    }

    @Test fun `committed file with incomplete bookkeeping is retained`() {
        val interrupted = job(QuickSortQueueItem(entry, QuickSortItemStatus.Failed,
            checkpoint.copy(stage = QuickMoveStage.SourceRemoved)))
        assertEquals(listOf(interrupted), quickSortJobsToSave(listOf(interrupted)))
    }

    @Test fun `undo is saved only during its originating session`() {
        val success = job(QuickSortQueueItem(entry, QuickSortItemStatus.Succeeded,
            checkpoint.copy(stage = QuickMoveStage.Completed), receipt)).copy(sessionId = "current")
        assertEquals(listOf(success), quickSortJobsToSave(listOf(success)))
        assertTrue(quickSortJobsToSave(listOf(success.copy(sessionClosed = true))).isEmpty())
        assertTrue(quickSortJobsToSave(listOf(success.copy(undoRequested = true, undoJobId = "reverse"))).isEmpty())
    }

    @Test fun `displaced original is protected until its backup is released`() {
        val backup = job(QuickSortQueueItem(entry, QuickSortItemStatus.Failed,
            replacementCheckpoint = checkpoint.copy(stage = QuickMoveStage.SourceRemoved)))
        assertEquals(listOf(backup), quickSortJobsToSave(listOf(backup)))
        val released = backup.copy(items = listOf(backup.items.single().copy(replacementReleased = true)))
        assertTrue(quickSortJobsToSave(listOf(released)).isEmpty())
        val untouched = backup.copy(items = listOf(backup.items.single().copy(replacementCheckpoint = checkpoint)))
        assertTrue(quickSortJobsToSave(listOf(untouched)).isEmpty())
    }

    @Test fun `pending undo is kept until reverse moves have been scheduled`() {
        val pending = job(QuickSortQueueItem(entry, QuickSortItemStatus.Cancelled)).copy(undoRequested = true)
        assertEquals(listOf(pending), quickSortJobsToSave(listOf(pending)))
    }
}
