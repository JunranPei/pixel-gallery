package com.pixel.gallery.ui.viewmodel

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.AlbumSortOrder
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.model.chronologicalTimestamp
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class MediaBrowseIndexTest {
    private val zone = TimeZone.getTimeZone("America/New_York")
    private val entries = listOf(
        media(1, "A/new.jpg", "image/jpeg", "2026-11-01T06:30:00Z"),
        media(2, "A/clip.mp4", "video/mp4", "2026-11-01T05:30:00Z"),
        media(3, "B/clip.mp4", "video/mp4", "2026-10-31T23:30:00Z"),
        media(4, "B/raw.dng", "image/dng", "2026-10-01T03:30:00Z"),
        media(5, "C/unknown", "application/octet-stream", "2026-09-30T22:30:00Z"),
    )

    @Test
    fun categorySwitchesReuseResultsAndMediaObjects() {
        val index = MediaBrowseIndex.build(entries, 3, PhotoSortOrder.DATE_DESC, zone)
        val videos = index.library[MediaTypeFilter.VIDEOS]
        assertSame(videos, index.library[MediaTypeFilter.VIDEOS])
        assertSame(videos.media, videos.search(" ") { _, _ -> false })
        assertSame(videos.albums(AlbumSortOrder.NAME_ASC), videos.albums(AlbumSortOrder.NAME_ASC))
        assertSame(entries[1], videos.media.photos.first())
        assertSame(index.library[MediaTypeFilter.ALL].media.gridItems.filterIsInstance<GridItem.Photo>()[1],
            videos.media.gridItems.filterIsInstance<GridItem.Photo>().first())
    }

    @Test
    fun dateHeadersMatchOriginalFormattingAcrossDstMonthAndSortDirections() {
        for (columns in listOf(3, 6)) for (sort in listOf(PhotoSortOrder.DATE_DESC, PhotoSortOrder.DATE_ASC)) {
            val ordered = sortMediaEntries(entries, sort)
            val index = MediaBrowseIndex.build(ordered, columns, sort, zone)
            for (type in MediaTypeFilter.entries) {
                val filtered = ordered.filter(type::matches)
                assertEquals(legacyGroups(filtered, columns), index.library[type].media.gridItems)
            }
            assertEquals(legacyGroups(ordered, columns), groupOrderedMedia(ordered, columns, sort, zone))
        }
    }

    @Test
    fun albumCoversCountsDatesAndEverySortMatchTheFilteredLibrary() {
        val index = MediaBrowseIndex.build(entries, 3, PhotoSortOrder.DATE_DESC, zone)
        for (type in MediaTypeFilter.entries) for (order in AlbumSortOrder.entries) {
            val expected = buildMediaAlbums(entries.filter(type::matches), order).map {
                it.copy(path = it.path.replace('\\', '/'))
            }
            assertEquals(expected, index.library[type].albums(order))
        }
    }

    @Test
    fun movingAndEditingWithoutCountChangesCreatesFreshCategoryAndAlbumResults() {
        val old = MediaBrowseIndex.build(entries, 3, PhotoSortOrder.DATE_DESC, zone)
        val moved = entries.map { if (it.contentId == 2L) it.copy(path = "/Pictures/C/clip.mp4", sourceMimeType = "image/gif") else it }
        val fresh = MediaBrowseIndex.build(moved, 3, PhotoSortOrder.DATE_DESC, zone)
        assertEquals(entries.size, fresh.library[MediaTypeFilter.ALL].media.photos.size)
        assertEquals(listOf(2L), old.album("/Pictures/A")[MediaTypeFilter.VIDEOS].media.photos.map { it.contentId })
        assertTrue(fresh.album("/Pictures/A")[MediaTypeFilter.VIDEOS].media.photos.isEmpty())
        assertEquals(listOf(2L), fresh.album("/Pictures/C")[MediaTypeFilter.IMAGES].media.photos.map { it.contentId })
        assertEquals(listOf(3L), fresh.library[MediaTypeFilter.VIDEOS].media.photos.map { it.contentId })
    }

    @Test
    fun albumIndexIsReusedAndSearchOnlyIncludesVisibleMediaWithNonemptyHeaders() {
        val index = MediaBrowseIndex.build(entries, 3, PhotoSortOrder.DATE_DESC, zone)
        val scope = index.album("/Pictures/A")
        assertSame(scope, index.album("/Pictures/A"))
        assertEquals(scope[MediaTypeFilter.ALL].media, index.album("A")[MediaTypeFilter.ALL].media)
        val result = scope[MediaTypeFilter.VIDEOS].search("clip") { entry, text -> entry.path.contains(text) }
        assertEquals(listOf(2L), result.photos.map { it.contentId })
        assertEquals(legacyGroups(listOf(entries[1]), 3), result.gridItems)
        assertEquals(List(result.gridItems.size) { (result.gridItems.first() as GridItem.Header).title }, result.positionLabels)
        assertTrue(scope[MediaTypeFilter.VIDEOS].search("new") { entry, text -> entry.path.contains(text) }.gridItems.isEmpty())
        assertTrue(index.album("/missing")[MediaTypeFilter.ALL].media.photos.isEmpty())
    }

    @Test
    fun nameAndSizeSortingKeepOrderAndHaveNoDateHeaders() {
        for (sort in listOf(PhotoSortOrder.NAME_ASC, PhotoSortOrder.NAME_DESC, PhotoSortOrder.SIZE_ASC, PhotoSortOrder.SIZE_DESC)) {
            val ordered = sortMediaEntries(entries, sort)
            val index = MediaBrowseIndex.build(ordered, 3, sort, zone)
            for (type in MediaTypeFilter.entries) {
                assertEquals(ordered.filter(type::matches).map { GridItem.Photo(it) }, index.library[type].media.gridItems)
            }
        }
    }

    @Test
    fun cancelledAlbumBuildCannotPoisonItsCache() {
        val index = MediaBrowseIndex.build(entries, 3, PhotoSortOrder.DATE_DESC, zone)
        try {
            index.album("/Pictures/A") { throw CancellationException("obsolete request") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(2, index.album("/Pictures/A")[MediaTypeFilter.ALL].media.photos.size)
    }

    @Test
    fun filesystemRootAlbumKeepsItsPath() {
        val entry = entries.first().copy(path = "/root.jpg")
        val index = MediaBrowseIndex.build(listOf(entry), 3, PhotoSortOrder.DATE_DESC, zone)
        assertEquals("/", index.library[MediaTypeFilter.ALL].albums(AlbumSortOrder.NAME_ASC).single().path)
        assertEquals(listOf(entry), index.album("/")[MediaTypeFilter.IMAGES].media.photos)
    }

    private fun legacyGroups(photos: List<MediaEntry>, columns: Int): List<GridItem> {
        val formatter = SimpleDateFormat(if (columns >= 6) "MMMM yyyy" else "MMMM d, yyyy", Locale.US)
            .apply { timeZone = zone }
        var previous = ""
        return buildList {
            photos.forEach {
                val title = formatter.format(it.chronologicalTimestamp())
                if (title != previous) { add(GridItem.Header(title, it.chronologicalTimestamp())); previous = title }
                add(GridItem.Photo(it))
            }
        }
    }

    private fun media(id: Long, path: String, mime: String, date: String): MediaEntry {
        val time = Instant.parse(date).toEpochMilli()
        return MediaEntry(id, "content://media/$id", "/Pictures/$path", mime, 1, 1, 0, id * 10,
            1, time, bestTimestamp = time)
    }
}
