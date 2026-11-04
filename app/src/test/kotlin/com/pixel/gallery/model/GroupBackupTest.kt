package com.pixel.gallery.model

import org.junit.Assert.*
import org.junit.Test

class GroupBackupTest {
    private val groups = listOf(
        BackupGroup("ordinary", "Travel", listOf("/storage/emulated/0/DCIM/Trip"), colorIndex = 2, illustration = 21, collapsed = true),
        BackupGroup("labels", "People", labelKeys = listOf("person"), illustration = 49),
    )
    private fun backup() = LabelBackup(exportedAt = "now", labels = listOf(BackupLabel("person", "Family")), media = emptyList(), groups = groups)
    @Test fun roundTripRestoresTypesOrderArtAndExpansionWithoutMedia() {
        val source = backup()
        val decoded = LabelBackupJson.read(LabelBackupJson.gson.toJson(source).reader())
        assertEquals(source, decoded)
        val restored = mergeBackupGroups(AlbumGrouping(), decoded.groups!!, mapOf("person" to 87L))
        assertEquals(listOf("ordinary", "labels"), restored.grouping.groups.map { it.id })
        assertEquals(listOf(87L), restored.grouping.groups[1].labelIds)
        assertEquals(groups[0].paths, restored.grouping.groups[0].paths)
        assertTrue("ordinary" in restored.grouping.collapsedIds)
        assertEquals(49, restored.illustrations["labels"])
    }
    @Test fun olderLabelOnlyBackupRemainsReadable() {
        val old = backup().copy(version = 1, groups = null)
        assertEquals(old, LabelBackupJson.read(LabelBackupJson.gson.toJson(old).reader()))
    }
    @Test fun repeatingImportIsIdempotentEvenWithConflictingGroupType() {
        val current = AlbumGrouping(listOf(AlbumGroup("labels", "People", listOf("/old"))))
        val first = mergeBackupGroups(current, groups, mapOf("person" to 87L)).grouping
        assertEquals(first, mergeBackupGroups(first, groups, mapOf("person" to 87L)).grouping)
        assertEquals(listOf("/old"), first.groups.first().paths)
    }
    @Test fun existingAssignmentsAreNotStolen() {
        val current = AlbumGrouping(listOf(AlbumGroup("keep", "Existing", groups[0].paths), AlbumGroup("keep-label", "Old labels", labelIds = listOf(87L))))
        val result = mergeBackupGroups(current, groups, mapOf("person" to 87L)).grouping
        assertEquals(current.groups, result.groups.take(2))
        assertTrue(result.groups.last().labelIds!!.isEmpty())
        assertTrue(result.groups[2].paths.isEmpty())
    }
    @Test fun rejectsMixedTypesDanglingLabelsDuplicateMembersAndInvalidArt() {
        listOf(groups + groups[0], listOf(groups[1].copy(paths = listOf("/mixed"))),
            listOf(groups[1].copy(labelKeys = listOf("missing"))), listOf(groups[0].copy(illustration = 50)),
            listOf(groups[0], groups[0].copy(id = "other"))).forEach { invalid ->
            assertTrue(runCatching { LabelBackupJson.read(LabelBackupJson.gson.toJson(backup().copy(groups = invalid)).reader()) }.isFailure)
        }
    }
    @Test fun disabledGroupsExposeAllAlbumsWithoutMutatingSavedState() {
        val saved = AlbumGrouping(listOf(AlbumGroup("g", "G", listOf("/a"))), setOf("g"))
        val albums = listOf(Album("A", "/a", "", 1), Album("B", "/b", "", 1))
        val flat = groupedAlbumRows(albums, saved, emptyList(), groupingEnabled = false)
        assertEquals(listOf("album:/a", "album:/b", "system:trash"), flat.map { it.key })
        assertTrue(flat.all { it.backgroundGroup == null })
        assertFalse(groupedAlbumRows(albums, saved, emptyList()).any { it.album?.path == "/a" })
    }
}
