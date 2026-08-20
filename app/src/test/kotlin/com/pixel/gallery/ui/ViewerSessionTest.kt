package com.pixel.gallery.ui

import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ViewerSessionTest {

    @Test
    fun `live reorder does not change viewer page identity`() {
        val original = listOf(entry(1, "/old/one.jpg"), entry(2, "/old/two.jpg"))
        val latest = listOf(
            entry(3, "/new/three.jpg"),
            entry(2, "/new/two.jpg"),
        )

        val reconciled = reconcileViewerSessionPhotos(original, latest)

        assertEquals(listOf(1L, 2L), reconciled.map { it.contentId })
        assertEquals("/old/one.jpg", reconciled[0].path)
        assertEquals("/new/two.jpg", reconciled[1].path)
    }

    @Test
    fun `unchanged viewer session reuses the same list`() {
        val current = listOf(entry(1, "/one.jpg"))

        assertSame(current, reconcileViewerSessionPhotos(current, current))
    }

    private fun entry(id: Long, path: String) = MediaEntry(
        contentId = id,
        uri = "content://media/$id",
        path = path,
        sourceMimeType = "image/jpeg",
        width = 100,
        height = 100,
        sourceRotationDegrees = 0,
        sizeBytes = 1,
        dateAddedSecs = 1,
        dateModifiedMillis = 1,
        bestTimestamp = id,
    )
}
