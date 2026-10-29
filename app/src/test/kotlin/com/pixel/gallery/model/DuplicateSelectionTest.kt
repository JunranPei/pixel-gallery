package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateSelectionTest {
    @Test
    fun tappingTheRetainedPhotoSwapsThePairInOneStep() {
        val pair = group(1, 2)
        val selected = toggleDuplicateSelection(pair, setOf(2L, 9L), 1)
        assertEquals(setOf(1L, 9L), selected)
        assertEquals(setOf(2L, 9L), toggleDuplicateSelection(pair, selected, 2))
        assertEquals(setOf(9L), toggleDuplicateSelection(pair, selected, 1))
    }

    @Test
    fun everyValidSelectionKeepsAtLeastOneCopyAndDoesNotAffectOtherGroups() {
        for (size in 2..6) {
            val group = group(*(1L..size.toLong()).toList().toLongArray())
            for (mask in 0 until (1 shl size) - 1) {
                val selected = (1..size).filter { mask and (1 shl (it - 1)) != 0 }.map { it.toLong() }.toSet() + 99L
                for (id in 1L..size.toLong()) {
                    val next = toggleDuplicateSelection(group, selected, id)
                    assertTrue(99L in next)
                    assertTrue(group.entries.any { it.contentId !in next })
                    assertEquals(id !in selected, id in next)
                }
            }
        }
    }

    @Test
    fun selectAllPreservesTheChosenKeeperThenClearsEverySelectedCopy() {
        val groups = listOf(group(1, 2, 3), group(4, 5))
        val next = toggleAllDuplicateSelection(groups, setOf(1L, 9L))
        assertEquals(setOf(1L, 3L, 5L, 9L), next)
        assertTrue(allDuplicateCopiesSelected(groups, next))
        assertEquals(setOf(9L), toggleAllDuplicateSelection(groups, next))
    }

    @Test
    fun customPairSelectionIsRecognizedAsAllCopiesSelected() {
        val groups = listOf(group(1, 2))
        assertTrue(allDuplicateCopiesSelected(groups, setOf(1L)))
        assertEquals(emptySet<Long>(), toggleAllDuplicateSelection(groups, setOf(1L)))
        assertFalse(allDuplicateCopiesSelected(groups, emptySet()))
        assertFalse(allDuplicateCopiesSelected(emptyList(), emptySet()))
    }

    @Test
    fun invalidIdAndSingletonCannotSelectTheOnlyCopy() {
        assertEquals(setOf(9L), toggleDuplicateSelection(group(1, 2), setOf(9L), 3))
        assertEquals(setOf(9L), toggleDuplicateSelection(group(1), setOf(9L), 1))
    }

    private fun group(vararg ids: Long): DuplicateGroup {
        val entries = ids.map { id ->
            MediaEntry(contentId = id, uri = "content://media/$id", path = "/Pictures/$id.jpg",
                sourceMimeType = "image/jpeg", width = 100, height = 100, sourceRotationDegrees = 0,
                sizeBytes = 4, dateAddedSecs = 1, dateModifiedMillis = 1)
        }
        return DuplicateGroup(entries.first(), entries.drop(1), DuplicateFingerprint(4, "same"))
    }
}
