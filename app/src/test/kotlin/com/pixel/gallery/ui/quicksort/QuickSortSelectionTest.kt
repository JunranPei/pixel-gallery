package com.pixel.gallery.ui.quicksort

import com.pixel.gallery.model.TransferDestination
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSortSelectionTest {
    @Test
    fun `lift excludes queued and absent files and snapshots selection`() {
        val selected = mutableSetOf(1L, 2L, 3L, 99L)
        val snapshot = frozenQuickSortSelection(selected, setOf(1L, 2L, 3L), setOf(2L))
        selected.clear()
        assertEquals(setOf(1L, 3L), snapshot)
    }

    @Test
    fun `range skips date headers and reserved files in either direction`() {
        val rows = listOf(null, 1L, 2L, null, 3L, 4L)
        val result = quickSortRangeSelection(setOf(10L, 2L), rows, 5, 1, setOf(2L, 3L))
        assertEquals(setOf(10L, 1L, 4L), result)
    }

    @Test
    fun `invalid range never invents a selection or retains reserved files`() {
        assertEquals(setOf(7L), quickSortRangeSelection(setOf(7L, 8L), listOf(1L), -1, 1, setOf(8L)))
    }

    @Test
    fun `reordering crosses rows without losing targets`() {
        assertEquals(listOf("b", "c", "d", "e", "a", "f"), moveQuickSortTarget(listOf("a", "b", "c", "d", "e", "f"), 0, 4))
        assertEquals(listOf("a", "b"), moveQuickSortTarget(listOf("a", "b"), 0, -1))
    }

    @Test
    fun `dock edge uses the actual container origin`() {
        assertTrue(quickSortDockAtTop(450f, 100f, 800f))
        assertFalse(quickSortDockAtTop(550f, 100f, 800f))
    }

    @Test
    fun `same album names on different volumes get distinct short paths`() {
        val internal = TransferDestination("internal", "Travel", "/storage/emulated/0/Pictures/Travel")
        val card = TransferDestination("card", "Travel", "/storage/ABCD/Pictures/Travel")
        val targets = listOf(internal, card)
        assertEquals("0/Pictures", quickSortPathSuffix(internal, targets))
        assertEquals("ABCD/Pictures", quickSortPathSuffix(card, targets))
    }

    @Test
    fun `release in a target gap or outside dock never chooses a default album`() {
        val bounds = linkedMapOf("a" to Rect(10f, 20f, 60f, 70f), "b" to Rect(70f, 20f, 120f, 70f))
        assertEquals("a", quickSortDropTarget(Offset(40f, 40f), bounds))
        assertEquals("b", quickSortDropTarget(Offset(80f, 40f), bounds))
        assertEquals(null, quickSortDropTarget(Offset(65f, 40f), bounds))
        assertEquals(null, quickSortDropTarget(Offset(40f, 80f), bounds))
    }
}
