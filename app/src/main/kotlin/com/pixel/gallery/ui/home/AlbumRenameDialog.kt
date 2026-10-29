package com.pixel.gallery.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.R
import com.pixel.gallery.model.Album
import com.pixel.gallery.model.isValidAlbumName
import com.pixel.gallery.ui.components.GalleryDialog
import kotlinx.coroutines.launch

@Composable
internal fun AlbumRenameDialog(
    album: Album,
    onDismiss: () -> Unit,
    onRename: suspend (String, String) -> Result<Unit>,
) {
    var name by remember(album.path) { mutableStateOf(album.name) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = isValidAlbumName(name)
    GalleryDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        icon = Icons.Outlined.Edit,
        title = { Text(stringResource(R.string.album_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text(stringResource(R.string.album_name)) },
                singleLine = true,
                enabled = !saving,
                isError = error != null || !valid,
                supportingText = {
                    if (error != null) Text(error!!)
                    else if (!valid) Text(stringResource(R.string.album_invalid_name))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = valid && !saving, onClick = {
                saving = true
                scope.launch {
                    onRename(album.path, name.trim())
                        .onSuccess { onDismiss() }
                        .onFailure { error = it.message }
                    saving = false
                }
            }) { Text(stringResource(R.string.labels_save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.labels_cancel)) }
        },
    )
}
