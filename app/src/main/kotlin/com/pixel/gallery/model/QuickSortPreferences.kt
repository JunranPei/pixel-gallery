package com.pixel.gallery.model

/** User-selected real folders. The order and docking edge are independent of usage counts. */
data class QuickSortPreferences(
    val targets: List<TransferDestination> = emptyList(),
    val dockTop: Boolean = false,
)
