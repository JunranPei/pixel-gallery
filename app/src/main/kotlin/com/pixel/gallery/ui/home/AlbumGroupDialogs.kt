package com.pixel.gallery.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.pixel.gallery.ui.components.GalleryMenuRow
import com.pixel.gallery.ui.components.ConfirmationReminder
import com.pixel.gallery.ui.theme.EmphasizedTypography
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.model.AlbumGroup
import com.pixel.gallery.ui.components.GalleryDialog

@Composable
fun AlbumGroupNameDialog(groups: List<AlbumGroup>, editing: AlbumGroup? = null, onDismiss: () -> Unit, initialName: String = "", onSave: (String) -> Unit) {
    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: initialName) }
    val valid = name.trim().isNotEmpty() && name.trim().length <= 60 && groups.none { it.id != editing?.id && it.name.equals(name.trim(), true) }
    GalleryDialog(onDismissRequest = onDismiss,
        icon = if (editing == null) Icons.Outlined.CreateNewFolder else Icons.Outlined.Edit,
        title = { Text(stringResource(if (editing == null) R.string.album_group_new else R.string.album_group_rename)) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.album_group_name)) },
                isError = name.isNotEmpty() && !valid,
                supportingText = { if (name.isNotEmpty() && !valid) Text(stringResource(R.string.album_group_name_error)) })
        },
        confirmButton = { Button(onClick = { onSave(name.trim()) }, enabled = valid) { Text(stringResource(R.string.album_group_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_group_cancel)) } })
}

/** Same icon rows and surface as the gallery menus, without a confirmation-dialog footer. */
@Composable
internal fun AlbumGroupActionDialog(
    title: String, icon: ImageVector, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .8f).dp),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp, shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(title, Modifier.weight(1f), style = EmphasizedTypography.TitleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.album_group_cancel)) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
            }
        }
    }
}

@Composable
internal fun AlbumGroupDissolveDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    GalleryDialog(
        onDismissRequest = onDismiss, icon = Icons.Outlined.FolderOff,
        title = { Text(stringResource(R.string.album_group_dissolve)) },
        text = {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            ConfirmationReminder(stringResource(R.string.album_group_dissolve_message), Icons.Outlined.Folder)
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.album_group_dissolve)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_group_cancel)) } },
    )
}
