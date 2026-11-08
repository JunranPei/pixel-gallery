package com.pixel.gallery.data.repository

import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.*
import org.junit.Test

class MediaEntrySnapshotsTest {
    private fun entry(id: Long) = MediaEntry(id, "content://media/$id", "/A/$id.jpg",
        "image/jpeg", 100, 200, 0, 1024, 100, 100000, bestTimestamp = 100000 - id)

    @Test fun unchangedDecodedGenerationKeepsTheOriginalList() {
        val previous = (1L..10).map(::entry)
        assertSame(previous, reuseUnchangedMediaEntries(previous, previous.map { it.copy() }))
    }

    @Test fun aReorderedEditSharesEveryOtherRecordWithoutMutatingTheOldSnapshot() {
        val previous = (1L..8).map(::entry)
        val fresh = previous.reversed().map { it.copy(bestTimestamp = if (it.contentId == 3L) 42 else it.bestTimestamp) }
        val result = reuseUnchangedMediaEntries(previous, fresh)
        assertEquals(fresh, result)
        result.forEach { row ->
            val old = previous.first { it.contentId == row.contentId }
            if (row.contentId == 3L) assertNotSame(old, row) else assertSame(old, row)
        }
        assertEquals(99997L, previous[2].bestTimestamp)
    }

    @Test fun removedRowsStayRemovedAndAddedRowsStayNew() {
        val previous = (1L..5).map(::entry)
        val fresh = listOf(previous[3].copy(), entry(6), previous[1].copy())
        val result = reuseUnchangedMediaEntries(previous, fresh)
        assertEquals(listOf(4L, 6L, 2L), result.map { it.contentId })
        assertSame(previous[3], result[0])
        assertSame(fresh[1], result[1])
        assertSame(previous[1], result[2])
        assertTrue(reuseUnchangedMediaEntries(previous, emptyList()).isEmpty())
    }

    @Test fun allStoredFieldsParticipateInReuse() {
        val old = entry(1)
        val edits = listOf(old.copy(contentId = 2), old.copy(uri = "content://changed"),
            old.copy(path = "/hidden/1.jpg"), old.copy(sourceMimeType = "video/mp4"),
            old.copy(width = 101), old.copy(height = 201), old.copy(sourceRotationDegrees = 90),
            old.copy(sizeBytes = 2048), old.copy(dateAddedSecs = 200), old.copy(dateModifiedMillis = 200000),
            old.copy(sourceDateTakenMillis = 2), old.copy(durationMillis = 2), old.copy(isTrashed = true),
            old.copy(bestTimestamp = 200000), old.copy(trashedAtMillis = 200000), old.copy(vaultOriginalPath = "/vault/1.jpg"))
        edits.forEach { edited ->
            val result = reuseUnchangedMediaEntries(listOf(old), listOf(edited)).single()
            assertSame(edited, result)
            assertNotSame(old, result)
        }
    }

    @Test fun editingThenRestoringOneRowDoesNotDuplicateTheWholeLibrary() {
        val initial = (1L..20_000).map(::entry)
        val edited = reuseUnchangedMediaEntries(initial, initial.map {
            it.copy(bestTimestamp = if (it.contentId == 1L) 0 else it.bestTimestamp)
        }.reversed())
        val restored = reuseUnchangedMediaEntries(edited, initial.map { it.copy() })
        assertEquals(initial, restored)
        assertEquals(19_999, restored.indices.count { restored[it] === initial[it] })
        assertEquals(19_999, edited.count { it === initial[(it.contentId - 1).toInt()] })
    }
}
