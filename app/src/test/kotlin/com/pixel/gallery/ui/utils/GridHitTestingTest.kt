package com.pixel.gallery.ui.utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridHitTestingTest {
    @Test
    fun `top dock pointer hits the selected first row instead of an unselected later row`() {
        // Screen y=800, grid origin y=278. Placement adds 252 px of upper padding;
        // the first thumbnail is therefore actually visible at screen y=643..970.
        val viewportPoint = Offset(170f, 800f - 278f)
        val firstRow = Rect(0f, 113f, 327f, 440f)
        val nextRow = Rect(0f, 452f, 327f, 779f)
        assertTrue(nextRow.contains(viewportPoint)) // Reproduces the previous wrong-row hit.
        val contentPoint = gridPointerInContentCoordinates(viewportPoint, 12f, -252)
        assertTrue(firstRow.contains(contentPoint))
        assertFalse(nextRow.contains(contentPoint))
    }

    @Test
    fun `ordinary grid also subtracts its small top and side padding`() {
        assertEquals(Offset(158f, 200f), gridPointerInContentCoordinates(Offset(170f, 212f), 12f, -12))
    }

    @Test
    fun `zero padding does not shift coordinates`() {
        assertEquals(Offset(17.25f, 24.75f), gridPointerInContentCoordinates(Offset(17.25f, 24.75f), 0f, 0))
    }

    @Test
    fun `a scrolled item with negative content offset remains hittable`() {
        val partiallyScrolledItem = Rect(0f, -120f, 327f, 207f)
        val contentPoint = gridPointerInContentCoordinates(Offset(170f, 150f), 12f, -252)
        assertEquals(Offset(158f, -102f), contentPoint)
        assertTrue(partiallyScrolledItem.contains(contentPoint))
    }

    @Test
    fun `padding and fractional gaps never become thumbnail hits through rounding`() {
        val firstCell = Rect(0f, 0f, 100f, 100f)
        assertFalse(firstCell.contains(gridPointerInContentCoordinates(Offset(11.75f, 280f), 12f, -252)))
        assertFalse(firstCell.contains(gridPointerInContentCoordinates(Offset(50f, 251.9f), 12f, -252)))
        assertTrue(firstCell.contains(gridPointerInContentCoordinates(Offset(111.9f, 280f), 12f, -252)))
        assertFalse(firstCell.contains(gridPointerInContentCoordinates(Offset(112f, 280f), 12f, -252)))
    }
}
