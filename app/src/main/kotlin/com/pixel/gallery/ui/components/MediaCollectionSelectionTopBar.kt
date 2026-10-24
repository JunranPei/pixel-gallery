package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R

data class MediaCollectionTopBarAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val showProgress: Boolean = false,
    val onClick: () -> Unit,
)

/** Shared selection bar for media collections; the overflow remains pinned at the right edge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCollectionSelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    actions: List<MediaCollectionTopBarAction>,
    overflowExpanded: Boolean,
    onOverflowExpandedChange: (Boolean) -> Unit,
    overflowContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        title = { SelectedCountBadge(count) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, stringResource(R.string.clear_selection))
            }
        },
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy((-8).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    IconButton(
                        enabled = action.enabled && !action.showProgress,
                        onClick = action.onClick,
                    ) {
                        if (action.showProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(action.icon, contentDescription = action.label)
                        }
                    }
                }
                GalleryOverflowButton(
                    expanded = overflowExpanded,
                    onExpandedChange = onOverflowExpandedChange,
                    content = overflowContent,
                )
            }
        },
    )
}
