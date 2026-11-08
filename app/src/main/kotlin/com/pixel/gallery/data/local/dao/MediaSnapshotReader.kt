package com.pixel.gallery.data.local.dao

import com.pixel.gallery.data.local.entity.MediaEntry

/** Reuse each immutable row before the next database page is decoded. */
internal suspend fun readMediaSnapshot(
    previous: List<MediaEntry>,
    pageSize: Int,
    loadPage: suspend (limit: Int, afterContentId: Long) -> List<MediaEntry>,
): List<MediaEntry> {
    require(pageSize > 0)
    // The query advances by ID, while the published snapshot is ordered by time.
    // A pointer array and merge cursor avoid a boxed Long/hash-node per record.
    val byId = previous.sortedWith { a, b -> a.contentId.compareTo(b.contentId) }
    var oldPosition = 0
    val result = ArrayList<MediaEntry>(previous.size)
    var after = -1L
    while (true) {
        val page = loadPage(pageSize, after)
        for (row in page) {
            check(row.contentId > after) { "Media pages must advance in content ID order" }
            while (oldPosition < byId.size && byId[oldPosition].contentId < row.contentId) oldPosition++
            val old = byId.getOrNull(oldPosition)
            result.add(old?.takeIf { it == row } ?: row)
            after = row.contentId
        }
        if (page.size < pageSize) break
    }
    result.sortWith { a, b ->
        val time = b.bestTimestamp.compareTo(a.bestTimestamp)
        if (time != 0) time else b.contentId.compareTo(a.contentId)
    }
    return if (result == previous) previous else result
}
