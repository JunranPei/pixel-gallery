package com.pixel.gallery.ui

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.ui.gallery.filterAlbumGridItems
import com.pixel.gallery.model.AlbumSortOrder
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import com.pixel.gallery.ui.viewmodel.buildMediaAlbums
import org.junit.Assert.*
import org.junit.Test

class MediaTypeFilterTest {
    @Test
    fun imagesIncludeAnimatedAndRawFormatsAndIgnoreMisleadingExtensions() {
        val entries = listOf(
            media(1, "A/clip.mp4", "image/gif"),
            media(2, "A/raw.dng", "image/x-adobe-dng"),
            media(3, "A/still.jpg", "video/mp4"),
            media(4, "A/unknown", "application/octet-stream"),
        )
        assertEquals(listOf(1L, 2L), entries.filter(MediaTypeFilter.IMAGES::matches).map { it.contentId })
        assertEquals(listOf(3L), entries.filter(MediaTypeFilter.VIDEOS::matches).map { it.contentId })
        assertEquals(entries, entries.filter(MediaTypeFilter.ALL::matches))
    }

    @Test
    fun videoAlbumsExcludeImageOnlyAlbumsAndRebuildCoverCountAndDate() {
        val entries = listOf(
            media(1, "Mixed/new.jpg", "image/jpeg", time = 9000),
            media(2, "Mixed/clip.mp4", "video/mp4", time = 3000),
            media(3, "Images/only.jpg", "image/jpeg", time = 8000),
            media(4, "Videos/clip.mp4", "video/mp4", time = 4000),
            media(5, "Videos/older.mp4", "video/mp4", time = 2000),
        )
        val albums = buildMediaAlbums(entries.filter(MediaTypeFilter.VIDEOS::matches), AlbumSortOrder.COUNT_DESC)
        assertEquals(listOf("Videos", "Mixed"), albums.map { it.name })
        assertEquals(listOf(2, 1), albums.map { it.itemCount })
        assertEquals(entries[1].uri, albums[1].coverUri)
        assertEquals(3000L, albums[1].lastModified)
        assertEquals(listOf("Mixed", "Videos"), buildMediaAlbums(
            entries.filter(MediaTypeFilter.VIDEOS::matches), AlbumSortOrder.DATE_ASC,
        ).map { it.name })
    }

    @Test
    fun albumSearchIntersectsTypeAndPrunesEmptyDateHeaders() {
        val image = media(1, "Mixed/needle.jpg", "image/jpeg")
        val video = media(2, "Mixed/needle.mp4", "video/mp4")
        val otherVideo = media(3, "Mixed/other.mp4", "video/mp4")
        val header = GridItem.Header("Yesterday", 1000)
        val items = listOf(GridItem.Header("Today", 2000), GridItem.Photo(image),
            header, GridItem.Photo(video), GridItem.Photo(otherVideo))
        val visible = filterAlbumGridItems(items, "needle", MediaTypeFilter.VIDEOS)
        assertEquals(listOf(header, GridItem.Photo(video)), visible)
        val visibleIds = visible.mapNotNull { (it as? GridItem.Photo)?.entry?.contentId }.toSet()
        assertEquals(setOf(2L), toggleAlbumSelection(emptySet(), visibleIds, emptySet()))
        assertTrue(filterAlbumGridItems(items, "missing", MediaTypeFilter.VIDEOS).isEmpty())
    }

    @Test
    fun emptyTypeDoesNotLeaveOrphanHeaders() {
        val items = listOf(GridItem.Header("Today", 1000), GridItem.Photo(media(1, "A/a.jpg", "image/jpeg")))
        assertTrue(filterAlbumGridItems(items, "", MediaTypeFilter.VIDEOS).isEmpty())
        assertSame(items, filterAlbumGridItems(items, "", MediaTypeFilter.ALL))
    }

    private fun media(id: Long, path: String, mime: String, time: Long = 1000) = MediaEntry(
        contentId = id, uri = "content://media/$id", path = "/Pictures/$path", sourceMimeType = mime,
        width = 100, height = 100, sourceRotationDegrees = 0, sizeBytes = 10,
        dateAddedSecs = 1, dateModifiedMillis = time, bestTimestamp = time,
    )
}
