package com.pixel.gallery.ui.components

import org.junit.Assert.*
import org.junit.Test

class CompactSheetPullTest {
    @Test fun `compact header pull can finish before reaching the screen edge`() {
        for (remaining in listOf(24f, 48f, 68f, 92f)) {
            val threshold = compactSheetDismissThreshold(40f, remaining, 8f)
            assertTrue(threshold > 0f)
            assertTrue(threshold + 8f < remaining)
            val gesture = CompactSheetPull(true, 2f)
            gesture.drag(threshold - 1f)
            assertFalse(gesture.shouldDismiss(threshold))
            gesture.drag(1f)
            assertTrue(gesture.shouldDismiss(threshold))
        }
    }
    @Test fun `large screens retain the preferred deliberate pull distance`() {
        assertEquals(40f, compactSheetDismissThreshold(40f, 300f, 8f), .001f)
        assertEquals(0f, compactSheetDismissThreshold(40f, 4f, 8f), .001f)
    }
    @Test fun `expanded gesture cannot skip collapse even with a long pull`() {
        val gesture = CompactSheetPull(false, 10f)
        gesture.drag(1000f)
        assertFalse(gesture.shouldDismiss(96f))
    }
    @Test fun `collapsed gesture requires distance and has resistance`() {
        val gesture = CompactSheetPull(true, 10f)
        gesture.drag(80f)
        assertEquals(28f, gesture.offset, .001f)
        assertFalse(gesture.shouldDismiss(96f))
        gesture.drag(16f)
        assertTrue(gesture.shouldDismiss(96f))
    }
    @Test fun `reversing a pull cancels dismissal and an upward start cannot dismiss`() {
        val gesture = CompactSheetPull(true, 10f)
        gesture.drag(120f)
        gesture.drag(-100f)
        assertFalse(gesture.shouldDismiss(96f))
        val opening = CompactSheetPull(true, -10f)
        opening.drag(300f)
        assertFalse(opening.shouldDismiss(96f))
    }
}
