package com.pixel.gallery.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AlbumGroupingTest {
    @Test fun groupingEntryOnlyAcceptsNonemptyHomogeneousSelection() {
        assertTrue(canGroupAlbumSelection(3, 0))
        assertTrue(canGroupAlbumSelection(0, 5))
        assertFalse(canGroupAlbumSelection(1, 1))
        assertFalse(canGroupAlbumSelection(3, 5))
        assertFalse(canGroupAlbumSelection(0, 0))
    }

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

    @Test fun legacyJsonDoesNotBecomeLabelGroups() {
        val state = Gson().fromJson("""{"groups":[{"id":"old","name":"Old","paths":["/A"]}],"collapsedIds":[]}""", AlbumGrouping::class.java)
        assertFalse(state.groups.single().isLabelGroup)
        assertEquals(listOf("/A"), state.groups.single().paths)
    }

    @Test fun labelMembershipCannotMixWithOrdinaryAlbums() {
        val state = grouping.copy(groups = grouping.groups + AlbumGroup("tags", "Tags", labelIds = emptyList()))
        assertTrue(runCatching { state.assign(setOf("/D"), "tags") }.isFailure)
        assertTrue(runCatching { state.assignLabels(setOf(2L), "one") }.isFailure)
        val moved = state.assignLabels(setOf(2L, 3L), "tags")
        assertEquals(listOf(2L, 3L), moved.groups.last().labelIds)
        assertTrue(moved.groups.last().paths.isEmpty())
        assertEquals(grouping.groups, moved.groups.take(2))
        assertEquals(moved, Gson().fromJson(Gson().toJson(moved), AlbumGrouping::class.java))
    }

    @Test fun labelsMoveAcrossGroupsAndReturnToDefaultWithoutDuplicateTiles() {
        val labels = (2L..5L).map { LabelSummary(com.pixel.gallery.data.local.entity.LabelEntry(it, "Label $it", "label $it"), 0) }
        val state = AlbumGrouping(listOf(AlbumGroup("tags", "Tags", labelIds = listOf(2L,3L)),
            AlbumGroup("other", "Other", labelIds = listOf(4L))), emptySet())
        val moved = state.assignLabels(setOf(3L), "other")
        assertEquals(listOf(2L), moved.groups[0].labelIds)
        assertEquals(listOf(4L,3L), moved.groups[1].labelIds)
        val rows = groupedAlbumRows(albums, moved, labels)
        assertEquals(listOf(5L,2L,3L,4L), rows.mapNotNull { it.label?.label?.id })
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals("other", moved.labelGroupId(3L))
        assertEquals(LABELS_ALBUM_GROUP, moved.assignLabels(setOf(3L), null).labelGroupId(3L))
        val closed = groupedAlbumRows(albums, moved.copy(collapsedIds = setOf("other")), labels)
        assertFalse(closed.any { it.label?.label?.id in listOf(3L,4L) })
        val dissolved = moved.copy(groups = moved.groups.filterNot { it.id == "other" })
        assertEquals(LABELS_ALBUM_GROUP, dissolved.labelGroupId(3L))
        assertEquals(4, groupedAlbumRows(albums, dissolved, labels).count { it.label != null })
    }

    @Test fun favouritesAreFirstOnlyWhenNonemptyAndNeverGrouped() {
        val fav = LabelSummary(com.pixel.gallery.data.local.entity.LabelEntry(1L, "Favourites", "favourites", "FAVOURITES"), 2)
        val legacy = AlbumGrouping(listOf(AlbumGroup("old", "Old", labelIds = listOf(1L,2L))), emptySet())
        val rows = groupedAlbumRows(albums, legacy, listOf(fav))
        assertEquals(FAVOURITES_ALBUM_ENTRY, rows.first().key)
        assertNull(rows.first().backgroundGroup)
        assertEquals(1, rows.count { it.favourite != null })
        assertFalse(rows.any { it.label?.label?.id == 1L })
        assertEquals(FAVOURITES_ALBUM_ENTRY, legacy.labelGroupId(1L))
        assertFalse(groupedAlbumRows(albums, legacy, listOf(fav.copy(count = 0))).any { it.favourite != null })
        assertTrue(runCatching { legacy.assignLabels(setOf(1L), "old") }.isFailure)
        assertTrue(runCatching { legacy.assignLabels(setOf(1L,2L), "old") }.isFailure)
    }
}
