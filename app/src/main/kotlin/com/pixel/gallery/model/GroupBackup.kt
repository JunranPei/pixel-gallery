package com.pixel.gallery.model

/** Labels use portable backup keys rather than device-local database IDs. */
data class BackupGroup(
    val id: String,
    val name: String,
    val paths: List<String> = emptyList(),
    val labelKeys: List<String>? = null,
    val colorIndex: Int? = null,
    val illustration: Int = 0,
    val collapsed: Boolean = false,
)

data class RestoredGroups(val grouping: AlbumGrouping, val illustrations: Map<String, Int>)

fun mergeBackupGroups(current: AlbumGrouping, incoming: List<BackupGroup>, labelIds: Map<String, Long>): RestoredGroups {
    var state = current
    val art = linkedMapOf<String, Int>()
    incoming.forEach { source ->
        if (source.id == LABELS_ALBUM_GROUP) {
            state = state.copy(collapsedIds = if (source.collapsed) state.collapsedIds + source.id else state.collapsedIds - source.id)
            art[source.id] = source.illustration
            return@forEach
        }
        val isLabel = source.labelKeys != null
        val importId = if (state.groups.any { it.id == source.id && it.isLabelGroup != isLabel })
            java.util.UUID.nameUUIDFromBytes((source.id + ":" + isLabel).toByteArray(Charsets.UTF_8)).toString() else source.id
        val found = state.groups.firstOrNull { it.id == importId && it.isLabelGroup == isLabel }
            ?: state.groups.firstOrNull { it.name.equals(source.name, true) && it.isLabelGroup == isLabel }
        var target = found
        if (target == null) {
            var id = importId
            var suffix = 2
            while (state.groups.any { it.id == id }) { id = "${source.id}-import-${suffix++}" }
            var name = source.name
            suffix = 2
            while (state.groups.any { it.name.equals(name, true) }) { name = source.name.take(50) + " (${suffix++})" }
            target = AlbumGroup(id, name, colorIndex = source.colorIndex, labelIds = if (isLabel) emptyList() else null)
            state = state.copy(groups = state.groups + target,
                collapsedIds = if (source.collapsed) state.collapsedIds + id else state.collapsedIds - id)
            art[id] = source.illustration
        }
        val targetId = target.id
        art[targetId] = source.illustration
        // Restore missing memberships without moving an existing member out of its group.
        if (isLabel) {
            val assigned = state.groups.flatMap { it.labelIds.orEmpty() }.toSet()
            val members = source.labelKeys.orEmpty().mapNotNull { labelIds[it] }.filterNot { it in assigned }.toSet()
            state = state.assignLabels(members, targetId)
        } else {
            val assigned = state.groups.flatMap { it.paths }.toSet()
            state = state.assign(source.paths.filterNot { it in assigned }.toSet(), targetId)
        }
    }
    return RestoredGroups(state, art)
}
