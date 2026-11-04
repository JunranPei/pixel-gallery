package com.pixel.gallery.model

data class AlbumOrderState(
    val rule: AlbumSortOrder? = null,
    val customStarted: Boolean = false,
    val custom: List<String> = emptyList(),
    val pinned: List<String> = emptyList(),
)
fun GroupedAlbumRow.orderScope(): String = when {
    group != null -> if (group.isLabelGroup) "groups:labels" else "groups:albums"
    else -> backgroundGroup ?: if (label != null) "labels" else "albums"
}
fun AlbumGrouping.orderState(scope: String) = ordering.orEmpty()[scope] ?: AlbumOrderState()
fun AlbumGrouping.withOrder(scope: String, change: (AlbumOrderState) -> AlbumOrderState): AlbumGrouping =
    copy(ordering = ordering.orEmpty() + (scope to change(orderState(scope))))

/** Remember identities without removing temporarily unavailable albums. */
fun AlbumGrouping.rememberOrder(rows: List<GroupedAlbumRow>): AlbumGrouping {
    var result = this
    rows.filter { it.album != null || it.label != null || it.group != null }.groupBy { it.orderScope() }.forEach { (scope, entries) ->
        result = result.withOrder(scope) { it.copy(custom = it.custom + entries.map { row -> row.key }.filterNot(it.custom::contains)) }
    }
    return result
}
fun orderedAlbumRows(rows: List<GroupedAlbumRow>, grouping: AlbumGrouping, default: AlbumSortOrder): List<GroupedAlbumRow> {
    fun sort(entries: List<GroupedAlbumRow>): List<GroupedAlbumRow> {
        if (entries.isEmpty()) return entries
        val scope = entries.first().orderScope()
        val saved = grouping.orderState(scope)
        val rule = saved.rule ?: if (scope.startsWith("groups:")) AlbumSortOrder.CUSTOM else default
        fun name(r: GroupedAlbumRow) = r.group?.name ?: r.album?.name ?: r.label?.label?.name.orEmpty()
        fun count(r: GroupedAlbumRow) = r.album?.itemCount ?: r.label?.count ?: (r.group?.paths?.size ?: 0) + (r.group?.labelIds?.size ?: 0)
        fun date(r: GroupedAlbumRow) = r.album?.lastModified ?: r.label?.lastModified ?: 0L
        val ordinary = entries.filterNot { it.key in saved.pinned }
        val sorted = when (rule) {
            AlbumSortOrder.CUSTOM -> ordinary.sortedBy { saved.custom.indexOf(it.key).takeIf { n -> n >= 0 } ?: Int.MAX_VALUE }
            AlbumSortOrder.NAME_ASC -> ordinary.sortedWith { a,b -> compareNaturalNames(name(a),name(b)) }
            AlbumSortOrder.NAME_DESC -> ordinary.sortedWith { a,b -> compareNaturalNames(name(b),name(a)) }
            AlbumSortOrder.COUNT_ASC -> ordinary.sortedBy(::count)
            AlbumSortOrder.COUNT_DESC -> ordinary.sortedByDescending(::count)
            AlbumSortOrder.DATE_ASC -> ordinary.sortedBy(::date)
            AlbumSortOrder.DATE_DESC -> ordinary.sortedByDescending(::date)
        }
        return entries.filter { it.key in saved.pinned }.sortedBy { saved.pinned.indexOf(it.key) } + sorted
    }
    val headers = rows.filter { it.group != null }
    return buildList {
        addAll(rows.filter { it.favourite != null })
        listOf(true,false).forEach { labelGroups ->
            sort(headers.filter { it.group!!.isLabelGroup == labelGroups }).forEach { header ->
                add(header)
                addAll(sort(rows.filter { it.group == null && it.backgroundGroup == header.group!!.id }))
            }
        }
        addAll(sort(rows.filter { it.backgroundGroup == null && it.label != null }))
        addAll(sort(rows.filter { it.backgroundGroup == null && it.album != null }))
        addAll(rows.filter { it.key == "system:trash" })
    }
}

fun AlbumGrouping.startCustom(scope: String, rows: List<GroupedAlbumRow>): AlbumGrouping = withOrder(scope) { saved ->
    if (saved.customStarted) saved else {
        val visible = rows.filter { it.orderScope() == scope && (it.album != null || it.label != null || it.group != null) }.map { it.key }
        saved.copy(customStarted = true, custom = visible + saved.custom.filterNot(visible::contains))
    }
}
