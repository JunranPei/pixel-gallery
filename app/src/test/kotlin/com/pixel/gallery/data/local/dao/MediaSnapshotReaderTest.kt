package com.pixel.gallery.data.local.dao

import com.pixel.gallery.data.local.entity.MediaEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MediaSnapshotReaderTest {
    private fun row(id: Long) = MediaEntry(id, "content://media/$id", "/A/$id.jpg",
        "image/jpeg", 100, 200, 0, 1024, 100, 100000, bestTimestamp = 100000 - id)

    private suspend fun read(previous: List<MediaEntry>, rows: List<MediaEntry>, size: Int = 256): List<MediaEntry> {
        val ordered = rows.sortedBy { it.contentId }
        return readMediaSnapshot(previous, size) { limit, after ->
            ordered.asSequence().filter { it.contentId > after }.take(limit).map { it.copy() }.toList()
        }
    }

    @Test fun unchangedLibraryKeepsListAndRecordIdentityAcrossPageBoundaries() = runBlocking {
        val previous = (1L..1024).map(::row)
        assertSame(previous, read(previous, previous))
        assertSame(previous, read(previous, previous, 257))
        assertTrue(read(previous, emptyList()).isEmpty())
    }

    @Test fun additionsRemovalsReorderingAndRestorePreserveAllUnchangedRows() = runBlocking {
        val previous = (1L..131_014).map(::row)
        val changed = previous.filterNot { it.contentId == 256L }.map {
            if (it.contentId == 1L) it.copy(bestTimestamp = -1) else it
        } + row(200_000)
        val edited = read(previous, changed)
        assertEquals(changed.sortedWith(compareByDescending<MediaEntry> { it.bestTimestamp }.thenByDescending { it.contentId }), edited)
        val originals = previous.associateBy { it.contentId }
        edited.filter { it.contentId != 1L && it.contentId != 200_000L }.forEach { assertSame(originals[it.contentId], it) }
        val restored = read(edited, previous)
        assertEquals(previous, restored)
        assertEquals(131_012, restored.indices.count { restored[it] === previous[it] })
    }

    @Test fun everyStoredFieldAndRandomPageSizeMatchAnUncachedRead() = runBlocking {
        val random = Random(50)
        val previous = (1L..500).map(::row)
        repeat(20) {
            val fresh = previous.filter { random.nextInt(6) != 0 }.map { old ->
                when (random.nextInt(5)) {
                    0 -> old.copy(path = "/B/${old.contentId}.jpg", uri = "content://new/${old.contentId}")
                    1 -> old.copy(width = 123, sourceRotationDegrees = 90, sourceMimeType = "image/png")
                    2 -> old.copy(bestTimestamp = random.nextLong(1000), dateModifiedMillis = 27)
                    else -> old
                }
            }
            assertEquals(fresh.sortedWith(compareByDescending<MediaEntry> { it.bestTimestamp }.thenByDescending { it.contentId }),
                read(previous, fresh, random.nextInt(1, 300)))
        }
    }
}
