package com.pixel.gallery.ui.utils

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * LazyGridMeasuredItem.offset is in content coordinates. LazyGrid adds its
 * visualOffset (start/top content padding) only when placing the item. The
 * pointerInput modifier receives viewport coordinates, before that translation.
 * viewportStartOffset is -beforeContentPadding, including an upper quick-sort dock.
 */
internal fun gridPointerInContentCoordinates(
    pointerInViewport: Offset,
    leftContentPaddingPx: Float,
    viewportStartOffset: Int,
): Offset = Offset(
    x = pointerInViewport.x - leftContentPaddingPx,
    y = pointerInViewport.y + viewportStartOffset,
)

/** Shared by ordinary range selection and quick-sort lift/range selection. */
internal fun LazyGridState.photoGridItemIndexAt(
    pointerInViewport: Offset,
    leftContentPaddingPx: Float,
): Int? {
    val info = layoutInfo
    // PhotosScreen uses a non-reversed vertical grid. Refuse an unsupported
    // coordinate system instead of accidentally acting on a different file.
    if (info.orientation != Orientation.Vertical || info.reverseLayout) return null
    val point = gridPointerInContentCoordinates(pointerInViewport, leftContentPaddingPx, info.viewportStartOffset)
    return info.visibleItemsInfo.firstOrNull { item ->
        Rect(
            left = item.offset.x.toFloat(),
            top = item.offset.y.toFloat(),
            right = (item.offset.x + item.size.width).toFloat(),
            bottom = (item.offset.y + item.size.height).toFloat(),
        ).contains(point)
    }?.index
}
