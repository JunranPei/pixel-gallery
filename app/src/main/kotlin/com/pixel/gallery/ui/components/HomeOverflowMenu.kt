package com.pixel.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R

@Composable
fun HomeOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    showQuickSort: Boolean,
    sortLabel: String,
    duplicateLabel: String,
    canFindDuplicates: Boolean,
    onQuickSort: () -> Unit,
    onSort: () -> Unit,
    onFindDuplicates: () -> Unit,
    onHiddenAlbums: () -> Unit,
    onLockedFolder: () -> Unit,
    onSettings: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(236.dp),
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        if (showQuickSort) {
            HomeMenuRow(
                label = stringResource(R.string.qs_start),
                icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                accented = true,
                onClick = onQuickSort,
            )
        }
        HomeMenuRow(sortLabel, Icons.AutoMirrored.Filled.Sort, accented = true, onClick = onSort)
        HomeMenuRow(
            label = duplicateLabel,
            icon = Icons.Outlined.FileCopy,
            accented = true,
            enabled = canFindDuplicates,
            onClick = onFindDuplicates,
        )

        Spacer(Modifier.height(8.dp))
        HomeMenuRow("Hidden albums", Icons.Outlined.VisibilityOff, onClick = onHiddenAlbums)
        HomeMenuRow("Locked folder", Icons.Outlined.Lock, onClick = onLockedFolder)

        Spacer(Modifier.height(8.dp))
        HomeMenuRow("Settings", Icons.Outlined.Settings, onClick = onSettings)
    }
}

@Composable
private fun HomeMenuRow(
    label: String,
    icon: ImageVector,
    accented: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val iconBackground = if (accented) colors.primaryContainer else colors.surfaceContainerHighest
    val iconColor = if (accented) colors.onPrimaryContainer else colors.onSurfaceVariant
    val contentAlpha = if (enabled) 1f else 0.38f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconBackground.copy(alpha = if (enabled) 1f else 0.38f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = iconColor.copy(alpha = contentAlpha),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface.copy(alpha = contentAlpha),
        )
    }
}
