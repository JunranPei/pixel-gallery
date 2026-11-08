package com.pixel.gallery.ui.viewmodel

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.MediaTypeFilter
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaBrowseIndexCacheTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private val stamp = Instant.parse("2026-11-01T00:30:00Z").toEpochMilli()
    private val entries = (1L..4L).map { id ->
        MediaEntry(id, "content://media/$id", "/Pictures/A/$id.jpg", "image/jpeg",
            100, 100, 0, 1000, 1, stamp, bestTimestamp = stamp - id)
    }

    @Test fun twelveWindowsWithIndependentListsShareOneLibraryIndex() {
        val cache = MediaBrowseIndexCache()
        val large = (0 until 20_000).map { entries[it % entries.size].copy(contentId = it.toLong()) }
        val indexes = List(12) { cache.getOrBuild(large.toList(), 4, PhotoSortOrder.DATE_DESC, zone) }
        indexes.forEach { assertSame(indexes.first(), it) }
        assertEquals(large, indexes.first().library[MediaTypeFilter.ALL].media.photos)
    }

    @Test fun concurrentWindowLaunchesPublishOneCompleteIndex() {
        val cache = MediaBrowseIndexCache()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val jobs = List(8) { pool.submit(Callable {
                start.await()
                cache.getOrBuild(entries.toList(), 4, PhotoSortOrder.DATE_DESC, zone)
            }) }
            start.countDown()
            val indexes = jobs.map { it.get(5, TimeUnit.SECONDS) }
            indexes.forEach { assertSame(indexes.first(), it) }
            assertEquals(entries, indexes.first().album("/Pictures/A")[MediaTypeFilter.IMAGES].media.photos)
        } finally { pool.shutdownNow() }
    }

    @Test fun movingOrEditingMediaWithTheSameCountInvalidatesTheIndex() {
        val cache = MediaBrowseIndexCache()
        val old = cache.getOrBuild(entries, 4, PhotoSortOrder.DATE_DESC, zone)
        val edited = entries.map { if (it.contentId == 1L) it.copy(path = "/Pictures/B/1.mp4", sourceMimeType = "video/mp4") else it }
        val new = cache.getOrBuild(edited, 4, PhotoSortOrder.DATE_DESC, zone)
        assertNotSame(old, new)
        assertEquals(4, old.album("/Pictures/A")[MediaTypeFilter.IMAGES].media.photos.size)
        assertEquals(3, new.album("/Pictures/A")[MediaTypeFilter.IMAGES].media.photos.size)
        assertEquals(listOf(1L), new.album("/Pictures/B")[MediaTypeFilter.VIDEOS].media.photos.map { it.contentId })
    }

    @Test fun visibilityChangesCannotReuseAnIndexContainingHiddenMedia() {
        val cache = MediaBrowseIndexCache()
        val full = cache.getOrBuild(entries, 4, PhotoSortOrder.DATE_DESC, zone)
        val visible = cache.getOrBuild(entries.drop(1), 4, PhotoSortOrder.DATE_DESC, zone)
        assertNotSame(full, visible)
        assertEquals(listOf(2L, 3L, 4L), visible.library[MediaTypeFilter.ALL].media.photos.map { it.contentId })
        assertTrue(cache.getOrBuild(emptyList(), 4, PhotoSortOrder.DATE_DESC, zone)
            .library[MediaTypeFilter.ALL].media.photos.isEmpty())
    }

    @Test fun columnsTimeZoneAndSortKeepTheirOwnHeadersAndOrder() {
        val cache = MediaBrowseIndexCache(8)
        val daily = cache.getOrBuild(entries, 4, PhotoSortOrder.DATE_DESC, zone)
        assertSame(daily, cache.getOrBuild(entries, 5, PhotoSortOrder.DATE_DESC, zone))
        val monthly = cache.getOrBuild(entries, 6, PhotoSortOrder.DATE_DESC, zone)
        val local = cache.getOrBuild(entries, 4, PhotoSortOrder.DATE_DESC, TimeZone.getTimeZone("America/Los_Angeles"))
        assertNotEquals(daily.library[MediaTypeFilter.ALL].media.positionLabels, monthly.library[MediaTypeFilter.ALL].media.positionLabels)
        assertNotEquals(daily.library[MediaTypeFilter.ALL].media.positionLabels, local.library[MediaTypeFilter.ALL].media.positionLabels)
        val reversed = cache.getOrBuild(entries.reversed(), 4, PhotoSortOrder.DATE_ASC, zone)
        assertEquals(entries.reversed(), reversed.library[MediaTypeFilter.ALL].media.photos)
        val byName = cache.getOrBuild(entries, 4, PhotoSortOrder.NAME_ASC, zone)
        assertTrue(byName.library[MediaTypeFilter.ALL].media.gridItems.all { it is PhotosViewModel.GridItem.Photo })
    }

    @Test fun cancelledBuildDoesNotPoisonTheNextWindow() {
        val cache = MediaBrowseIndexCache()
        var checks = 0
        try {
            cache.getOrBuild(entries, 4, PhotoSortOrder.DATE_DESC, zone) {
                if (++checks == 3) throw CancellationException("window closed")
            }
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        val recovered = cache.getOrBuild(entries, 4, PhotoSortOrder.DATE_DESC, zone)
        assertEquals(entries, recovered.library[MediaTypeFilter.ALL].media.photos)
    }

    @Test fun cacheEvictionDoesNotChangeDataStillUsedByAnotherWindow() {
        val cache = MediaBrowseIndexCache(1)
        val first = cache.getOrBuild(entries, 4, PhotoSortOrder.DATE_DESC, zone)
        cache.getOrBuild(entries.reversed(), 4, PhotoSortOrder.DATE_ASC, zone)
        val rebuilt = cache.getOrBuild(entries, 4, PhotoSortOrder.DATE_DESC, zone)
        assertNotSame(first, rebuilt)
        assertEquals(first.library[MediaTypeFilter.ALL].media, rebuilt.library[MediaTypeFilter.ALL].media)
    }
}
