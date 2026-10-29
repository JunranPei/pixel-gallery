package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.ui.viewmodel.filterVisibleLabelMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LabelsTest {
    @Test fun `multi selection exposes only shared labels and recomputes for each group`() {
        val links = listOf(MediaLabelEntry(1, 10), MediaLabelEntry(2, 10), MediaLabelEntry(1, 20),
            MediaLabelEntry(3, 99), MediaLabelEntry(2, 10))
        assertEquals(setOf(1L), commonLabelIds(setOf(10L, 20L), links))
        assertEquals(setOf(1L, 2L), commonLabelIds(setOf(10L), links))
        assertEquals(setOf(1L), commonLabelIds(setOf(20L), links))
        assertTrue(commonLabelIds(emptySet(), links).isEmpty())
        assertTrue(commonLabelIds(setOf(10L, 88L), links).isEmpty())
    }
    @Test
    fun `selection distinguishes none some and all without unrelated files`() {
        val links = listOf(
            MediaLabelEntry(1, 10), MediaLabelEntry(1, 20),
            MediaLabelEntry(2, 10), MediaLabelEntry(2, 30),
            MediaLabelEntry(3, 99)
        )
        val selected = setOf(10L, 20L)
        assertEquals(LabelSelection.ALL, labelSelection(1, selected, links))
        assertEquals(LabelSelection.SOME, labelSelection(2, selected, links))
        assertEquals(LabelSelection.NONE, labelSelection(3, selected, links))
        assertEquals(LabelSelection.NONE, labelSelection(1, emptySet(), links))
    }

    @Test
    fun `duplicate observations cannot turn a mixed selection into all`() {
        val links = listOf(MediaLabelEntry(1, 10), MediaLabelEntry(1, 10))
        assertEquals(LabelSelection.SOME, labelSelection(1, setOf(10L, 20L), links))
    }

    @Test
    fun `single file uses on or off and retains independent labels`() {
        val links = listOf(MediaLabelEntry(1, 10), MediaLabelEntry(2, 10))
        assertEquals(LabelSelection.ALL, labelSelection(1, setOf(10L), links))
        assertEquals(LabelSelection.ALL, labelSelection(2, setOf(10L), links))
        assertEquals(LabelSelection.NONE, labelSelection(3, setOf(10L), links))
    }

    @Test
    fun `name identity handles surrounding space unicode width and case`() {
        assertEquals(normalizeLabelName("Travel"), normalizeLabelName("  ＴＲＡＶＥＬ  "))
        assertEquals("旅行 / 工作", normalizeLabelName(" 旅行 / 工作 "))
        assertFalse(normalizeLabelName("a/b") == normalizeLabelName("ab"))
    }

    @Test
    fun `labels are not filesystem names but reject empty or control text`() {
        assertTrue(isValidLabelName("旅行 / 工作"))
        assertTrue(isValidLabelName("a".repeat(60)))
        assertFalse(isValidLabelName("a".repeat(61)))
        assertFalse(isValidLabelName(" \t "))
        assertFalse(isValidLabelName("travel\nwork"))
    }

    @Test
    fun `label cover is the newest visible member independent of media order`() {
        val oldest = media(10, 100)
        val newest = media(20, 300)
        val middle = media(30, 200)
        val summary = buildLabelSummaries(
            listOf(LabelEntry(2, "Travel", "travel")),
            listOf(MediaLabelEntry(2, 10), MediaLabelEntry(2, 20), MediaLabelEntry(2, 30), MediaLabelEntry(2, 99)),
            listOf(newest, oldest, middle)
        ).single()

        assertEquals(3, summary.count)
        assertEquals(newest, summary.cover)
        assertEquals(300L, summary.lastModified)

        // Hiding/excluding/trashing a member removes it from this already-filtered input.
        val afterHidden = buildLabelSummaries(
            listOf(summary.label),
            listOf(MediaLabelEntry(2, 10), MediaLabelEntry(2, 20), MediaLabelEntry(2, 30), MediaLabelEntry(2, 99)),
            listOf(oldest, middle)
        ).single()
        assertEquals(2, afterHidden.count)
        assertEquals(middle, afterHidden.cover)
    }

    @Test
    fun `locked media stays linked but is filtered from label summaries until returned`() {
        val locked = media(99, 500)
        val links = listOf(MediaLabelEntry(2, 99), MediaLabelEntry(1, 99))
        val label = LabelEntry(2, "Secret", "secret")

        val visibleWhileLocked = filterVisibleLabelMedia(listOf(locked), emptySet(), setOf(99L))
        val hiddenWhileLocked = buildLabelSummaries(listOf(label), links, visibleWhileLocked).single()
        assertEquals(0, hiddenWhileLocked.count)
        assertTrue(links.any { it.labelId == 1L && it.contentId == 99L })
        assertTrue(links.any { it.labelId == 2L && it.contentId == 99L })

        val returnedToLibrary = filterVisibleLabelMedia(listOf(locked), emptySet(), emptySet())
        val visibleAfterReturn = buildLabelSummaries(listOf(label), links, returnedToLibrary).single()
        assertEquals(1, visibleAfterReturn.count)
        assertEquals(locked, visibleAfterReturn.cover)
    }

    @Test
    fun `label summaries use latest member time and shared sorter supports date direction`() {
        val first = media(10, 100).copy(sourceDateTakenMillis = 200L, dateModifiedMillis = 900L)
        val second = media(20, 300).copy(sourceDateTakenMillis = 600L, dateModifiedMillis = 100L)
        val summaries = buildLabelSummaries(
            listOf(
                LabelEntry(2, "Older", "older"),
                LabelEntry(3, "Newer", "newer"),
                LabelEntry(4, "Empty", "empty"),
            ),
            listOf(MediaLabelEntry(2, 10), MediaLabelEntry(3, 20)),
            listOf(first, second),
        ).associateBy { it.label.id }

        assertEquals(200L, summaries.getValue(2).lastModified)
        assertEquals(600L, summaries.getValue(3).lastModified)
        assertEquals(0L, summaries.getValue(4).lastModified)
        assertEquals(listOf(3L, 2L, 4L), AlbumSorter.shared.sortLabels(summaries.values.toList(), AlbumSortOrder.DATE_DESC).map { it.label.id })
        assertEquals(listOf(4L, 2L, 3L), AlbumSorter.shared.sortLabels(summaries.values.toList(), AlbumSortOrder.DATE_ASC).map { it.label.id })
    }

    @Test
    fun `album sorter maps shared criteria and migrates old custom preference to name`() {
        assertEquals(AlbumSortOrder.DATE_DESC, AlbumSortOrder.fromSelection("DATE", "DESC"))
        assertEquals(AlbumSortOrder.COUNT_ASC, AlbumSortOrder.fromSelection("COUNT", "ASC"))
        assertEquals(AlbumSortOrder.NAME_ASC, AlbumSortOrder.fromStored("CUSTOM"))
    }

    @Test
    fun `one file can cover multiple labels while an empty label has no cover`() {
        val photo = media(10, 100)
        val summaries = buildLabelSummaries(
            listOf(LabelEntry(2, "Travel", "travel"), LabelEntry(3, "Work", "work"), LabelEntry(4, "Empty", "empty")),
            listOf(MediaLabelEntry(2, 10), MediaLabelEntry(3, 10), MediaLabelEntry(4, 99)),
            listOf(photo)
        )

        assertEquals(listOf(1, 1, 0), summaries.map { it.count })
        assertEquals(photo, summaries[0].cover)
        assertEquals(photo, summaries[1].cover)
        assertNull(summaries[2].cover)
    }

    private fun media(id: Long, timestamp: Long) = MediaEntry(
        contentId = id,
        uri = "content://media/external/images/media/$id",
        path = "/storage/emulated/0/Pictures/$id.jpg",
        sourceMimeType = "image/jpeg",
        width = 100,
        height = 100,
        sourceRotationDegrees = 0,
        sizeBytes = 100,
        dateAddedSecs = timestamp / 1000,
        dateModifiedMillis = timestamp,
        bestTimestamp = timestamp
    )
}
