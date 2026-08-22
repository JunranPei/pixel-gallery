package com.pixel.gallery.data.repository

import com.pixel.gallery.data.local.dao.KnownEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaRepositoryIndexCleanupTest {
    @Test
    fun deletedMediaPathBecomesOrphaned() {
        assertEquals(
            listOf("/pictures/deleted.jpg"),
            deletedIndexSourcePaths(
                knownEntries = listOf(entry(1, "/pictures/deleted.jpg")),
                currentIds = emptySet(),
                currentPaths = emptySet(),
            ),
        )
    }

    @Test
    fun movedMediaIsNotTreatedAsDeleted() {
        assertEquals(
            emptyList<String>(),
            deletedIndexSourcePaths(
                knownEntries = listOf(entry(1, "/pictures/old.jpg")),
                currentIds = setOf(1L),
                currentPaths = setOf("/pictures/new.jpg"),
            ),
        )
    }

    @Test
    fun unchangedMediaPathIsRetained() {
        assertEquals(
            emptyList<String>(),
            deletedIndexSourcePaths(
                knownEntries = listOf(entry(1, "/pictures/photo.jpg")),
                currentIds = setOf(1L),
                currentPaths = setOf("/pictures/photo.jpg"),
            ),
        )
    }

    @Test
    fun replacementAtSamePathRetainsIndexUntilSourceValidationRuns() {
        assertEquals(
            emptyList<String>(),
            deletedIndexSourcePaths(
                knownEntries = listOf(entry(1, "/pictures/photo.jpg")),
                currentIds = setOf(2L),
                currentPaths = setOf("/pictures/photo.jpg"),
            ),
        )
    }

    @Test
    fun sameMediaIdAtANewPathRelocatesItsIndex() {
        assertEquals(
            listOf(IndexPathRelocation(1L, "/pictures/old.jpg", "/pictures/new.jpg")),
            relocatedIndexSourcePaths(
                knownEntries = listOf(entry(1, "/pictures/old.jpg")),
                currentPathsById = mapOf(1L to "/pictures/new.jpg"),
            ),
        )
    }

    @Test
    fun reusedPathWithANewMediaIdDoesNotRelocateTheOldIndex() {
        assertEquals(
            emptyList<IndexPathRelocation>(),
            relocatedIndexSourcePaths(
                knownEntries = listOf(entry(1, "/pictures/photo.jpg")),
                currentPathsById = mapOf(2L to "/pictures/photo.jpg"),
            ),
        )
    }

    private fun entry(id: Long, path: String) = KnownEntry(
        contentId = id,
        path = path,
        dateModifiedMillis = 1L,
        isTrashed = false,
    )
}
