package com.pixel.gallery.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Data shared by filesystem, hidden-folder, and label collection grids. */
data class AlbumCollectionItem<Key : Any>(
    val key: Key,
    val title: String,
    val subtitle: String,
    val coverUri: String?,
    val coverModifiedMillis: Long,
    val coverMimeType: String? = null,
    val coverSizeBytes: Long? = null,
    val coverRotationDegrees: Int = 0,
    val emptyIcon: ImageVector = Icons.Outlined.Folder,
    val selectable: Boolean = true,
)

/** One collection-grid implementation; each page supplies only data and actions. */
class AlbumCollectionGrid private constructor() {
    companion object {
        val instance = AlbumCollectionGrid()
    }

    @Composable
    fun <Key : Any> Content(
        entries: List<AlbumCollectionItem<Key>>,
        columns: Int,
        onColumnsChange: (Int) -> Unit,
        onOpen: (Key) -> Unit,
        onToggleSelection: (Key) -> Unit,
        selectedKeys: Set<Key> = emptySet(),
        selectionMode: Boolean = selectedKeys.isNotEmpty(),
        enabled: Boolean = true,
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
        beforeItems: LazyGridScope.() -> Unit = {},
        afterItems: LazyGridScope.() -> Unit = {},
    ) {
        val gridColumns = columns.coerceIn(2, 6)
        AlbumGrid(
            columns = gridColumns,
            onColumnsChange = onColumnsChange,
            state = state,
            modifier = modifier,
            bottomPadding = bottomPadding,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = verticalArrangement,
            emptyMessage = if (entries.isEmpty()) emptyMessage else null,
        ) {
            beforeItems()
            items(entries, key = { it.key }) { entry ->
                AlbumCoverCard(
                    title = entry.title,
                    subtitle = entry.subtitle,
                    coverUri = entry.coverUri,
                    coverModifiedMillis = entry.coverModifiedMillis,
                    coverMimeType = entry.coverMimeType,
                    coverSizeBytes = entry.coverSizeBytes,
                    coverRotationDegrees = entry.coverRotationDegrees,
                    emptyIcon = entry.emptyIcon,
                    columns = gridColumns,
                    enabled = enabled,
                    selected = entry.key in selectedKeys,
                    selectionMode = selectionMode && entry.selectable,
                    onClick = {
                        if (!selectionMode) onOpen(entry.key)
                        else if (entry.selectable) onToggleSelection(entry.key)
                    },
                    onLongClick = if (entry.selectable) ({ onToggleSelection(entry.key) }) else null,
                )
            }
            afterItems()
        }
    }
}
