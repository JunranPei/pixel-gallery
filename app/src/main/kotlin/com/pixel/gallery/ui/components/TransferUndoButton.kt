package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R

@Composable
internal fun TransferUndoButton(
    available: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    description: String = stringResource(R.string.qs_undo),
    unavailableDescription: String = stringResource(R.string.qs_undo_unavailable),
) {
    val enabled = available && !busy
    Surface(onClick = onClick, enabled = enabled,
        shape = CircleShape, shadowElevation = 1.dp,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp).size(56.dp)
            .semantics { if (!available) stateDescription = unavailableDescription }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (available) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.HistoryToggleOff, description)
        }
    }
}
