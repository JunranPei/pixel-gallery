package com.pixel.gallery.ui.deduplication

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.MediaEntry
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A read-only scan and review until the user confirms the selected copies. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateReviewDialog(
    state: DuplicateUiState,
    onDismiss: () -> Unit,
    onToggle: (Long) -> Unit,
    onConfirm: () -> Unit,
) {
    if (state.phase == DuplicatePhase.IDLE) return

    var showConfirmation by remember(state.phase) { mutableStateOf(false) }
    val context = LocalContext.current
    val groups = state.result?.groups.orEmpty()
    val selectedEntries = groups.flatMap { it.entries }
        .filter { it.contentId in state.selectedIds }
    val selectedBytes = selectedEntries.sumOf { it.sizeBytes }
    val copyIds = groups.flatMap { group -> group.entries.drop(1).map(MediaEntry::contentId) }
    val allCopiesSelected = copyIds.isNotEmpty() && copyIds.all { it in state.selectedIds }
    val canDismiss = state.phase != DuplicatePhase.AWAITING_PERMISSION &&
        state.phase != DuplicatePhase.VERIFYING

    Dialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = false,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.duplicate_review_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = canDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.duplicate_close))
                        }
                    },
                )
            },
            bottomBar = {
                if (state.phase == DuplicatePhase.REVIEW && groups.isNotEmpty()) {
                    Surface(
                        // Keep this area opaque and aligned with the page background so it
                        // reads as part of the screen instead of a floating color block.
                        color = MaterialTheme.colorScheme.background,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(
                                        R.string.duplicate_selection_summary,
                                        selectedEntries.size,
                                        Formatter.formatShortFileSize(context, selectedBytes),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        toggleCopySelection(copyIds, state.selectedIds, onToggle)
                                    },
                                ) {
                                    Text(
                                        stringResource(
                                            if (allCopiesSelected) {
                                                R.string.duplicate_clear_selection
                                            } else {
                                                R.string.duplicate_select_copies
                                            },
                                        ),
                                    )
                                }
                            }
                            if (!state.canTrash) {
                                Text(
                                    stringResource(R.string.duplicate_trash_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(
                                onClick = { showConfirmation = true },
                                enabled = selectedEntries.isNotEmpty() && state.canTrash,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.duplicate_move_to_bin))
                            }
                        }
                    }
                }
            },
        ) { padding ->
            when (state.phase) {
                DuplicatePhase.IDLE,
                DuplicatePhase.SCANNING,
                DuplicatePhase.VALIDATING,
                DuplicatePhase.AWAITING_PERMISSION,
                DuplicatePhase.VERIFYING -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(state.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(24.dp))
                        val progress = state.progress
                        if (state.phase == DuplicatePhase.SCANNING && progress != null && progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.duplicate_scan_progress, progress.completed, progress.total))
                        } else {
                            CircularProgressIndicator()
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(
                                when (state.phase) {
                                    DuplicatePhase.VALIDATING -> R.string.duplicate_validating
                                    DuplicatePhase.AWAITING_PERMISSION -> R.string.duplicate_awaiting_permission
                                    DuplicatePhase.VERIFYING -> R.string.duplicate_verifying
                                    else -> R.string.duplicate_scanning
                                },
                            ),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.duplicate_scan_explanation),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                DuplicatePhase.REVIEW -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                    ) {
                        item("summary") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(state.title, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.duplicate_scan_short), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.duplicate_scan_summary, state.result?.scannedCount ?: 0, groups.size), style = MaterialTheme.typography.bodyMedium)
                                val skipped = state.result?.skippedCount ?: 0
                                if (skipped > 0) {
                                    Text(
                                        stringResource(R.string.duplicate_scan_skipped, skipped),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                if (groups.isEmpty()) {
                                    Text(
                                        stringResource(R.string.duplicate_none_found),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                        groups.forEachIndexed { index, group ->
                            item("group-${group.keeper.contentId}") {
                                DuplicateGroupCard(
                                    index = index,
                                    group = group,
                                    selectedIds = state.selectedIds,
                                    onToggle = onToggle,
                                )
                            }
                        }
                        item("end") { Spacer(Modifier.height(16.dp)) }
                    }
                }

                DuplicatePhase.COMPLETE,
                DuplicatePhase.ERROR -> {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                        item {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(state.title, style = MaterialTheme.typography.titleMedium)
                                if (state.phase == DuplicatePhase.ERROR) {
                                    Text(
                                        stringResource(R.string.duplicate_failed),
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                    state.error?.let { Text(it) }
                                } else {
                                    Text(
                                        stringResource(
                                            if (state.cancelled) R.string.duplicate_cleanup_cancelled
                                            else R.string.duplicate_cleanup_complete,
                                        ),
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                }
                                Text(stringResource(R.string.duplicate_removed_summary, state.removedCount))
                                if (state.skippedCount > 0) {
                                    Text(stringResource(R.string.duplicate_cleanup_skipped, state.skippedCount))
                                }
                                if (state.removedCount > 0) {
                                    Text(stringResource(R.string.duplicate_restore_hint))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showConfirmation && state.phase == DuplicatePhase.REVIEW) {
            AlertDialog(
                onDismissRequest = { showConfirmation = false },
                title = { Text(stringResource(R.string.duplicate_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.duplicate_confirm_message,
                            selectedEntries.size,
                            state.title,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = selectedEntries.isNotEmpty() && state.canTrash,
                        onClick = {
                            showConfirmation = false
                            onConfirm()
                        },
                    ) { Text(stringResource(R.string.duplicate_move_to_bin)) }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmation = false }) {
                        Text(stringResource(R.string.duplicate_cancel))
                    }
                },
            )
        }
    }
}

private fun toggleCopySelection(
    copyIds: List<Long>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    val selectAll = copyIds.any { it !in selectedIds }
    copyIds
        .filter { if (selectAll) it !in selectedIds else it in selectedIds }
        .forEach(onToggle)
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DuplicateGroupCard(
    index: Int,
    group: com.pixel.gallery.model.DuplicateGroup,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    val entries = group.entries
    val copyIds = entries.drop(1).map(MediaEntry::contentId)
    val allCopiesSelected = copyIds.isNotEmpty() && copyIds.all { it in selectedIds }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.duplicate_group_title, index + 1, entries.size), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.duplicate_group_size, Formatter.formatShortFileSize(LocalContext.current, entries.sumOf { it.sizeBytes })),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = {
                    toggleCopySelection(copyIds, selectedIds, onToggle)
                }) {
                    Text(
                        stringResource(
                            if (allCopiesSelected) {
                                R.string.duplicate_clear_selection
                            } else {
                                R.string.duplicate_select_copies
                            },
                        ),
                    )
                }
            }
            entries.forEachIndexed { position, entry ->
                val entrySelected = entry.contentId in selectedIds
                val unselected = entries.count { it.contentId !in selectedIds }
                DuplicateMediaRow(
                    entry = entry,
                    recommended = position == 0,
                    selected = entrySelected,
                    disabled = !entrySelected && unselected == 1,
                    onToggle = { onToggle(entry.contentId) },
                )
                if (position < entries.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DuplicateMediaRow(
    entry: MediaEntry,
    recommended: Boolean,
    selected: Boolean,
    disabled: Boolean,
    onToggle: () -> Unit = {},
) {
    val context = LocalContext.current
    val rowModifier = Modifier.toggleable(
        value = selected,
        role = Role.Checkbox,
        enabled = !disabled,
        onValueChange = { onToggle() },
    )
    Row(
        modifier = rowModifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            GlideImage(
                model = entry.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(if (recommended && !selected) R.string.duplicate_recommended else R.string.duplicate_copy),
                color = if (recommended && !selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (recommended && !selected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(File(entry.path).name.ifBlank { entry.uri }, style = MaterialTheme.typography.bodyMedium)
            val folder = File(entry.path).parentFile?.name
                ?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.duplicate_unknown_folder)
            val timestamp = entry.bestTimestamp.takeIf { it > 0L }
                ?: entry.sourceDateTakenMillis
                ?: (entry.dateAddedSecs * 1000L).takeIf { it > 0L }
            val date = timestamp?.let {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
            }
            Text(
                listOfNotNull(
                    folder,
                    Formatter.formatShortFileSize(context, entry.sizeBytes),
                    date,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Checkbox(checked = selected, enabled = !disabled, onCheckedChange = null)
            Text(
                stringResource(if (selected) R.string.duplicate_move_status else R.string.duplicate_keep_status),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
