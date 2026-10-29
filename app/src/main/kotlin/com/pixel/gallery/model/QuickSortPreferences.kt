package com.pixel.gallery.model

/** User-selected real folders. The order and docking edge are independent of usage counts. */
data class QuickSortPreferences(
    val targets: List<TransferDestination> = emptyList(),
    val dockTop: Boolean = false,
    val albumRows: Int = 2,
)

internal fun quickSortAlbumRows(requestedRows: Int, targetCount: Int): Int =
    requestedRows.coerceIn(1, 4).coerceAtMost(targetCount.coerceAtLeast(1))
