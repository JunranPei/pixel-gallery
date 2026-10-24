package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    enabled: Boolean = true,
    renameDescription: String = stringResource(R.string.album_rename),
    onRename: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    onExclude: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    canFindDuplicates: Boolean = true,
    onFindDuplicates: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        title = { SelectedCountBadge(count) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, stringResource(R.string.clear_selection))
            }
        },
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), verticalAlignment = Alignment.CenterVertically) {
                if (count == 1 && onRename != null) {
                    IconButton(enabled = enabled, onClick = onRename) {
                        Icon(Icons.Outlined.Edit, renameDescription)
                    }
                }
                onHide?.let { action ->
                    IconButton(enabled = enabled, onClick = action) {
                        Icon(Icons.Outlined.VisibilityOff, stringResource(R.string.hide_album))
                    }
                }
                onExclude?.let { action ->
                    IconButton(enabled = enabled, onClick = action) {
                        Icon(Icons.Outlined.FolderOff, stringResource(R.string.exclude_album))
                    }
                }
                onDelete?.let { action ->
                    IconButton(enabled = enabled, onClick = action) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                    }
                }
                if (onFindDuplicates != null) {
                    androidx.compose.foundation.layout.Box(Modifier.size(48.dp)) {
                        GalleryOverflowButton(
                            expanded = showMenu,
                            onExpandedChange = { showMenu = it },
                        ) {
                            GalleryMenuRow(
                                stringResource(R.string.duplicate_find_selected_albums),
                                Icons.Outlined.FileCopy,
                                accented = true,
                                enabled = enabled && canFindDuplicates,
                                onClick = { showMenu = false; onFindDuplicates() },
                            )
                        }
                    }
                }
            }
        },
    )
}
