package com.pixel.gallery.model

internal fun canCreateAlbumFromQuery(query: String, parent: TransferDestination, albums: List<TransferDestination>): Boolean {
    if (!isValidAlbumName(query)) return false
    val path = parent.path.trimEnd('/') + "/" + query.trim()
    return albums.none { it.path.trimEnd('/').equals(path, ignoreCase = true) }
}
