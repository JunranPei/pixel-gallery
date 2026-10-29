package com.pixel.gallery.ui.quicksort

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

data class MoveGridPresentation(
    val sessionKey: String,
    val completedBatchPaths: Set<String>,
    val heldItems: com.pixel.gallery.model.QuickSortBatchHold,
    val relocatedEntries: Map<Pair<Long, String>, com.pixel.gallery.data.local.entity.MediaEntry> = emptyMap(),
)

val LocalMoveGridPresentation = staticCompositionLocalOf<MoveGridPresentation?> { null }
val LocalMediaSelectionMode = staticCompositionLocalOf { false }
val LocalSelectionPanelInset = staticCompositionLocalOf { 0.dp }
