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
    val byUri = media.filterNot { it.isTrashed }.associateBy { it.uri }
    return albums.map { album ->
        val manual = covers[albumCoverKey(album.path)]?.let(byUri::get)
            ?.takeIf { it.path.substringBeforeLast('/') == album.path.trimEnd('/') }
        if (manual == null) album else album.copy(coverUri = manual.uri)
    }
}

fun remapCollectionCovers(covers: Map<String, String>, source: String, target: String): Map<String, String> =
    covers.mapKeys { (key, _) ->
        if (key.startsWith("album:")) albumCoverKey(remapAlbumPath(key.removePrefix("album:"), source, target)) else key
    }
