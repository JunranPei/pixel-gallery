package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import org.junit.Assert.*
import org.junit.Test

class CollectionCoverMemoryTest {
    private fun photo(id: Long, folder: String = "/A", trash: Boolean = false) = MediaEntry(
        id, "content://media/$id", "$folder/$id.jpg", "image/jpeg", 100, 100, 0, 100, 1, 1,
        isTrashed = trash,
    )

    @Test fun noCoverRequestDoesNotReadTheLibrary() {
        val unreadable = object : AbstractList<MediaEntry>() {
            override val size: Int get() = error("unrequested library read")
            override fun get(index: Int): MediaEntry = error("unrequested library read")
        }
        assertTrue(groupCoverCandidates(unreadable, emptyList(), AlbumGrouping(), emptyList(), emptySet()).isEmpty())
        assertTrue(findCoverMedia(unreadable, emptySet()).isEmpty())
        assertTrue(resolveGroupCoverUris(unreadable, emptyList(), AlbumGrouping(), emptyList(),
            mapOf("group:deleted" to "missing", "album:A" to "other")).isEmpty())
        val albums = listOf(Album("A", "/A", "auto", 1))
        assertSame(albums, applyAlbumCovers(albums, unreadable, mapOf(labelCoverKey(7) to "other")))
    }

    @Test fun groupRequestsRespectVisibilityMembershipAndDefaultLabelGroup() {
        val a = photo(1); val b = photo(2, "/AB"); val hidden = photo(3, "/Hidden")
        val groups = AlbumGrouping(listOf(
            AlbumGroup("ordinary", "A", listOf("/A", "/Hidden")),
            AlbumGroup("label", "L", labelIds = listOf(7)),
        ))
        val albums = listOf(Album("A", "/A", a.uri, 1), Album("AB", "/AB", b.uri, 1))
        val links = listOf(MediaLabelEntry(labelId = 7, contentId = 1), MediaLabelEntry(labelId = 8, contentId = 2))
        val result = groupCoverCandidates(listOf(a, b, hidden), albums, groups, links,
            setOf("ordinary", "label", LABELS_ALBUM_GROUP, "deleted"))
        assertEquals(listOf(a), result["ordinary"])
        assertEquals(listOf(a), result["label"])
        assertEquals(listOf(b), result[LABELS_ALBUM_GROUP])
        assertFalse(result.containsKey("deleted"))
        assertEquals(setOf("label"), groupCoverCandidates(listOf(a, b), albums, groups, links, setOf("label")).keys)
    }

    @Test fun coverLookupKeepsOnlyRequestedLiveMedia() {
        val a = photo(1); val b = photo(2); val trash = photo(3, trash = true)
        assertEquals(mapOf(b.uri to b), findCoverMedia(listOf(a, b, trash), setOf(b.uri, trash.uri, "removed")))
        val albums = listOf(Album("A", "/A", a.uri, 2))
        assertEquals(b.uri, applyAlbumCovers(albums, listOf(a, b), mapOf(albumCoverKey("/A") to b.uri)).single().coverUri)
    }

    @Test fun legacyGroupCoverValidationMatchesCandidatesWithoutHoldingThem() {
        val a = photo(1); val b = photo(2, "/AB"); val hidden = photo(3, "/Hidden")
        val trash = photo(4, trash = true)
        val media = listOf(a, b, hidden, trash)
        val albums = listOf(Album("A", "/A", a.uri, 1), Album("AB", "/AB", b.uri, 1))
        val groups = AlbumGrouping(listOf(AlbumGroup("ordinary", "A", listOf("/A", "/Hidden")),
            AlbumGroup("label", "L", labelIds = listOf(7))))
        val links = listOf(MediaLabelEntry(labelId = 7, contentId = 1), MediaLabelEntry(labelId = 8, contentId = 2))
        val choices = mapOf(groupCoverKey("ordinary") to a.uri, groupCoverKey("label") to a.uri,
            groupCoverKey(LABELS_ALBUM_GROUP) to b.uri)
        assertEquals(mapOf("ordinary" to a.uri, "label" to a.uri, LABELS_ALBUM_GROUP to b.uri),
            resolveGroupCoverUris(media, albums, groups, links, choices))
        for (invalid in listOf(b.uri, hidden.uri, trash.uri, "missing")) {
            assertTrue(resolveGroupCoverUris(media, albums, groups, links,
                mapOf(groupCoverKey("ordinary") to invalid)).isEmpty())
        }
        assertEquals(mapOf(LABELS_ALBUM_GROUP to b.uri),
            resolveGroupCoverUris(media, albums, groups, links, choices, setOf(a.contentId)))
    }

    @Test fun fourWindowAllocationComparison() {
        // Android's test compile classpath omits java.management; the host JVM
        // exposes the allocation counter through its public management interface.
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        val api = Class.forName("com.sun.management.ThreadMXBean")
        check(api.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true)
        api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val allocatedBytes = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val media = (1L..131_014L).map { photo(it, "/Album${it % 179}") }
        val albums = (0 until 179).map { Album("Album$it", "/Album$it", "auto", 1) }
        val groups = AlbumGrouping((0 until 8).map { g ->
            AlbumGroup("g$g", "Group$g", albums.filterIndexed { i, _ -> i % 8 == g }.map { it.path })
        })
        fun oldCandidates(): Map<String, List<MediaEntry>> {
            val byAlbum = media.groupBy { it.path.substringBeforeLast('/') }
            return groups.groups.associate { group -> group.id to group.paths.flatMap { byAlbum[it].orEmpty() } }
        }
        fun allocated(block: () -> Unit): Long {
            val id = Thread.currentThread().id
            val start = allocatedBytes.invoke(bean, id) as Long
            block()
            return (allocatedBytes.invoke(bean, id) as Long) - start
        }
        repeat(2) { oldCandidates(); groupCoverCandidates(media, albums, groups, emptyList(), emptySet()) }
        val beforeGroups = allocated { repeat(4) { assertEquals(media.size, oldCandidates().values.sumOf { it.size }) } }
        val afterGroups = allocated { repeat(4) { assertTrue(groupCoverCandidates(media, albums, groups, emptyList(), emptySet()).isEmpty()) } }
        val requested = setOf(media[0].uri, media[1].uri)
        val beforeCovers = allocated { repeat(4) { assertEquals(media.size, media.filterNot { it.isTrashed }.associateBy { it.uri }.size) } }
        val afterCovers = allocated { repeat(4) { assertEquals(2, findCoverMedia(media, requested).size) } }
        println("COVER_ALLOCATIONS records=${media.size} windows=4 groupsBefore=$beforeGroups groupsAfter=$afterGroups coversBefore=$beforeCovers coversAfter=$afterCovers")
        assertTrue("Idle group preparation must not scale with library size", afterGroups < beforeGroups / 100)
        assertTrue("Manual covers must not allocate a full-library index", afterCovers < beforeCovers / 100)
    }
}
