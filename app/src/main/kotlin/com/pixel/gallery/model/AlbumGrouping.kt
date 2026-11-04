package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.FAVOURITES_LABEL_ID

const val FAVOURITES_ALBUM_ENTRY = "system:favourites"
const val LABELS_ALBUM_GROUP = "system:labels"

/** No grouping action for an empty selection or for mixed collection types. */
fun canGroupAlbumSelection(ordinaryCount: Int, labelCount: Int): Boolean =
    (ordinaryCount > 0) xor (labelCount > 0)

data class AlbumGroup(
    val id: String, val name: String, val paths: List<String> = emptyList(),
    val colorIndex: Int? = null,
    val labelIds: List<Long>? = null,
) {
    val isLabelGroup: Boolean get() = id == LABELS_ALBUM_GROUP || labelIds != null
    // Legacy groups keep a stable ID-based color without rewriting their data.
    val resolvedColorIndex: Int get() = colorIndex?.takeIf { it in 0..3 } ?: Math.floorMod(id.hashCode(), 4)
}
data class AlbumGrouping(
    val groups: List<AlbumGroup> = emptyList(),
    val collapsedIds: Set<String> = setOf(LABELS_ALBUM_GROUP),
    val ordering: Map<String, AlbumOrderState>? = emptyMap(),
)

/** Allocate once; avoid the previous group and balance the four quiet hues. */
fun AlbumGrouping.nextColorIndex(): Int {
    val previous = groups.lastOrNull()?.resolvedColorIndex ?: 2 // labels use grey violet
    return (0..3).filter { it != previous }
        .minBy { candidate -> groups.count { it.resolvedColorIndex == candidate } }
}

/** A path belongs to at most one group. Missing/hidden albums retain their stored membership. */
fun AlbumGrouping.assign(paths: Set<String>, groupId: String?): AlbumGrouping {
    require(groupId == null || groups.any { it.id == groupId && !it.isLabelGroup })
    return copy(groups = groups.map { group ->
        val retained = group.paths.filterNot { it in paths }
        group.copy(paths = if (group.id == groupId) retained + paths else retained)
    })
}

/** Label membership is separate from filesystem paths; null returns labels to the default group. */
fun AlbumGrouping.assignLabels(ids: Set<Long>, groupId: String?): AlbumGrouping {
    require(FAVOURITES_LABEL_ID !in ids) { "Favourites cannot be grouped" }
    require(groupId == null || groups.any { it.id == groupId && it.isLabelGroup })
    return copy(groups = groups.map { group ->
        if (!group.isLabelGroup) group else {
            val retained = group.labelIds.orEmpty().filterNot { it in ids || it == FAVOURITES_LABEL_ID }
            group.copy(paths = emptyList(), labelIds = if (group.id == groupId) retained + ids else retained)
        }
    })
}

fun AlbumGrouping.labelGroupId(labelId: Long): String =
    if (labelId == FAVOURITES_LABEL_ID) FAVOURITES_ALBUM_ENTRY
    else groups.firstOrNull { it.isLabelGroup && labelId in it.labelIds.orEmpty() }?.id ?: LABELS_ALBUM_GROUP

fun AlbumGrouping.remap(source: String, target: String): AlbumGrouping = copy(groups = groups.map { group ->
    group.copy(paths = group.paths.map { remapAlbumPath(it, source, target) }.distinct())
})

data class GroupedAlbumRow(
    val key: String,
    val backgroundGroup: String? = null,
    val group: AlbumGroup? = null,
    val album: Album? = null,
    val label: LabelSummary? = null,
    val favourite: LabelSummary? = null,
)

/** One flat sequence: no spans, nested grids or group-specific cell measurements. */
fun groupedAlbumRows(
    albums: List<Album>, grouping: AlbumGrouping, labels: List<LabelSummary>, search: Boolean = false, groupingEnabled: Boolean = true,
): List<GroupedAlbumRow> {
    if (search) return albums.map { GroupedAlbumRow("album:${it.path}", album = it) }
    return buildList {
        labels.firstOrNull { it.label.id == FAVOURITES_LABEL_ID && it.count > 0 }?.let {
            add(GroupedAlbumRow(FAVOURITES_ALBUM_ENTRY, favourite = it))
        }
        if (!groupingEnabled) {
            labels.filterNot { it.label.isSystem }.forEach { add(GroupedAlbumRow("label:${it.label.id}", label = it)) }
            albums.forEach { add(GroupedAlbumRow("album:${it.path}", album = it)) }
            add(GroupedAlbumRow("system:trash"))
            return@buildList
        }
        val defaultLabels = labels.filter { !it.label.isSystem && grouping.labelGroupId(it.label.id) == LABELS_ALBUM_GROUP }
        if (defaultLabels.isNotEmpty()) {
            add(GroupedAlbumRow("group:$LABELS_ALBUM_GROUP", LABELS_ALBUM_GROUP,
                group = AlbumGroup(LABELS_ALBUM_GROUP, "")))
            if (LABELS_ALBUM_GROUP !in grouping.collapsedIds) defaultLabels.forEach {
                add(GroupedAlbumRow("label:${it.label.id}", LABELS_ALBUM_GROUP, label = it))
            }
        }
        val assigned = hashSetOf<String>()
        grouping.groups.sortedBy { !it.isLabelGroup }.forEach { group ->
            val open = group.id !in grouping.collapsedIds
            add(GroupedAlbumRow("group:${group.id}", group.id, group = group))
            if (group.isLabelGroup) {
                if (open) labels.filter { grouping.labelGroupId(it.label.id) == group.id }.forEach {
                    add(GroupedAlbumRow("label:${it.label.id}", group.id, label = it))
                }
                return@forEach
            }
            // Preserve the caller's album sort inside each group.
            albums.filter { it.path in group.paths && assigned.add(it.path) }.forEach {
                if (open) add(GroupedAlbumRow("album:${it.path}", group.id, album = it))
            }
        }
        albums.filterNot { it.path in assigned }.forEach { add(GroupedAlbumRow("album:${it.path}", album = it)) }
        add(GroupedAlbumRow("system:trash"))
    }
}
