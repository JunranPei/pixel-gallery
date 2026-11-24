package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.pixel.gallery.R
import com.pixel.gallery.ui.theme.ExpressiveShapes
import com.pixel.gallery.ui.utils.pinchToZoomColumns

/** Shared search and scrollable album grid for all three half-page pickers. */
@Composable
fun SelectionSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    actionVisible: Boolean = true,
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
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            shape = ExpressiveShapes.LargeIncreased,
            singleLine = true,
            enabled = enabled,
        )
        AnimatedVisibility(
            visible = actionVisible,
            enter = fadeIn(tween(180)) + expandHorizontally(tween(180)),
            exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180)),
        ) {
            actions()
        }
    }
}

@Composable
fun SelectionSearchAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(48.dp),
        shape = ExpressiveShapes.LargeIncreased,
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SelectionGrid(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    state: LazyGridState,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 4.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = ExpressiveShapes.AlbumCornerRadius,
    content: LazyGridScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().pinchToZoomColumns(
        currentColumns = columns.coerceIn(2, 6),
        onColumnsChange = onColumnsChange,
        minColumns = 2,
        maxColumns = 6,
    )) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceIn(2, 6)),
            state = state,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius)),
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
