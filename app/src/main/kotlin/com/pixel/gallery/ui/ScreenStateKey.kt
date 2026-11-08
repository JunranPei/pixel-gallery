package com.pixel.gallery.ui

/** Compose folds key.hashCode into saved-state IDs; enum/object identity changes across processes. */
internal fun Screen.stableStateKey(): Any = when (this) {
    Screen.Home -> "Home"
    Screen.Settings -> "Settings"
    Screen.Favourites -> "Favourites"
    Screen.Labels -> "Labels"
    is Screen.Label -> listOf("Label", labelId)
    Screen.Trash -> "Trash"
    Screen.HiddenAlbums -> "HiddenAlbums"
    Screen.LockedFolder -> "LockedFolder"
    is Screen.Viewer -> listOf("Viewer", initialId, source.name, albumName, externalUri,
        externalMimeType, searchQuery, labelId, mediaType.name, hiddenAlbum)
    Screen.ExcludedFolders -> "ExcludedFolders"
    Screen.Licenses -> "Licenses"
    is Screen.Photo -> listOf("Photo", albumName, initialMediaType.name, hiddenAlbum)
    Screen.PerformanceSettings -> "PerformanceSettings"
    Screen.DisplaySettings -> "DisplaySettings"
    Screen.LibrarySettings -> "LibrarySettings"
    is Screen.TransferDestination -> TransferStateKey(entryIds, origin.name)
}

/** Routes already own an immutable ID array; do not duplicate large selections for a key. */
private class TransferStateKey(private val ids: LongArray, private val origin: String) {
    override fun equals(other: Any?): Boolean = other is TransferStateKey &&
        origin == other.origin && ids.contentEquals(other.ids)
    override fun hashCode(): Int = 31 * ids.contentHashCode() + origin.hashCode()
}
