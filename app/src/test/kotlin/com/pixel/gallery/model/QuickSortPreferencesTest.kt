package com.pixel.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSortPreferencesTest {
    @Test fun `default remains two rows and supported counts are respected`() {
        assertEquals(2, QuickSortPreferences().albumRows)
        for (rows in 1..4) assertEquals(rows, quickSortAlbumRows(rows, 20))
    }

    @Test fun `few albums and invalid settings cannot create empty or excessive rows`() {
        assertEquals(1, quickSortAlbumRows(4, 1))
        assertEquals(2, quickSortAlbumRows(4, 2))
        assertEquals(1, quickSortAlbumRows(4, 0))
        assertEquals(1, quickSortAlbumRows(-1, 10))
        assertEquals(4, quickSortAlbumRows(99, 10))
    }
}
