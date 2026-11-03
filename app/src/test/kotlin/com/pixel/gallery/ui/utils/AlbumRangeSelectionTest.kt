package com.pixel.gallery.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumRangeSelectionTest {
    private val keys = listOf("a", "b", "c", "d")
    @Test fun reverseRangePreservesEarlierSelection() {
        assertEquals(setOf("outside", "b", "c", "d"), albumRangeSelection(setOf("outside"), keys, "d", "b", true))
    }
    @Test fun reversingFingerRestoresOriginalStateOutsideRange() {
        val original = setOf("outside", "d")
        assertEquals(setOf("outside", "a", "b", "d"), albumRangeSelection(original, keys, "a", "b", true))
        assertEquals(setOf("outside", "b", "c", "d"), albumRangeSelection(original + keys, keys, "a", "a", false))
    }
    @Test fun deselectionPreservesOtherItems() {
        assertEquals(setOf("a", "d", "outside"), albumRangeSelection(keys.toSet() + "outside", keys, "b", "c", false))
    }
    @Test fun missingAnchorDoesNotChangeSelection() {
        assertEquals(setOf("b"), albumRangeSelection(setOf("b"), keys, "deleted", "c", true))
    }
}
