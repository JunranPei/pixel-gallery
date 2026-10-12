package com.pixel.gallery.model

import com.google.gson.Gson
import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.*
import org.junit.Test

class QuickSortAlbumUsageTest {
    private val now = QUICK_SORT_USAGE_WINDOW_MILLIS * 3
    private fun album(key: String) = TransferDestination(key, key, "/Pictures/$key")
    private fun job(id: String, key: String, successes: Int = 1, timestamp: Long = now - 1000): QuickSortJob {
        val entry = MediaEntry(1, "uri", "/Source/a.jpg", "image/jpeg", 16, 16, 0, 4, 1, 1000)
        return QuickSortJob(id, album(key), List(successes) { QuickSortQueueItem(entry, QuickSortItemStatus.Succeeded) },
            timestamp, firstSucceededAt = timestamp)
    }

    @Test fun batchSizeAndRepeatedPersistenceNeverInflateFrequency() {
        val jobs = listOf(job("batch-a", "a", 500), job("batch-b", "b", 1), job("batch-c", "b", 1))
        val uses = quickSortAlbumUses(emptyList(), jobs, now)
        assertEquals(3, uses.size)
        assertEquals(uses, quickSortAlbumUses(uses, jobs, now))
        assertEquals(listOf("b", "a"), frequentQuickSortAlbums(listOf(album("a"), album("b")), uses, now).map { it.stableKey })
    }

    @Test fun failuresSelectionsAndSkippedItemsNeverBecomeFrequent() {
        val entry = job("template", "a").items.first().entry
        val jobs = QuickSortItemStatus.entries.filter { it != QuickSortItemStatus.Succeeded }.map { status ->
            job(status.name, "a").copy(items = listOf(QuickSortQueueItem(entry, status)))
        }
        assertTrue(quickSortAlbumUses(emptyList(), jobs, now).isEmpty())
        assertTrue(frequentQuickSortAlbums(listOf(album("a")), emptyList(), now).isEmpty())
    }

    @Test fun historyTrimmingAndRestartKeepUsageAndPruneExpiredAndUnavailableAlbums() {
        val jobs = listOf(job("a", "a"), job("b", "b"), job("old", "old", timestamp = now - QUICK_SORT_USAGE_WINDOW_MILLIS - 1))
        val uses = quickSortAlbumUses(emptyList(), jobs, now)
        val json = Gson().toJson(QuickSortQueueJournal(jobs = emptyList(), albumUses = uses))
        val restored = Gson().fromJson(json, QuickSortQueueJournal::class.java)
        assertEquals(uses, quickSortAlbumUses(restored.albumUses.orEmpty(), restored.jobs, now))
        assertEquals(listOf(album("b")), frequentQuickSortAlbums(listOf(album("b"), album("old")), restored.albumUses.orEmpty(), now))
    }

    @Test fun tiedCountsUseRecencyWithEightAlbumLimitAndLegacyMigration() {
        val jobs = (0..11).map { job("batch-$it", "$it", timestamp = now - it * 1000L) }
        val uses = quickSortAlbumUses(emptyList(), jobs, now)
        assertEquals((0..7).map(Int::toString), frequentQuickSortAlbums(jobs.map { it.destination }.reversed(), uses, now).map { it.stableKey })
        val legacy = Gson().fromJson("{\"version\":1,\"jobs\":[]}", QuickSortQueueJournal::class.java)
        assertTrue(quickSortAlbumUses(legacy.albumUses.orEmpty(), legacy.jobs, now).isEmpty())
        val legacySuccess = job("legacy", "a").copy(firstSucceededAt = null)
        assertEquals(legacySuccess.createdAt, quickSortAlbumUses(emptyList(), listOf(legacySuccess), now).single().usedAt)
    }
}
