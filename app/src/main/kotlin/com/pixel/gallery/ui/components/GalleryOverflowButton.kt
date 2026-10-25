package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R

/** Fixed toolbar action bounds also anchor the menu consistently across grids. */
@Composable
fun GalleryOverflowButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSettings: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.fillMaxSize()) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.more), modifier = Modifier.size(24.dp))
        }
        GalleryOverflowMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            onSettings = onSettings,
            content = content,
        )
    }
}
