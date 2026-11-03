package com.pixel.gallery.model

const val LABELS_ALBUM_GROUP = "system:labels"

data class AlbumGroup(
    val id: String, val name: String, val paths: List<String> = emptyList(),
    val colorIndex: Int? = null,
) {
    // Legacy groups keep a stable ID-based color without rewriting their data.
    val resolvedColorIndex: Int get() = colorIndex?.takeIf { it in 0..3 } ?: Math.floorMod(id.hashCode(), 4)
}
data class AlbumGrouping(
    val groups: List<AlbumGroup> = emptyList(),
    val collapsedIds: Set<String> = setOf(LABELS_ALBUM_GROUP),
)

/** Allocate once; avoid the previous group and balance the four quiet hues. */
fun AlbumGrouping.nextColorIndex(): Int {
    val previous = groups.lastOrNull()?.resolvedColorIndex ?: 2 // labels use grey violet
    return (0..3).filter { it != previous }
        .minBy { candidate -> groups.count { it.resolvedColorIndex == candidate } }
}

/** A path belongs to at most one group. Missing/hidden albums retain their stored membership. */
fun AlbumGrouping.assign(paths: Set<String>, groupId: String?): AlbumGrouping {
    require(groupId == null || groups.any { it.id == groupId })
    return copy(groups = groups.map { group ->
        val retained = group.paths.filterNot { it in paths }
        group.copy(paths = if (group.id == groupId) retained + paths else retained)
    })
}

fun AlbumGrouping.remap(source: String, target: String): AlbumGrouping = copy(groups = groups.map { group ->
    group.copy(paths = group.paths.map { remapAlbumPath(it, source, target) }.distinct())
})

data class GroupedAlbumRow(
    val key: String,
    val backgroundGroup: String? = null,
    val group: AlbumGroup? = null,
    val album: Album? = null,
    val label: LabelSummary? = null,
)

/** One flat sequence: no spans, nested grids or group-specific cell measurements. */
fun groupedAlbumRows(
    albums: List<Album>, grouping: AlbumGrouping, labels: List<LabelSummary>, search: Boolean = false,
): List<GroupedAlbumRow> {
    if (search) return albums.map { GroupedAlbumRow("album:${it.path}", album = it) }
    return buildList {
        val labelsOpen = LABELS_ALBUM_GROUP !in grouping.collapsedIds
        add(GroupedAlbumRow("group:$LABELS_ALBUM_GROUP", LABELS_ALBUM_GROUP,
            group = AlbumGroup(LABELS_ALBUM_GROUP, "")))
        if (labelsOpen) labels.forEach {
            add(GroupedAlbumRow("label:${it.label.id}", LABELS_ALBUM_GROUP, label = it))
        }
        val assigned = hashSetOf<String>()
        grouping.groups.forEach { group ->
            val open = group.id !in grouping.collapsedIds
            add(GroupedAlbumRow("group:${group.id}", group.id, group = group))
            // Preserve the caller's album sort inside each group.
            albums.filter { it.path in group.paths && assigned.add(it.path) }.forEach {
                if (open) add(GroupedAlbumRow("album:${it.path}", group.id, album = it))
            }
        }
        albums.filterNot { it.path in assigned }.forEach { add(GroupedAlbumRow("album:${it.path}", album = it)) }
        add(GroupedAlbumRow("system:trash"))
    }
}
