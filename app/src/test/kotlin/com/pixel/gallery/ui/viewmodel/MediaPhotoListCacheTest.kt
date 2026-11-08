package com.pixel.gallery.ui.viewmodel

import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MediaPhotoListCacheTest {
    private fun photo(id: Long, path: String = "/A/$id.jpg") = MediaEntry(
        id, "content://media/$id", path, "image/jpeg", 100, 100, 0, id, 1, 1, bestTimestamp = id)

    @Test fun fiftyConcurrentWindowsReceiveOneSortedList() {
        val source = List(131_014) { photo(it.toLong()) }
        val cache = MediaPhotoListCache()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(List(50) {
                Callable { cache.get(source, emptySet(), PhotoSortOrder.DATE_DESC) }
            }).map { it.get() }
            results.forEach { assertSame(results.first(), it) }
            assertEquals(131_013L, results.first().first().contentId)
            assertSame(source, cache.get(source, emptySet()))
        } finally { executor.shutdownNow() }
    }

    @Test fun metadataVisibilityAndOrderHaveIndependentCacheKeys() {
        val source = listOf(photo(1), photo(2, "/Hidden/2.jpg"), photo(3))
        val cache = MediaPhotoListCache()
        val visible = cache.get(source, setOf("/Hidden"))
        assertEquals(listOf(1L, 3L), visible.map { it.contentId })
        assertEquals(listOf(2L), cache.get(source, setOf("/Hidden"), hiddenOnly = true).map { it.contentId })
        assertTrue(cache.get(source, emptySet(), hiddenOnly = true).isEmpty())
        assertSame(source, cache.get(source, emptySet()))
        assertEquals(listOf(3L, 2L, 1L), cache.get(source, emptySet(), PhotoSortOrder.DATE_DESC).map { it.contentId })
        assertEquals(listOf(1L, 2L, 3L), cache.get(source, emptySet(), PhotoSortOrder.DATE_ASC).map { it.contentId })
        val changed = listOf(source[0].copy(bestTimestamp = 20), source[1], source[2])
        assertEquals(1L, cache.get(changed, emptySet(), PhotoSortOrder.DATE_DESC).first().contentId)
        assertEquals(listOf(1L, 3L), cache.get(source, setOf("/Hidden")).map { it.contentId })
    }

    @Test fun cacheMatchesOriginalFilteringAndSortingForEveryOrder() {
        val source = (1L..300L).map { photo(it, if (it % 3L == 0L) "/Hidden/$it.jpg" else "/A/$it.jpg") }
        val cache = MediaPhotoListCache()
        val visible = source.filterNot { it.path.startsWith("/Hidden/") }
        for (order in PhotoSortOrder.entries) {
            assertEquals(sortMediaEntries(visible, order), cache.get(source, setOf("/Hidden"), order))
        }
    }
}
