package com.pixel.gallery.ui.home

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.ui.quicksort.quickSortRangeSelection
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PhotoGridSelectionTest {
    private fun photo(id: Long) = GridItem.Photo(MediaEntry(id, "content://media/$id", "/A/$id.jpg",
        "image/jpeg", 100, 100, 0, 100, 1, 1, bestTimestamp = id))

    @Test fun dateSelectionMatchesPreviousBehaviorIncludingReservedAndAdjacentHeaders() {
        val items = listOf(GridItem.Header("Empty", 0), GridItem.Header("Today", 1), photo(1000),
            photo(1001), photo(1002), GridItem.Header("Yesterday", 2), photo(1003), photo(1004),
            GridItem.Header("Last empty", 3))
        val random = Random(73642)
        for (header in listOf(0, 1, 5, 8)) {
            val range = PhotoGridHeaderRange(items, header)
            repeat(100) {
                val selected = (1000L..1006L).filter { random.nextBoolean() }.toSet()
                val reserved = (1000L..1006L).filter { random.nextBoolean() }.toSet()
                val ids = items.drop(header + 1).takeWhile { it !is GridItem.Header }
                    .map { (it as GridItem.Photo).entry.contentId }.filterNot { it in reserved }
                val wasSelected = ids.isNotEmpty() && ids.all { it in selected }
                assertEquals(wasSelected, range.isSelected(selected, reserved))
                val expected = if (wasSelected) selected - ids.toSet() - reserved
                    else (selected + ids.toSet()) - reserved
                assertEquals(expected, range.toggleSelection(selected, reserved))
            }
        }
    }

    @Test fun quickSortIdsStayLazyAndRangeSelectionSkipsHeadersAndReservedPhotos() {
        val source = listOf(GridItem.Header("A", 1), photo(1000), photo(1001),
            GridItem.Header("B", 2), photo(1002), photo(1003))
        var reads = 0
        val observed = object : AbstractList<GridItem>() {
            override val size: Int get() = source.size
            override fun get(index: Int): GridItem { reads++; return source[index] }
        }
        val ids = PhotoGridItemIds(observed)
        assertEquals(source.size, ids.size)
        assertEquals(0, reads)
        assertEquals(1002L, ids[4])
        assertEquals(1, reads)
        for (start in -1..source.size) for (end in -1..source.size) {
            val initial = setOf(1003L, 1004L)
            val reserved = setOf(1001L, 1003L)
            assertEquals(quickSortRangeSelection(initial, source.map { (it as? GridItem.Photo)?.entry?.contentId },
                start, end, reserved), quickSortRangeSelection(initial, ids, start, end, reserved))
        }
    }

    @Test fun fiftyIdleHeadersDoNotAllocateAListOfIdsPerWindow() {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val api = Class.forName("com.sun.management.ThreadMXBean")
        api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val counter = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val items = listOf(GridItem.Header("Today", 1)) + List(131_014) { photo(1000L + it) }
        fun allocation(block: () -> Unit): Long {
            val id = Thread.currentThread().id
            val before = counter.invoke(bean, id) as Long
            block()
            return (counter.invoke(bean, id) as Long) - before
        }
        repeat(5) { assertFalse(PhotoGridHeaderRange(items, 0).isSelected(emptySet(), emptySet())) }
        val before = allocation { repeat(50) {
            val photos = mutableListOf<Long>()
            for (index in 1 until items.size) {
                val next = items[index]
                if (next is GridItem.Header) break
                if (next is GridItem.Photo) photos.add(next.entry.contentId)
            }
            val ids = photos.filterNot { it in emptySet<Long>() }
            assertEquals(131_014, ids.size)
            assertFalse(ids.all { it in emptySet<Long>() })
        } }
        val ranges = mutableListOf<PhotoGridHeaderRange>()
        val after = allocation { repeat(50) {
            val range = PhotoGridHeaderRange(items, 0)
            ranges.add(range)
            assertFalse(range.isSelected(emptySet(), emptySet()))
        } }
        println("DATE_HEADER_ALLOCATION records=131014 windows=50 before=$before after=$after")
        assertTrue("Idle headers must not allocate per photo", after < before / 100)
        assertEquals(131_014, ranges.last().toggleSelection(emptySet(), emptySet()).size)
    }
}
