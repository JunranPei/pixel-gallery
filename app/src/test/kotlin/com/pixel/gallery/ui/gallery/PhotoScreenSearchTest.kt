package com.pixel.gallery.ui.gallery

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoScreenSearchTest {
    @Test
    fun `header switches on distance or fling velocity`() {
        assertFalse(shouldSwitchPhotoHeader(199f, 1000f, 0f))
        assertTrue(shouldSwitchPhotoHeader(200f, 1000f, 0f))
        assertTrue(shouldSwitchPhotoHeader(20f, 1000f, 1300f))
    }

    @Test
    fun `album search matches filename and full path`() {
        val entry = media("/Pictures/Trips/IMG_2026.JPG")
        val items = listOf(
            GridItem.Header("Today", 1L),
            GridItem.Photo(entry),
        )

        assertEquals(items, filterAlbumGridItems(items, "img_2026"))
        assertEquals(items, filterAlbumGridItems(items, "trips"))
        assertEquals(listOf<GridItem>(), filterAlbumGridItems(items, "missing"))
    }

    private fun media(path: String) = MediaEntry(
        contentId = 1L,
        uri = "content://media/1",
        path = path,
        sourceMimeType = "image/jpeg",
        width = 100,
        height = 100,
        sourceRotationDegrees = 0,
        sizeBytes = 1L,
        dateAddedSecs = 1L,
        dateModifiedMillis = 1L,
    )
}
