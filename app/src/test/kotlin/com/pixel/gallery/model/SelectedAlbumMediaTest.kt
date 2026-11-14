package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectedAlbumMediaTest {
    private fun media(id: Long, path: String) = MediaEntry(id, "content://test/$id", path,
        "image/jpeg", 10, 10, 0, 10, 0, 0)

    @Test fun emptySelectionDoesNotReadLibrary() = runBlocking {
        val unreadable = object : AbstractList<MediaEntry>() {
            override val size: Int get() = error("Library must not be read")
            override fun get(index: Int): MediaEntry = error("Library must not be read")
        }
        assertTrue(collectSelectedAlbumMedia(unreadable, emptySet(), emptySet(), false).isEmpty())
    }

    @Test fun ordinaryAlbumsMatchExactParentAndKeepSourceOrder() = runBlocking {
        val source = listOf(media(1, "/Pictures/A/one.jpg"), media(2, "/Pictures/A/sub/two.jpg"),
            media(3, "/Pictures/AB/three.jpg"), media(4, "/Pictures/B/four.jpg"))
        assertEquals(listOf(1L, 4L), collectSelectedAlbumMedia(source,
            setOf("/Pictures/A", "/Pictures/B"), emptySet(), false).map { it.contentId })
    }

    @Test fun albumsAndLabelsFormUnionWithoutAddingDuplicateEntries() = runBlocking {
        val source = listOf(media(1, "/A/one.jpg"), media(2, "/B/two.jpg"), media(3, "/C/three.jpg"))
        assertEquals(listOf(1L, 2L), collectSelectedAlbumMedia(source,
            setOf("/A"), setOf(1L, 2L, 999L), false).map { it.contentId })
        assertEquals(listOf(2L), collectSelectedAlbumMedia(source,
            emptySet(), setOf(2L), false).map { it.contentId })
    }

    @Test fun hiddenSelectionIncludesChildrenWithoutPrefixCollisions() = runBlocking {
        val source = listOf(media(1, "/A/one.jpg"), media(2, "/A/sub/two.jpg"), media(3, "/AB/three.jpg"))
        assertEquals(listOf(1L, 2L), collectSelectedAlbumMedia(source,
            setOf("/A"), setOf(3L), true).map { it.contentId })
    }

    @Test fun cancelledCollectionDoesNotContinueScanning() = runBlocking {
        val job = Job().apply { cancel() }
        try {
            withContext(job) { collectSelectedAlbumMedia(listOf(media(1, "/A/a.jpg")), setOf("/A"), emptySet(), false) }
            fail("Cancelled scan continued")
        } catch (_: CancellationException) { }
    }
}
