package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LabelSummaryMemoryTest {
    private fun media(id: Long, time: Long = id) = MediaEntry(id, "content://media/$id", "/A/$id.jpg",
        "image/jpeg", 100, 100, 0, 100, 1, 1, bestTimestamp = time)

    private fun previous(labels: List<LabelEntry>, links: List<MediaLabelEntry>, media: List<MediaEntry>): List<LabelSummary> {
        val byId = media.associateBy { it.contentId }
        val byLabel = links.filter { it.contentId in byId }.groupBy { it.labelId }
        val order = compareBy<MediaEntry> { it.bestTimestamp }.thenBy { it.contentId }
        return labels.map { label ->
            val members = byLabel[label.id].orEmpty().mapNotNull { byId[it.contentId] }
            LabelSummary(label, members.size, members.maxWithOrNull(order),
                members.maxOfOrNull { it.chronologicalTimestamp() } ?: 0)
        }
    }

    @Test fun emptyAndDeletedLabelMembershipsDoNotReadTheLibrary() {
        val unreadable = object : AbstractList<MediaEntry>() {
            override val size: Int get() = error("Unused library read")
            override fun get(index: Int): MediaEntry = error("Unused library read")
        }
        val label = LabelEntry(2, "Trip", "trip")
        assertEquals(emptyList<LabelSummary>(), buildLabelSummaries(emptyList(), listOf(MediaLabelEntry(2, 1)), unreadable))
        assertEquals(listOf(LabelSummary(label, 0)), buildLabelSummaries(listOf(label), emptyList(), unreadable))
        assertEquals(listOf(LabelSummary(label, 0)), buildLabelSummaries(listOf(label), listOf(MediaLabelEntry(99, 1)), unreadable))
    }

    @Test fun sparseAndDenseMembershipsPreserveCountsCoversOrderAndMissingRows() {
        val random = Random(73641)
        val labels = (1L..5L).map { LabelEntry(it, "Label $it", "label $it") }
        repeat(100) {
            val media = (1L..100L).filter { random.nextBoolean() }.map { media(it, random.nextLong(1, 8)) }.shuffled(random)
            // Duplicate source IDs retain the original last-row-wins behavior.
            val rows = media + media.take(2).map { it.copy(bestTimestamp = 20) }
            val links = List(random.nextInt(0, 350)) { MediaLabelEntry(random.nextLong(1, 8), random.nextLong(1, 120)) }
            assertEquals(previous(labels, links, rows), buildLabelSummaries(labels, links, rows))
        }
    }

    @Test fun twentyFourEmptyWindowsAvoidFullLibraryMapAllocation() {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val api = Class.forName("com.sun.management.ThreadMXBean")
        api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val counter = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val rows = (1L..131_014L).map { media(it) }
        val labels = listOf(LabelEntry(1, "Favourites", "favourites", "FAVOURITES"))
        fun allocation(block: () -> Unit): Long {
            val id = Thread.currentThread().id
            val before = counter.invoke(bean, id) as Long
            block()
            return (counter.invoke(bean, id) as Long) - before
        }
        repeat(2) { previous(labels, emptyList(), rows); buildLabelSummaries(labels, emptyList(), rows) }
        val before = allocation { repeat(24) { assertEquals(0, previous(labels, emptyList(), rows).single().count) } }
        val after = allocation { repeat(24) { assertEquals(0, buildLabelSummaries(labels, emptyList(), rows).single().count) } }
        println("LABEL_SUMMARY_ALLOCATION records=131014 windows=24 before=$before after=$after")
        assertTrue("Empty label summaries must not allocate per library entry", after < before / 100)
    }
}
