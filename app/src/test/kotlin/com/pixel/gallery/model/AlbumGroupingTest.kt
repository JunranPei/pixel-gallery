package com.pixel.gallery.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AlbumGroupingTest {
    private val albums = listOf("D", "B", "A", "C").map { Album(it, "/$it", "", 1) }
    private val grouping = AlbumGrouping(listOf(AlbumGroup("one", "First", listOf("/A", "/B")), AlbumGroup("two", "Second", listOf("/C"))))

    @Test fun groupsFirstMembersKeepAlbumOrderAndTrashLast() {
        val rows = groupedAlbumRows(albums, grouping, emptyList())
        assertEquals(listOf("group:system:labels", "group:one", "album:/B", "album:/A", "group:two", "album:/C", "album:/D", "system:trash"), rows.map { it.key })
        assertEquals("one", rows[1].backgroundGroup)
        assertNull(rows[6].backgroundGroup)
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
    }
    @Test fun closingOneGroupDoesNotCloseOthersOrLeakMembersIntoUngrouped() {
        val rows = groupedAlbumRows(albums, grouping.copy(collapsedIds = setOf("one")), emptyList())
        assertFalse(rows.any { it.album?.path in listOf("/A", "/B") })
        assertTrue(rows.any { it.album?.path == "/C" && it.backgroundGroup == "two" })
    }
    @Test fun searchFindsCollapsedMembersWithoutGroupOrTrashTiles() {
        val rows = groupedAlbumRows(albums.filter { it.path == "/A" }, grouping.copy(collapsedIds = setOf("one")), emptyList(), search = true)
        assertEquals(listOf("album:/A"), rows.map { it.key })
    }
    @Test fun moveAndRemoveKeepSingleMembershipAndLeaveUnrelatedPathsIntact() {
        val moved = grouping.assign(setOf("/A", "/D"), "two")
        assertEquals(listOf("/B"), moved.groups[0].paths)
        assertEquals(listOf("/C", "/A", "/D"), moved.groups[1].paths)
        assertEquals(listOf("/C", "/D"), moved.assign(setOf("/A"), null).groups[1].paths)
        assertTrue(runCatching { moved.assign(setOf("/A"), "deleted") }.isFailure)
    }
    @Test fun temporarilyMissingAlbumKeepsMembershipAndReturnsToItsGroup() {
        val filtered = groupedAlbumRows(albums.filterNot { it.path == "/A" }, grouping, emptyList())
        assertFalse(filtered.any { it.album?.path == "/A" })
        assertTrue(groupedAlbumRows(albums, grouping, emptyList()).any { it.album?.path == "/A" && it.backgroundGroup == "one" })
    }
    @Test fun renameAndJsonRoundtripKeepGroupsOrderMembershipAndOpenState() {
        val before = grouping.copy(collapsedIds = setOf("two")).remap("/A", "/Renamed")
        val restored = Gson().fromJson(Gson().toJson(before), AlbumGrouping::class.java)
        assertEquals(before, restored)
        assertEquals(listOf("/Renamed", "/B"), restored.groups.first().paths)
        assertEquals(listOf("one", "two"), restored.groups.map { it.id })
    }
    @Test fun newUngroupedAlbumUsesCallerSortAndEmptyGroupsRemainReachable() {
        val rows = groupedAlbumRows(emptyList(), grouping, emptyList())
        assertEquals(listOf("group:system:labels", "group:one", "group:two", "system:trash"), rows.map { it.key })
        val added = groupedAlbumRows(listOf(Album("New", "/new", "", 1)) + albums, grouping, emptyList())
        assertEquals(listOf("/new", "/D"), added.filter { it.album != null && it.backgroundGroup == null }.map { it.album!!.path })
    }
}
