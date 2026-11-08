package com.pixel.gallery.ui

import com.pixel.gallery.model.MediaTypeFilter
import org.junit.Assert.*
import org.junit.Test

class ScreenStateKeyTest {
    @Test fun `viewer key uses value hashes rather than enum identity`() {
        val screen = Screen.Viewer(-1, Screen.ViewerSource.External, externalUri = "content://media/42",
            externalMimeType = "video/mp4", mediaType = MediaTypeFilter.VIDEOS)
        val expected = listOf("Viewer", -1L, "External", null, "content://media/42", "video/mp4",
            null, null, "VIDEOS", false)
        assertEquals(expected, screen.stableStateKey())
        assertEquals(expected.hashCode(), screen.stableStateKey().hashCode())
        assertEquals(screen.stableStateKey(), screen.copy().stableStateKey())
        assertNotEquals(screen.stableStateKey(), screen.copy(externalUri = "content://media/43").stableStateKey())
        assertNotEquals(screen.stableStateKey(), screen.copy(mediaType = MediaTypeFilter.ALL).stableStateKey())
    }

    @Test fun `singleton and collection routes have repeatable keys`() {
        assertEquals("Home", Screen.Home.stableStateKey())
        assertEquals("Settings", Screen.Settings.stableStateKey())
        assertEquals(listOf("Label", 42L), Screen.Label(42).stableStateKey())
        assertEquals(listOf("Photo", "Trip", "IMAGES", false),
            Screen.Photo("Trip", MediaTypeFilter.IMAGES).stableStateKey())
        assertNotEquals(Screen.Photo("Trip").stableStateKey(), Screen.Photo("Trip", hiddenAlbum = true).stableStateKey())
    }

    @Test fun `transfer identity uses ordered ID content without array identity`() {
        val first = Screen.TransferDestination(longArrayOf(1, 5, 9), Screen.TransferOrigin.Grid).stableStateKey()
        val second = Screen.TransferDestination(longArrayOf(1, 5, 9), Screen.TransferOrigin.Grid).stableStateKey()
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, Screen.TransferDestination(longArrayOf(5, 1, 9), Screen.TransferOrigin.Grid).stableStateKey())
        assertNotEquals(first, Screen.TransferDestination(longArrayOf(1, 5, 9), Screen.TransferOrigin.Viewer).stableStateKey())
    }
}
