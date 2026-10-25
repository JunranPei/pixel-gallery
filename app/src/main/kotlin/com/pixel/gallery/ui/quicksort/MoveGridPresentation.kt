package com.pixel.gallery.ui.quicksort

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

data class MoveGridPresentation(
    val sessionKey: String,
    val completedBatchPaths: Set<String>,
    val reservedIds: Set<Long>,
)

val LocalMoveGridPresentation = staticCompositionLocalOf<MoveGridPresentation?> { null }
val LocalMediaSelectionMode = staticCompositionLocalOf { false }
val LocalSelectionPanelInset = staticCompositionLocalOf { 0.dp }
