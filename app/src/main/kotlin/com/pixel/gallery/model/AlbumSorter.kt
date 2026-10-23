package com.pixel.gallery.model

/** Sort orders shared by ordinary albums and label albums. */
enum class AlbumSortOrder(val criterion: String, val direction: String) {
    NAME_ASC("NAME", "ASC"),
    NAME_DESC("NAME", "DESC"),
    COUNT_ASC("COUNT", "ASC"),
    COUNT_DESC("COUNT", "DESC"),
    DATE_ASC("DATE", "ASC"),
    DATE_DESC("DATE", "DESC");

    companion object {
        fun fromSelection(criterion: String, direction: String): AlbumSortOrder {
            val normalizedDirection = if (direction == "DESC") "DESC" else "ASC"
            return entries.firstOrNull {
                it.criterion == criterion && it.direction == normalizedDirection
            } ?: NAME_ASC
        }

        /** Old installs may still have the removed CUSTOM value stored. */
        fun fromStored(value: String): AlbumSortOrder =
            entries.firstOrNull { it.name == value } ?: NAME_ASC
    }
}

/** One shared implementation for album sorting across the app. */
class AlbumSorter private constructor() {
    fun sortAlbums(albums: List<Album>, order: AlbumSortOrder): List<Album> = sort(
        values = albums,
        order = order,
        name = Album::name,
        count = Album::itemCount,
        timestamp = Album::lastModified,
    )

    fun sortLabels(labels: List<LabelSummary>, order: AlbumSortOrder): List<LabelSummary> =
        labels.filter { it.label.isSystem } + sort(
            values = labels.filterNot { it.label.isSystem },
            order = order,
            name = { it.label.name },
            count = LabelSummary::count,
            timestamp = LabelSummary::lastModified,
        )

    private fun <T> sort(
        values: List<T>,
        order: AlbumSortOrder,
        name: (T) -> String,
        count: (T) -> Int,
        timestamp: (T) -> Long,
    ): List<T> = when (order) {
        AlbumSortOrder.NAME_ASC -> values.sortedWith(naturalBy(name))
        AlbumSortOrder.NAME_DESC -> values.sortedWith(naturalBy(name).reversed())
        AlbumSortOrder.COUNT_ASC -> values.sortedWith(compareBy(count).then(naturalBy(name)))
        AlbumSortOrder.COUNT_DESC -> values.sortedWith(compareByDescending(count).then(naturalBy(name)))
        AlbumSortOrder.DATE_ASC -> values.sortedWith(compareBy(timestamp).then(naturalBy(name)))
        AlbumSortOrder.DATE_DESC -> values.sortedWith(compareByDescending(timestamp).then(naturalBy(name)))
    }

    companion object {
        val shared = AlbumSorter()
    }
}

private fun <T> naturalBy(selector: (T) -> String): Comparator<T> =
    Comparator { first, second -> naturalNameOrder.compare(selector(first), selector(second)) }

private val naturalNameOrder = Comparator<String> { first, second ->
    val length = minOf(first.length, second.length)
    for (index in 0 until length) {
        val left = first[index]
        val right = second[index]
        if (left != right) {
            val upperLeft = left.uppercaseChar()
            val upperRight = right.uppercaseChar()
            if (upperLeft != upperRight) return@Comparator upperLeft.compareTo(upperRight)
            return@Comparator if (left.isUpperCase()) -1 else 1
        }
    }
    first.length.compareTo(second.length)
}

internal fun compareNaturalNames(first: String, second: String): Int = naturalNameOrder.compare(first, second)
