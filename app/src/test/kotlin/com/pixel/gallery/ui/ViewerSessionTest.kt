package com.pixel.gallery.ui

import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import com.pixel.gallery.model.TransferReceipt
import kotlin.random.Random

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

    @Test
    fun `unrelated history does not copy a hundred thousand photo session`() {
        val current = List(100_000) { entry(it.toLong(), "/gallery/$it.jpg") }
        val history = List(64) { move(-it.toLong() - 1, it.toLong(), "/old/$it.jpg", "/moved/$it.jpg") }

        assertSame(current, remapMovedViewerPhotos(current, emptyList()))
        assertSame(current, remapMovedViewerPhotos(current, history))
        assertSame(current, refreshViewerSessionPhotos(current, current, history))
    }

    @Test
    fun `receipt replay follows journal order for cycles and repeated source keys`() {
        val current = listOf(entry(1, "/a.jpg"))
        val history = listOf(
            move(2, 3, "/b.jpg", "/too-early.jpg"),
            move(1, 2, "/a.jpg", "/b.jpg"),
            move(2, 1, "/b.jpg", "/a.jpg"),
            move(1, 4, "/a.jpg", "/final.jpg"),
        )

        assertEquals(4L, remapMovedViewerPhotos(current, history).single().contentId)
        assertEquals("/final.jpg", remapMovedViewerPhotos(current, history).single().path)
    }

    @Test
    fun `indexed replay matches ordered replay over mixed move histories`() {
        val random = Random(5481)
        repeat(100) {
            val current = List(20) { entry(it.toLong(), "/${random.nextInt(5)}.jpg") }
            val history = List(100) {
                move(random.nextLong(20), random.nextLong(20),
                    "/${random.nextInt(5)}.jpg", "/${random.nextInt(5)}.jpg")
            }
            val expected = current.map { original ->
                history.fold(original) { photo, receipt ->
                    if (photo.contentId == receipt.sourceId && photo.path == receipt.sourcePath) {
                        photo.copy(contentId = receipt.targetId, uri = receipt.targetUri,
                            path = receipt.targetPath, dateModifiedMillis = receipt.modifiedMillis,
                            sourceDateTakenMillis = receipt.dateTakenMillis)
                    } else photo
                }
            }

            assertEquals(expected, remapMovedViewerPhotos(current, history))
        }
    }

    @Test
    fun `session refresh reconciles moved IDs against live metadata`() {
        val current = listOf(entry(1, "/a.jpg"), entry(2, "/other.jpg"))
        val latest = listOf(entry(3, "/new.jpg").copy(width = 500), current[1])

        val refreshed = refreshViewerSessionPhotos(current, latest,
            listOf(move(1, 3, "/a.jpg", "/new.jpg")))

        assertEquals(listOf(3L, 2L), refreshed.map { it.contentId })
        assertEquals(500, refreshed.first().width)
        assertSame(current[1], refreshed[1])
    }

    private fun move(from: Long, to: Long, source: String, target: String) = TransferReceipt(
        from, "content://media/$from", source, to, "content://media/$to", target,
        1234L, 567L, true, true, true, true,
    )

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
