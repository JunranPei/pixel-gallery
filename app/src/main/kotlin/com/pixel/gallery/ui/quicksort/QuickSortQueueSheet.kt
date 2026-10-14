package com.pixel.gallery.ui.quicksort

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSortQueueSheet(
    state: QuickSortQueueState,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = ExpressiveShapes.ExtraLargeIncreased) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.qs_tasks), style = EmphasizedTypography.TitleLarge)
            if (state.jobs.any { !it.isTerminal }) {
                Text(stringResource(R.string.qs_queue_explanation), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.jobs.any { it.status == QuickSortJobStatus.Paused } ||
                (!state.serviceActive && state.jobs.any { it.status == QuickSortJobStatus.Queued })) {
                TextButton(onClick = onResume) { Text(stringResource(R.string.qs_resume)) }
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.jobs.isEmpty()) item { Text(stringResource(R.string.qs_no_tasks)) }
                items(state.jobs.asReversed(), key = { it.id }) { job ->
                    var expanded by remember { mutableStateOf(false) }
                    Surface(shape = ExpressiveShapes.LargeIncreased, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (job.undoOfJobId != null) stringResource(R.string.qs_undo_batch)
                                else job.destination.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(job.destination.path, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(when (job.status) {
                                QuickSortJobStatus.Queued -> R.string.qs_status_queued
                                QuickSortJobStatus.NeedsPermission -> R.string.qs_status_permission
                                QuickSortJobStatus.Running -> R.string.qs_status_running
                                QuickSortJobStatus.Completed -> R.string.qs_status_complete
                                QuickSortJobStatus.Cancelled -> R.string.qs_status_cancelled
                                QuickSortJobStatus.Paused -> R.string.qs_status_paused
                                QuickSortJobStatus.NeedsConflictDecision -> R.string.qs_conflict_title
                            }))
                            Text(stringResource(R.string.qs_task_counts, job.completed, job.total, job.failed))
                            if (job.resolved > 0) Text(stringResource(R.string.qs_resolved_on_retry, job.resolved),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!job.isTerminal) LinearProgressIndicator(
                                progress = { job.items.count { it.status !in setOf(QuickSortItemStatus.Queued, QuickSortItemStatus.Running) }.toFloat() / job.total.coerceAtLeast(1) },
                                drawStopIndicator = {},
                                modifier = Modifier.fillMaxWidth())
                            Row {
                                if (!job.isTerminal) TextButton(onClick = { onCancel(job.id) }, enabled = !job.cancelRequested) {
                                    Text(stringResource(if (job.cancelRequested) R.string.qs_cancelling else R.string.qs_cancel_remaining))
                                }
                                if (job.failed > 0) {
                                    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.qs_details)) }
                                    if (job.isTerminal) TextButton(onClick = { onRetry(job.id) }) { Text(stringResource(R.string.qs_retry_failed)) }
                                }
                            }
                            if (expanded) job.items.filter { it.status == QuickSortItemStatus.Failed }.take(50).forEach { item ->
                                Text("${java.io.File(item.entry.path).name}\n${item.error.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
