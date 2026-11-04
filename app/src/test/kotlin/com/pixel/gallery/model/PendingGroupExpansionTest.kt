package com.pixel.gallery.model
import org.junit.Assert.*
import org.junit.Test
class PendingGroupExpansionTest {
    @Test fun expandsBeforeDiskWriteAndKeepsUnrelatedGroups() {
        val pending = PendingGroupExpansion()
        val state = AlbumGrouping(collapsedIds = setOf("labels", "other"))
        pending.begin("labels", state)
        assertEquals(setOf("other"), pending.apply(state).collapsedIds)
    }
    @Test fun oldWriteCannotUndoNewerTap() {
        val pending = PendingGroupExpansion()
        val state = AlbumGrouping(collapsedIds = setOf("labels"))
        val first = pending.begin("labels", state)
        val second = pending.begin("labels", pending.apply(state))
        pending.finish(first)
        assertTrue("labels" in pending.apply(state.copy(collapsedIds = emptySet())).collapsedIds)
        pending.finish(second)
        assertEquals(state, pending.apply(state))
    }
    @Test fun failedWriteRestoresPersistedStateWithoutChangingMembership() {
        val pending = PendingGroupExpansion()
        val state = AlbumGrouping(groups = listOf(AlbumGroup("g", "G", listOf("/a"))))
        val request = pending.begin("g", state)
        pending.finish(request)
        assertEquals(state, pending.apply(state))
    }
}
