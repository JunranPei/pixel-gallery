package com.pixel.gallery.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AlbumGroupColorTest {
    @Test fun legacyColorSurvivesRenameReorderAndRemoval() {
        val original = Gson().fromJson("""{"id":"legacy","name":"Old","paths":["/a"]}""", AlbumGroup::class.java)
        val renamed = original.copy(name = "New")
        val state = AlbumGrouping(listOf(AlbumGroup("other", "Other", colorIndex = 1), renamed))
        assertEquals(original.resolvedColorIndex, state.copy(groups = state.groups.reversed()).groups.first().resolvedColorIndex)
        assertEquals(original.resolvedColorIndex, state.copy(groups = listOf(renamed)).groups.single().resolvedColorIndex)
    }
    @Test fun allocatedColorPersistsAndAvoidsNeighbour() {
        var state = AlbumGrouping()
        repeat(20) { n ->
            val color = state.nextColorIndex()
            assertNotEquals(state.groups.lastOrNull()?.resolvedColorIndex ?: 2, color)
            val group = AlbumGroup(n.toString(), "Group $n", colorIndex = color)
            assertEquals(color, Gson().fromJson(Gson().toJson(group), AlbumGroup::class.java).resolvedColorIndex)
            state = state.copy(groups = state.groups + group)
        }
        assertEquals(setOf(0, 1, 2, 3), state.groups.map { it.resolvedColorIndex }.toSet())
    }
}
