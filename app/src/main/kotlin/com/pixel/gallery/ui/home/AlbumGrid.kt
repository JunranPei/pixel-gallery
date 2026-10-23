package com.pixel.gallery.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixel.gallery.ui.components.GalleryScrollbar
import com.pixel.gallery.ui.utils.pinchToZoomColumns

/** Shared cover-grid shell for filesystem albums and label albums. */
@Composable
fun AlbumGrid(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    state: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(
        start = 16.dp,
        top = 16.dp,
        end = 16.dp,
        bottom = 80.dp,
    ),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    emptyMessage: String? = null,
    content: LazyGridScope.() -> Unit,
) {
    val gridColumns = columns.coerceIn(2, 6)
    val layoutDirection = LocalLayoutDirection.current
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoomColumns(
                    currentColumns = gridColumns,
                    onColumnsChange = onColumnsChange,
                    minColumns = 2,
                    maxColumns = 6,
                ),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding() + bottomPadding,
            ),
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = verticalArrangement,
            content = content,
        )

        if (emptyMessage != null) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        GalleryScrollbar(
            lazyGridState = state,
            layoutModifier = Modifier.align(Alignment.CenterEnd).padding(bottom = bottomPadding),
            onDragStateChanged = {},
        )
    }
}
