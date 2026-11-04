package com.pixel.gallery.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AlbumOrderStateTest {
    private fun rows(names: List<String>, grouping: AlbumGrouping = AlbumGrouping()) =
        groupedAlbumRows(names.map { Album(it, "/$it", "", 1) }, grouping, emptyList())
    private fun names(rows: List<GroupedAlbumRow>) = rows.mapNotNull { it.album?.name }
    @Test fun pinsWinOverCustomAndOtherSortsWithoutOverwritingMemory() {
        val saved = AlbumGrouping().withOrder("albums") { AlbumOrderState(custom = listOf("album:/B", "album:/A", "album:/C"), pinned = listOf("album:/C")) }
        val source = rows(listOf("A","B","C"))
        assertEquals(listOf("C","B","A"), names(orderedAlbumRows(source,saved,AlbumSortOrder.CUSTOM)))
        assertEquals(listOf("C","A","B"), names(orderedAlbumRows(source,saved,AlbumSortOrder.NAME_ASC)))
        assertEquals(listOf("C","B","A"), names(orderedAlbumRows(source,saved,AlbumSortOrder.CUSTOM)))
    }
    @Test fun missingAlbumKeepsItsPositionAndNewAlbumsAppend() {
        var state = AlbumGrouping().rememberOrder(rows(listOf("B","A")))
        state = state.rememberOrder(rows(listOf("A","C")))
        assertEquals(listOf("B","A","C"), names(orderedAlbumRows(rows(listOf("A","B","C")),state,AlbumSortOrder.CUSTOM)))
    }
    @Test fun firstCustomUsesVisibleOrderAndLaterSwitchesPreserveIt() {
        var state = AlbumGrouping().rememberOrder(rows(listOf("A","B")))
        state = state.startCustom("albums", rows(listOf("B","A")))
        state = state.startCustom("albums", rows(listOf("A","B")))
        assertEquals(listOf("album:/B","album:/A"),state.orderState("albums").custom)
    }
    @Test fun groupsKeepIndependentPinsAndRules() {
        val state = AlbumGrouping(groups=listOf(AlbumGroup("g","Group",listOf("/A","/B"))))
            .withOrder("g") { AlbumOrderState(rule=AlbumSortOrder.NAME_DESC,pinned=listOf("album:/A")) }
        val result=orderedAlbumRows(rows(listOf("A","B","C"),state),state,AlbumSortOrder.NAME_DESC)
        assertEquals(listOf("A","B","C"),names(result))
        assertEquals("g",result.first { it.album?.name=="A" }.backgroundGroup)
    }
    @Test fun oldJsonAndSavedOrderRoundTrip() {
        val old=Gson().fromJson("{\"groups\":[],\"collapsedIds\":[]}",AlbumGrouping::class.java)
        assertTrue(old.orderState("albums").pinned.isEmpty())
        val saved=old.withOrder("g") { AlbumOrderState(AlbumSortOrder.CUSTOM,true,listOf("label:3"),listOf("label:3")) }
        assertEquals(saved,Gson().fromJson(Gson().toJson(saved),AlbumGrouping::class.java))
    }

    @Test fun groupDragStaysWithinTypeAndMovesWholeExpandedBlock() {
        val groups = listOf(AlbumGroup("a","A",listOf("/A")), AlbumGroup("b","B",listOf("/B")),
            AlbumGroup("l1","L1",labelIds=emptyList()), AlbumGroup("l2","L2",labelIds=emptyList()))
        val initial = AlbumGrouping(groups=groups)
        val source = rows(listOf("A","B"),initial)
        val moved = initial.moveGroup(source,"group:a","album:/B")
        val ordered = orderedAlbumRows(source,moved,AlbumSortOrder.NAME_ASC)
        assertEquals(listOf("group:l1","group:l2","group:b","album:/B","group:a","album:/A","system:trash"),ordered.map { it.key })
        assertEquals(moved,moved.moveGroup(ordered,"group:a","group:l1"))
        assertEquals(moved,moved.moveGroup(ordered,"group:l1","album:/A"))
        val labelsMoved=moved.moveGroup(ordered,"group:l1","group:l2")
        assertEquals(listOf("group:l2","group:l1","group:b","group:a"),orderedAlbumRows(source,labelsMoved,AlbumSortOrder.DATE_DESC).filter { it.group!=null }.map { it.key })
        assertEquals(labelsMoved,Gson().fromJson(Gson().toJson(labelsMoved),AlbumGrouping::class.java))
    }
    @Test fun groupOrderIgnoresOldRuleAndPinAndAppendsNewGroups() {
        val initial=AlbumGrouping(groups=listOf(AlbumGroup("a","A"),AlbumGroup("b","B")))
            .withOrder("groups:albums") { AlbumOrderState(rule=AlbumSortOrder.NAME_ASC,custom=listOf("group:b","group:a"),pinned=listOf("group:a")) }
        val extended=initial.copy(groups=initial.groups+AlbumGroup("c","C"))
        assertEquals(listOf("group:b","group:a","group:c"),orderedAlbumRows(rows(emptyList(),extended),extended,AlbumSortOrder.NAME_DESC).filter { it.group!=null }.map { it.key })
    }
}
