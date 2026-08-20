package com.pixel.gallery.ui

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScaffoldSearchTest {
    @Test
    fun headerSwipeThresholdScalesWithActualHeaderWidth() {
        assertFalse(shouldSwitchHomeHeader(199f, 1000f, 0f))
        assertTrue(shouldSwitchHomeHeader(200f, 1000f, 0f))
        assertFalse(shouldSwitchHomeHeader(399f, 2000f, 0f))
        assertTrue(shouldSwitchHomeHeader(400f, 2000f, 0f))
    }

    @Test
    fun quickHeaderFlickWorksInBothDirections() {
        assertTrue(shouldSwitchHomeHeader(20f, 1000f, 1300f))
        assertTrue(shouldSwitchHomeHeader(-20f, 1000f, -1300f))
        assertFalse(shouldSwitchHomeHeader(20f, 1000f, 1200f))
    }

    @Test
    fun fileSearchMatchesFileNameButNotParentFolders() {
        val entry = mediaEntry(1, "/Pictures/Holiday/Beach.JPG")

        assertTrue(entry.matchesFileSearch("beach"))
        assertTrue(entry.matchesFileSearch(".jpg"))
        assertFalse(entry.matchesFileSearch("holiday"))
    }

    @Test
    fun filteringKeepsOnlyHeadersThatHaveMatchingPhotos() {
        val firstHeader = GridItem.Header("August 19, 2026", 2)
        val secondHeader = GridItem.Header("August 18, 2026", 1)
        val match = GridItem.Photo(mediaEntry(2, "/Pictures/Camera/needle.png"))
        val miss = GridItem.Photo(mediaEntry(1, "/Pictures/Camera/haystack.png"))

        assertEquals(
            listOf(secondHeader, match),
            filterPhotoGridItems(
                listOf(firstHeader, miss, secondHeader, match),
                query = "needle",
            ),
        )
    }

    private fun mediaEntry(id: Long, path: String) = MediaEntry(
        contentId = id,
        uri = "content://media/$id",
        path = path,
        sourceMimeType = "image/jpeg",
        width = 1,
        height = 1,
        sourceRotationDegrees = 0,
        sizeBytes = 1,
        dateAddedSecs = 1,
        dateModifiedMillis = 1,
    )
}
