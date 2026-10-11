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

    @Test
    fun `move receipts follow replaced IDs without changing page order`() {
        val current = listOf(entry(1, "/source/one.jpg"), entry(2, "/source/two.jpg"))
        fun receipt(from: Long, to: Long, source: String, target: String) = com.pixel.gallery.model.TransferReceipt(
            from, "content://media/$from", source, to, "content://media/$to", target,
            1234L, 567L, true, true, true, true,
        )
        val moved = remapMovedViewerPhotos(current, listOf(
            receipt(1, 10, "/source/one.jpg", "/target/one.jpg"),
            receipt(10, 10, "/target/one.jpg", "/final/one.jpg"),
            receipt(2, 20, "/unrelated/two.jpg", "/ignore/two.jpg"),
        ))
        assertEquals(listOf(10L, 2L), moved.map { it.contentId })
        assertEquals(listOf("/final/one.jpg", "/source/two.jpg"), moved.map { it.path })
        assertEquals("content://media/10", moved.first().uri)
        assertEquals(1234L, moved.first().dateModifiedMillis)
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
