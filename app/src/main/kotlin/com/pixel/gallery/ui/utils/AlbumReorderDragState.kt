package com.pixel.gallery.ui.utils

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/** Pointer-owned position, independent of persistence and the other items' placement animations. */
@Stable
internal class AlbumReorderDragState {
    var key: Any? by mutableStateOf(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
    private var grabOffset = Offset.Zero

    fun begin(key: Any, position: Offset, grid: LazyGridState, leftPadding: Float) {
        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        pointer = position
        grabOffset = gridPointerInContentCoordinates(position, leftPadding, grid.layoutInfo.viewportStartOffset) -
            Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
        this.key = key
    }

    fun translation(grid: LazyGridState, leftPadding: Float): Offset {
        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return Offset.Zero
        return gridPointerInContentCoordinates(pointer, leftPadding, grid.layoutInfo.viewportStartOffset) -
            grabOffset - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
    }

    fun end() { key = null }
}
