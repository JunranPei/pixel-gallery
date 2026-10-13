package com.pixel.gallery.ui.quicksort

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.os.Build
import android.os.Environment
import com.pixel.gallery.R
import com.pixel.gallery.model.ConflictPolicy
import com.pixel.gallery.model.QuickSortJob
import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.ConfirmationReminder
import com.pixel.gallery.ui.transfer.ConflictChoice

@Composable
internal fun QuickSortConflictDialog(job: QuickSortJob, onResolve: (ConflictPolicy) -> Unit, onCancel: () -> Unit) {
    var policy by remember(job.id) { mutableStateOf(ConflictPolicy.KEEP_BOTH) }
    GalleryDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.qs_conflict_title)) },
        icon = Icons.Outlined.Warning,
        text = {
            Column {
                ConfirmationReminder(stringResource(R.string.qs_conflict_body, job.conflictCount, job.destination.displayName))
                Spacer(Modifier.height(12.dp))
                ConflictChoice(stringResource(R.string.qs_keep_both), stringResource(R.string.qs_keep_both_description),
                    policy == ConflictPolicy.KEEP_BOTH, true) { policy = ConflictPolicy.KEEP_BOTH }
                ConflictChoice(stringResource(R.string.qs_skip_conflicts), stringResource(R.string.qs_skip_conflicts_description),
                    policy == ConflictPolicy.SKIP, true) { policy = ConflictPolicy.SKIP }
                val canReplace = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
                ConflictChoice(stringResource(R.string.qs_replace),
                    stringResource(if (canReplace) R.string.qs_replace_description else R.string.qs_replace_permission),
                    policy == ConflictPolicy.REPLACE, canReplace) { policy = ConflictPolicy.REPLACE }
            }
        },
        confirmButton = { Button(onClick = { onResolve(policy) }) { Text(stringResource(R.string.qs_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
internal fun QuickSortWarningDialog(message: String, onDismiss: () -> Unit) {
    GalleryDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qs_warning_title)) },
        icon = Icons.Outlined.Warning,
        text = { Column {
            ConfirmationReminder(message, Icons.Outlined.Warning)
            ConfirmationReminder(stringResource(R.string.qs_warning_review))
        } },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}
