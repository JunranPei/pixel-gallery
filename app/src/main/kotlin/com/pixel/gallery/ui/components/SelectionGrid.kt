package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixel.gallery.ui.theme.ExpressiveShapes
import com.pixel.gallery.ui.utils.pinchToZoomColumns

/** Shared search and scrollable album grid for all three half-page pickers. */
@Composable
fun SelectionSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            shape = ExpressiveShapes.LargeIncreased,
            singleLine = true,
            enabled = enabled,
        )
        actions()
    }
}

@Composable
fun SelectionGrid(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    state: LazyGridState,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 4.dp,
    content: LazyGridScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceIn(2, 6)),
            state = state,
            modifier = Modifier.fillMaxSize().pinchToZoomColumns(
                currentColumns = columns.coerceIn(2, 6),
                onColumnsChange = onColumnsChange,
                minColumns = 2,
                maxColumns = 6,
            ),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        GalleryScrollbar(
            lazyGridState = state,
            layoutModifier = Modifier.align(Alignment.CenterEnd),
            onDragStateChanged = {},
        )
    }
}
