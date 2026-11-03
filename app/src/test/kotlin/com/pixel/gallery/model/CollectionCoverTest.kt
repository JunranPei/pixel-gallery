package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.*
import org.junit.Test

class CollectionCoverTest {
    private fun media(id: Long, path: String, trash: Boolean = false) = MediaEntry(
        id, "content://media/$id", path, "image/jpeg", 100, 100, 0, 100, 1, 1, isTrashed = trash)
    @Test fun manualCoverCannotCrossCollectionBoundaryOrUseRemovedMedia() {
        val first = media(1, "/Pictures/A/one.jpg")
        val other = media(2, "/Pictures/AB/two.jpg")
        val album = Album("A", "/Pictures/A", first.uri, 1)
        assertEquals(first.uri, applyAlbumCovers(listOf(album), listOf(first, other), mapOf(albumCoverKey(album.path) to other.uri)).single().coverUri)
        assertNull(resolveCollectionCover(other.uri, listOf(first)))
        assertNull(resolveCollectionCover(first.uri, listOf(first.copy(isTrashed = true))))
    }
    @Test fun explicitCoverSurvivesSortButKeepsAlbumMetadata() {
        val a = media(1, "/Pictures/A/one.jpg")
        val b = media(2, "/Pictures/A/two.jpg")
        val album = Album("A", "/Pictures/A", b.uri, 2, 90)
        val covers = mapOf(albumCoverKey(album.path) to a.uri)
        assertEquals(album.copy(coverUri = a.uri), applyAlbumCovers(listOf(album), listOf(b, a), covers).single())
        assertEquals(album, applyAlbumCovers(listOf(album), listOf(b), covers).single())
        assertEquals(album, applyAlbumCovers(listOf(album), listOf(a, b), emptyMap()).single())
    }
    @Test fun renameMovesOnlyAlbumKeysAndRetainsStableUri() {
        val covers = mapOf(albumCoverKey("/A") to "uri1", albumCoverKey("/AB") to "uri2", labelCoverKey(1) to "uri3", groupCoverKey("g") to "uri4")
        val moved = remapCollectionCovers(covers, "/A", "/Renamed")
        assertEquals("uri1", moved[albumCoverKey("/Renamed")])
        assertFalse(moved.containsKey(albumCoverKey("/A")))
        assertEquals("uri2", moved[albumCoverKey("/AB")])
        assertEquals("uri3", moved[labelCoverKey(1)])
        assertEquals("uri4", moved[groupCoverKey("g")])
    }
}
