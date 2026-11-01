package com.pixel.gallery.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.model.AlbumGroup
import com.pixel.gallery.ui.components.GalleryDialog

@Composable
fun AlbumGroupNameDialog(groups: List<AlbumGroup>, editing: AlbumGroup? = null, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
    val valid = name.trim().isNotEmpty() && name.trim().length <= 60 && groups.none { it.id != editing?.id && it.name.equals(name.trim(), true) }
    GalleryDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing == null) R.string.album_group_new else R.string.album_group_rename)) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.album_group_name)) },
                isError = name.isNotEmpty() && !valid,
                supportingText = { if (name.isNotEmpty() && !valid) Text(stringResource(R.string.album_group_name_error)) })
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = valid) { Text(stringResource(R.string.album_group_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_group_cancel)) } })
}

@Composable
fun AlbumGroupPickerDialog(
    groups: List<AlbumGroup>, onDismiss: () -> Unit, onAssign: (String?) -> Unit, onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        AlbumGroupNameDialog(groups, onDismiss = { creating = false }, onSave = onCreate)
    } else GalleryDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_group_add)) },
        text = { Column(Modifier.fillMaxWidth()) {
            groups.forEach { group ->
                TextButton(onClick = { onAssign(group.id) }, modifier = Modifier.fillMaxWidth()) { Text(group.name) }
            }
            TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.album_group_new)) }
            if (groups.isNotEmpty()) TextButton(onClick = { onAssign(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.album_group_remove)) }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_group_cancel)) } })
}
