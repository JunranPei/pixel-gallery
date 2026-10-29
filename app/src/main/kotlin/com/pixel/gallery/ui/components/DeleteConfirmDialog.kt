package com.pixel.gallery.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.R

@Composable
fun DeleteConfirmDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    isDeletePermanently: Boolean = false,
    onConfirm: () -> Unit
) {
    if (!visible) return
    GalleryDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        icon = Icons.Outlined.DeleteOutline,
        destructive = isDeletePermanently,
        text = { ConfirmationReminder(message, Icons.Outlined.DeleteOutline) },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismissRequest() },
                colors = if (isDeletePermanently) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
