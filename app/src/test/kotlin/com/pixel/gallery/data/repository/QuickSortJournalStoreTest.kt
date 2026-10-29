package com.pixel.gallery.data.repository

import com.google.gson.Gson
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class QuickSortJournalStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val gson = Gson()
    private var fullWrites = 0
    private var failAfterSnapshot = false
    private fun snapshotFile() = File(folder.root, "queue.json")
    private fun deltaFile() = File(folder.root, "queue.deltas")
    private fun store() = QuickSortJournalStore(deltaFile(), {
        if (snapshotFile().exists()) snapshotFile().reader().use { gson.fromJson(it, QuickSortQueueJournal::class.java) }
        else QuickSortQueueJournal()
    }, { snapshot ->
        fullWrites++
        snapshotFile().writer().use { gson.toJson(snapshot, it) }
        if (failAfterSnapshot) throw IOException("Interrupted before clearing deltas")
    })
    private fun entry(id: Long) = MediaEntry(id, "content://media/$id", "/source/$id.jpg", "image/jpeg", 100, 100, 0, 10, 1, 1)
    private fun job(size: Int = 2) = QuickSortJob("batch", TransferDestination("target", "Target", "/target"),
        (1..size).map { QuickSortQueueItem(entry(it.toLong())) }, 1, status = QuickSortJobStatus.Running, sessionId = "session")
    private fun checkpoint(item: QuickSortQueueItem, stage: QuickMoveStage) = QuickMoveCheckpoint(
        item.entry, "/target/${item.entry.contentId}.jpg", null, 1, 10, "sha256", stage)

    @Test fun `thousand item batch writes small durable changes without rewriting the batch`() {
        val store = store()
        var current = job(1000)
        store.saveSnapshot(QuickSortQueueJournal(jobs = listOf(current)))
        val initialSize = snapshotFile().length()
        repeat(1000) { index ->
            val items = current.items.toMutableList()
            items[index] = items[index].copy(status = QuickSortItemStatus.Running,
                checkpoint = checkpoint(items[index], QuickMoveStage.TargetReady))
            current = current.copy(items = items)
            store.saveItem(current, index, null)
        }
        assertEquals(1, fullWrites)
        val totalBytes = initialSize + deltaFile().length()
        assertTrue("Delta writes should be far smaller than 1000 full snapshots", totalBytes < initialSize * 1000 / 50)
        val restored = store().load()
        assertEquals(current, restored.jobs.single())
        println("1000 items: full snapshots=$fullWrites; bytes=$totalBytes; former full-rewrite lower bound=${initialSize * 1000}")
    }

    @Test fun `legacy snapshot without revision is compatible`() {
        snapshotFile().writeText(gson.toJson(QuickSortQueueJournal(jobs = listOf(job()))).replace("\"revision\":0,", ""))
        assertEquals(job(), store().load().jobs.single())
    }

    @Test fun `torn last delta is discarded before appending the next checkpoint`() {
        val store = store()
        val current = job()
        store.saveSnapshot(QuickSortQueueJournal(jobs = listOf(current)))
        val ready = current.copy(items = current.items.map { it.copy(checkpoint = checkpoint(it, QuickMoveStage.TargetReady)) })
        store.saveItem(ready, 0, null)
        val committedLength = deltaFile().length()
        deltaFile().appendText("{incomplete")
        val reopened = store()
        val recovered = reopened.load().jobs.single()
        assertEquals(QuickMoveStage.TargetReady, recovered.items[0].checkpoint!!.stage)
        assertEquals(committedLength, deltaFile().length())
        reopened.saveItem(recovered.copy(items = recovered.items.map {
            it.copy(checkpoint = checkpoint(it, QuickMoveStage.SourceRemoved))
        }), 0, null)
        assertEquals(QuickMoveStage.SourceRemoved, store().load().jobs.single().items[0].checkpoint!!.stage)
    }

    @Test fun `new snapshot wins if process stops before old deltas are cleared`() {
        val store = store()
        val current = job()
        store.saveSnapshot(QuickSortQueueJournal(jobs = listOf(current)))
        store.saveItem(current.copy(items = current.items.map { it.copy(status = QuickSortItemStatus.Running) }), 0, null)
        val completed = current.copy(status = QuickSortJobStatus.Completed)
        failAfterSnapshot = true
        try { store.saveSnapshot(QuickSortQueueJournal(jobs = listOf(completed))); fail("Expected injected interruption") }
        catch (_: IOException) { }
        assertTrue(deltaFile().length() > 0)
        assertEquals(completed, store().load().jobs.single())
    }

    @Test fun `corrupt complete delta stops recovery without erasing the evidence`() {
        val store = store()
        store.saveSnapshot(QuickSortQueueJournal(jobs = listOf(job())))
        store.saveItem(job(), 0, null)
        val damaged = deltaFile().readText().replace("content://media/1", "content://media/9")
        deltaFile().writeText(damaged)
        try { store().load(); fail("Corruption must not silently authorize a move") }
        catch (_: IllegalStateException) { }
        assertEquals(damaged, deltaFile().readText())
    }

    @Test fun `first success time and album usage survive delta replay and compaction`() {
        val store = store()
        val current = job()
        store.saveSnapshot(QuickSortQueueJournal(jobs = listOf(current)))
        val updated = current.copy(firstSucceededAt = 123)
        val uses = listOf(QuickSortAlbumUse("batch", "target", 123))
        store.saveItem(updated, 0, uses)
        val reopened = store()
        val saved = reopened.load()
        assertEquals(123L, saved.jobs.single().firstSucceededAt)
        assertEquals(uses, saved.albumUses)
        reopened.saveSnapshot(saved)
        assertEquals(0L, deltaFile().length())
        assertEquals(saved.jobs, store().load().jobs)
        assertEquals(uses, store().load().albumUses)
    }
}
