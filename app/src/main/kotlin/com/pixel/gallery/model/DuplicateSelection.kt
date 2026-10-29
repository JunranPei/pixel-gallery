package com.pixel.gallery.model

internal fun toggleDuplicateSelection(group: DuplicateGroup, selectedIds: Set<Long>, id: Long): Set<Long> {
    val ids = group.entries.map { it.contentId }
    if (id !in ids || ids.size < 2) return selectedIds
    if (id in selectedIds) return selectedIds - id
    val retained = ids.filterNot { it in selectedIds }
    return if (retained.size == 1) {
        // Exchange with the first selected copy, preferring the recommended original.
        (selectedIds - ids.first { it in selectedIds }) + id
    } else {
        selectedIds + id
    }
}

internal fun allDuplicateCopiesSelected(groups: List<DuplicateGroup>, selectedIds: Set<Long>): Boolean =
    groups.isNotEmpty() && groups.all { group ->
        group.entries.count { it.contentId !in selectedIds } == 1
    }

internal fun toggleAllDuplicateSelection(groups: List<DuplicateGroup>, selectedIds: Set<Long>): Set<Long> {
    val groupIds = groups.flatMap { it.entries }.mapTo(hashSetOf()) { it.contentId }
    if (allDuplicateCopiesSelected(groups, selectedIds)) return selectedIds - groupIds
    val copies = groups.flatMap { group ->
        val ids = group.entries.map { it.contentId }
        val keeper = ids.firstOrNull { it !in selectedIds } ?: group.keeper.contentId
        ids.filterNot { it == keeper }
    }
    return (selectedIds - groupIds) + copies
}
