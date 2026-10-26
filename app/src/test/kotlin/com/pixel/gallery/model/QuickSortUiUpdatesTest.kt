package com.pixel.gallery.model

import com.google.gson.Gson
import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.*
import org.junit.Test

class QuickSortUiUpdatesTest {
    private val entry = MediaEntry(1, "content://media/1", "/source/1.jpg", "image/jpeg", 100, 100, 0, 10, 1, 1)
    private val checkpoint = QuickMoveCheckpoint(entry, "/target/1.jpg", null, 1, 10, "sha256")
    private val job = QuickSortJob("batch", TransferDestination("target", "Target", "/target"),
        listOf(QuickSortQueueItem(entry, QuickSortItemStatus.Running, checkpoint)), 1, status = QuickSortJobStatus.Running)
    private fun state(job: QuickSortJob) = QuickSortQueueState(jobs = listOf(job), loaded = true)

    @Test fun `per file checkpoints and status do not rebuild the gallery but batch changes do`() {
        val before = state(job)
        val checkpointOnly = state(job.copy(items = job.items.map {
            it.copy(checkpoint = checkpoint.copy(stage = QuickMoveStage.SourceRemoved))
        }))
        assertTrue(sameQuickSortUiState(before, checkpointOnly))
        assertTrue(sameQuickSortUiState(before, state(job.copy(items = job.items.map { it.copy(status = QuickSortItemStatus.Succeeded) }))))
        assertFalse(sameQuickSortUiState(before, state(job.copy(status = QuickSortJobStatus.Completed))))
        assertFalse(sameQuickSortUiState(before, before.copy(error = "Disk error")))
        assertFalse(sameQuickSortUiState(before, before.copy(permissionRevision = 1)))
    }

    @Test fun `derived caches belong to their immutable snapshot and survive Gson reload`() {
        val before = state(job).prepareForUi()
        assertEquals(setOf(1L), before.reservedIds)
        assertSame(before.reservedIds, before.reservedIds)
        assertSame(before.heldItems, before.heldItems)
        assertEquals(setOf(1L), before.heldItems.ids)
        assertEquals(setOf(entry.path), before.heldItems.paths)
        val completed = job.copy(items = job.items.map { it.copy(status = QuickSortItemStatus.Succeeded) })
        assertEquals(0, job.completed)
        assertEquals(1, completed.completed)
        assertTrue(state(completed).reservedIds.isEmpty())
        assertEquals(before.heldItems, state(completed).heldItems)
        assertTrue(state(completed.copy(status = QuickSortJobStatus.Completed)).heldItems.ids.isEmpty())
        val json = Gson().toJson(QuickSortQueueJournal(jobs = listOf(completed)))
        assertFalse(json.contains("Cache"))
        assertEquals(1, Gson().fromJson(json, QuickSortQueueJournal::class.java).jobs.single().completed)
    }

    @Test fun `pending covers retain failed and cancelled items until their batch ends`() {
        val entries = (1L..4L).map { entry.copy(contentId = it, path = "/source/$it.jpg") }
        val statuses = listOf(QuickSortItemStatus.Succeeded, QuickSortItemStatus.Running,
            QuickSortItemStatus.Failed, QuickSortItemStatus.Cancelled)
        val running = job.copy(items = entries.zip(statuses).map { (entry, status) -> QuickSortQueueItem(entry, status) })
        assertEquals(setOf(2L), state(running).reservedIds)
        assertEquals(setOf(1L, 2L, 3L, 4L), state(running).heldItems.ids)
        assertTrue(state(running.copy(status = QuickSortJobStatus.Cancelled)).heldItems.ids.isEmpty())
    }

    @Test fun `a thousand file completions produce one final batch change for the gallery`() {
        val items = (1L..1000L).map { QuickSortQueueItem(entry.copy(contentId = it, path = "/source/$it.jpg")) }
        val initial = state(job.copy(items = items))
        var current = initial
        var changes = 0
        repeat(1000) { index ->
            val next = state(current.jobs.single().copy(items = current.jobs.single().items.mapIndexed { i, item ->
                if (i == index) item.copy(status = QuickSortItemStatus.Succeeded) else item
            }))
            if (!sameQuickSortUiState(initial, next)) changes++
            current = next
        }
        assertEquals(0, changes)
        assertFalse(sameQuickSortUiState(initial, state(current.jobs.single().copy(status = QuickSortJobStatus.Completed))))
        assertFalse(sameQuickSortUiState(initial, initial.copy(error = "Persist failed")))
        assertFalse(sameQuickSortUiState(initial, state(initial.jobs.single().copy(status = QuickSortJobStatus.NeedsPermission))))
        println("BATCH_UI: 1000 per-file completions; gallery intermediate changes=0; terminal change=1")
    }
}
