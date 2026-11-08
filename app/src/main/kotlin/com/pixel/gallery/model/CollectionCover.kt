package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry

data class CollectionCover(val key: String, val uri: String)
data class CoverTarget(val key: String, val title: String, val hidden: Boolean = false)
fun albumCoverKey(path: String) = "album:$path"
fun labelCoverKey(id: Long) = "label:$id"
fun groupCoverKey(id: String) = "group:$id"

/** Resolve only against the current visible collection; stale choices fall back automatically. */
fun resolveCollectionCover(uri: String?, candidates: List<MediaEntry>): MediaEntry? =
    uri?.let { selected -> candidates.firstOrNull { it.uri == selected && !it.isTrashed } }

fun applyAlbumCovers(albums: List<Album>, media: List<MediaEntry>, covers: Map<String, String>): List<Album> {
    if (covers.isEmpty()) return albums
    val requested = albums.mapNotNullTo(hashSetOf()) { covers[albumCoverKey(it.path)] }
    if (requested.isEmpty()) return albums
    val byUri = findCoverMedia(media, requested)
    return albums.map { album ->
        val manual = covers[albumCoverKey(album.path)]?.let(byUri::get)
            ?.takeIf { it.path.substringBeforeLast('/') == album.path.trimEnd('/') }
        if (manual == null) album else album.copy(coverUri = manual.uri)
    }
}

/** Index only configured covers, not every image in every open window. */
fun findCoverMedia(media: List<MediaEntry>, requested: Set<String>): Map<String, MediaEntry> {
    if (requested.isEmpty()) return emptyMap()
    val result = HashMap<String, MediaEntry>()
    media.forEach { if (!it.isTrashed && it.uri in requested) result[it.uri] = it }
    return result
}

/** Validate legacy photo covers without materializing every group's member list. */
fun resolveGroupCoverUris(
    media: List<MediaEntry>, albums: List<Album>, grouping: AlbumGrouping,
    memberships: List<com.pixel.gallery.data.local.entity.MediaLabelEntry>,
    covers: Map<String, String>, lockedIds: Set<Long> = emptySet(),
): Map<String, String> {
    val groups = grouping.groups.associateBy { it.id }
    val choices = covers.filterKeys { key ->
        key.startsWith("group:") && (key == groupCoverKey(LABELS_ALBUM_GROUP) || key.removePrefix("group:") in groups)
    }
    if (choices.isEmpty()) return emptyMap()
    val byUri = findCoverMedia(media, choices.values.toHashSet())
    val visiblePaths = albums.mapTo(hashSetOf()) { it.path }
    return buildMap {
        choices.forEach { (key, uri) ->
            val candidate = byUri[uri] ?: return@forEach
            if (candidate.contentId in lockedIds) return@forEach
            val id = key.removePrefix("group:")
            val group = groups[id]
            val belongs = if (id == LABELS_ALBUM_GROUP || group?.isLabelGroup == true) {
                memberships.any { it.contentId == candidate.contentId && grouping.labelGroupId(it.labelId) == id }
            } else {
                val path = candidate.path.substringBeforeLast('/')
                path in visiblePaths && path in group!!.paths
            }
            if (belongs) put(id, uri)
        }
    }
}

/** Full candidate lists are only needed for the collection being edited or inspected. */
fun groupCoverCandidates(
    media: List<MediaEntry>,
    albums: List<Album>,
    grouping: AlbumGrouping,
    memberships: List<com.pixel.gallery.data.local.entity.MediaLabelEntry>,
    requested: Set<String>,
): Map<String, List<MediaEntry>> {
    if (requested.isEmpty()) return emptyMap()
    val visiblePaths = albums.mapTo(hashSetOf()) { it.path }
    return buildMap {
        requested.forEach { id ->
            val group = grouping.groups.firstOrNull { it.id == id }
            if (id != LABELS_ALBUM_GROUP && group == null) return@forEach
            if (id == LABELS_ALBUM_GROUP || group?.isLabelGroup == true) {
                val ids = memberships.asSequence().filter { grouping.labelGroupId(it.labelId) == id }
                    .map { it.contentId }.toHashSet()
                put(id, media.filter { it.contentId in ids })
            } else {
                val paths = group!!.paths.filter { it in visiblePaths }.toHashSet()
                val byAlbum = mutableMapOf<String, MutableList<MediaEntry>>()
                media.forEach { entry ->
                    val path = entry.path.substringBeforeLast('/')
                    if (path in paths) byAlbum.getOrPut(path) { mutableListOf() }.add(entry)
                }
                put(id, paths.flatMap { byAlbum[it].orEmpty() })
            }
        }
    }
}

fun remapCollectionCovers(covers: Map<String, String>, source: String, target: String): Map<String, String> =
    covers.mapKeys { (key, _) ->
        if (key.startsWith("album:")) albumCoverKey(remapAlbumPath(key.removePrefix("album:"), source, target)) else key
    }
