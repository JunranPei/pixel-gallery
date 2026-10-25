package com.pixel.gallery.model

import org.junit.Assert.*
import org.junit.Test

class AlbumCreationTest {
    private val parent = TransferDestination("pictures", "Pictures", "/Pictures/")

    @Test fun `creation accepts a trimmed name but not a path or hidden directory`() {
        assertTrue(canCreateAlbumFromQuery("  Travel  ", parent, emptyList()))
        for (name in listOf("", " ", ".", "..", ".hidden", "a/b", "a\\b", "a\n", "a".repeat(256))) {
            assertFalse(name, canCreateAlbumFromQuery(name, parent, emptyList()))
        }
    }

    @Test fun `only a duplicate in the actual parent suppresses create`() {
        val existing = TransferDestination("travel", "Travel", "/Pictures/Travel/")
        assertFalse(canCreateAlbumFromQuery(" travel ", parent, listOf(existing)))
        assertTrue(canCreateAlbumFromQuery("Travel", parent, listOf(existing.copy(path = "/Other/Travel"))))
    }
}
