package com.pixel.gallery.data.repository

import com.pixel.gallery.data.local.entity.MediaEntry

/** Keep unchanged immutable records shared while Room publishes a new library generation. */
internal fun reuseUnchangedMediaEntries(
    previous: List<MediaEntry>,
    incoming: List<MediaEntry>,
): List<MediaEntry> {
    if (previous == incoming) return previous
    if (previous.isEmpty() || incoming.isEmpty()) return incoming
    var byId: Map<Long, MediaEntry>? = null
    return incoming.mapIndexed { index, entry ->
        val samePosition = previous.getOrNull(index)
        if (samePosition != null && samePosition == entry) {
            samePosition
        } else {
            // A timestamp edit can move one row past thousands of unchanged rows.
            // Match by identity in that case, but reuse only when every stored field agrees.
            val lookup = byId ?: previous.associateBy { it.contentId }.also { byId = it }
            lookup[entry.contentId]?.takeIf { it == entry } ?: entry
        }
    }
}
