package com.pixel.gallery.ui.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pixel.gallery.R
import com.pixel.gallery.model.TransferMode
import com.pixel.gallery.model.TransferSummary
import com.pixel.gallery.ui.components.GalleryDialog

/** Keep the result until it can be seen and dismissed; there is no retry action. */
@Composable
internal fun TransferResultFeedback(
    summary: TransferSummary?,
    error: String?,
    mode: TransferMode,
    onFinished: (TransferSummary) -> Unit,
    onClear: () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (lifecycleState != Lifecycle.State.RESUMED) return

    LaunchedEffect(summary, error) {
        if (summary != null && summary.failed == 0 && error == null) onFinished(summary)
    }
    if (error == null && (summary == null || summary.failed == 0)) return

    val action = summary?.mode ?: mode
    val moving = action == TransferMode.MOVE
    val reason = error ?: summary?.failures?.map { it.reason }?.distinct()?.singleOrNull()
    GalleryDialog(
        onDismissRequest = { if (summary?.completedAny == true) onFinished(summary) else onClear() },
        title = { Text(stringResource(if (moving) R.string.qs_warning_title else R.string.transfer_copy_incomplete)) },
        icon = Icons.Outlined.Warning,
        text = { Column {
            if (summary != null) {
                Text(stringResource(if (moving) R.string.transfer_move_failed_result else R.string.transfer_copy_failed_result,
                    summary.succeeded, summary.failed))
            }
            val reasonResource = transferFailureReason(reason.orEmpty())
            if (reasonResource != null || summary == null) {
                if (summary != null) Spacer(Modifier.height(8.dp))
                Text(stringResource(reasonResource ?: if (moving) R.string.qs_move_failed else R.string.transfer_copy_failed))
            }
        } },
        confirmButton = {
            Button(onClick = { if (summary?.completedAny == true) onFinished(summary) else onClear() }) {
                Text(stringResource(R.string.dialog_got_it))
            }
        },
    )
}

/** Convert known file-operation diagnostics into brief localized reasons. */
internal fun transferFailureReason(message: String): Int? {
    val text = message.lowercase(java.util.Locale.ROOT)
    return when {
        "already in the destination album" in text || "already in this album" in text ||
            "already in this folder" in text -> R.string.transfer_already_in_album
        "already being moved" in text -> R.string.transfer_already_moving
        "permission" in text || "access denied" in text -> R.string.qs_move_no_permission
        "occupied" in text || "filename already exists" in text -> R.string.qs_move_name_conflict
        "space left" in text || "disk full" in text -> R.string.qs_move_no_space
        "queue is full" in text || "wait for" in text -> R.string.qs_move_busy
        "destination album is unavailable" in text || "destination folder is unavailable" in text ||
            "original album is unavailable" in text -> R.string.qs_move_album_missing
        "source file no longer exists" in text || "source media record is unavailable" in text -> R.string.transfer_source_missing
        else -> null
    }
}
