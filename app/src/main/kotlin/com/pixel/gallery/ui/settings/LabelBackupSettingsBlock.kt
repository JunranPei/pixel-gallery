package com.pixel.gallery.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel.gallery.R
import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.LabelBackupOperation
import com.pixel.gallery.ui.viewmodel.LabelBackupViewModel
import java.time.LocalDate

/** Label backup actions are shown inline; previews and results remain dialogs. */
@Composable
fun LabelBackupSettingsBlock(
    viewModel: LabelBackupViewModel = hiltViewModel(),
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy = state.operation != null
    var showDetails by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) viewModel.export(it)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) viewModel.preview(it)
    }
    SettingsGroup(
        itemCount = if (busy) 1 else 2,
        modifier = modifier,
        title = title,
        description = if (title != null) stringResource(R.string.label_backup_menu_body) else null,
    ) { index ->
        if (busy) {
            val operation = stringResource(when (state.operation) {
                LabelBackupOperation.EXPORT -> R.string.label_backup_exporting
                LabelBackupOperation.SCAN -> R.string.label_backup_scanning
                else -> R.string.label_backup_importing
            })
            ListItem(
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                headlineContent = { Text(stringResource(R.string.label_backup_title), style = EmphasizedTypography.LabelLarge) },
                supportingContent = {
                    Text(if (state.total > 0) "$operation ${state.completed} / ${state.total}" else operation)
                },
                leadingContent = { Icon(Icons.Outlined.Label, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
            )
        } else if (index == 0) {
            SettingsClickItem(
                title = stringResource(R.string.label_backup_export),
                icon = Icons.Outlined.FileUpload,
                onClick = { exporter.launch("pixel-gallery-labels-${LocalDate.now()}.json") },
            )
        } else {
            SettingsClickItem(
                title = stringResource(R.string.label_backup_import),
                icon = Icons.Outlined.FileDownload,
                onClick = { importer.launch(arrayOf("*/*")) },
            )
        }
    }

    val preview = state.preview
    if (preview != null && !busy && state.error == null) {
        val missing = preview.resolution.missing
        val ambiguous = preview.resolution.ambiguous
        if (showDetails) {
            val height = (LocalConfiguration.current.screenHeightDp.dp - 120.dp).coerceIn(240.dp, 640.dp)
            Dialog(onDismissRequest = { showDetails = false }) {
                Surface(shape = SettingsBlockPosition.Single.shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.height(height).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.label_backup_details), style = MaterialTheme.typography.titleLarge)
                        val labelNames = preview.backup.labels.associate { it.key to it.name }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (missing.isNotEmpty()) item { Text(stringResource(R.string.label_backup_missing, missing.size), style = MaterialTheme.typography.titleMedium) }
                            items(missing, key = { "missing-${it.key}" }) { file ->
                                Column {
                                    Text(file.relativePath.ifBlank { file.fileName }, style = MaterialTheme.typography.bodyMedium)
                                    Text(file.labelKeys.mapNotNull { labelNames[it] }.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (ambiguous.isNotEmpty()) item { Text(stringResource(R.string.label_backup_ambiguous, ambiguous.size), style = MaterialTheme.typography.titleMedium) }
                            items(ambiguous, key = { "ambiguous-${it.key}" }) { file ->
                                Column {
                                    Text(file.relativePath.ifBlank { file.fileName }, style = MaterialTheme.typography.bodyMedium)
                                    Text(file.labelKeys.mapNotNull { labelNames[it] }.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        TextButton(onClick = { showDetails = false }, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                            Text(stringResource(R.string.label_backup_ok))
                        }
                    }
                }
            }
        } else GalleryDialog(onDismissRequest = viewModel::clearPreview,
            title = { Text(stringResource(if (state.importResult == null) R.string.label_backup_preview else R.string.label_backup_import_done)) },
            text = {
                Text(stringResource(R.string.label_backup_matches, preview.resolution.matches.size, preview.backup.media.size))
                Text(stringResource(R.string.label_backup_label_count, preview.backup.labels.size))
                if (state.importResult != null) {
                    val result = state.importResult!!
                    Text(stringResource(R.string.label_backup_import_result, result.media, result.labelsAdded, result.linksAdded))
                } else Text(stringResource(R.string.label_backup_merge_note))
                if (preview.unreadable > 0) Text(stringResource(R.string.label_backup_unreadable, preview.unreadable))
                if (preview.backup.skippedMedia > 0) Text(stringResource(R.string.label_backup_source_skipped, preview.backup.skippedMedia))
                if (missing.isNotEmpty()) { Text(stringResource(R.string.label_backup_missing, missing.size)); Text(stringResource(R.string.label_backup_missing_note)) }
                if (ambiguous.isNotEmpty()) { Text(stringResource(R.string.label_backup_ambiguous, ambiguous.size)); Text(stringResource(R.string.label_backup_ambiguous_note)) }
                if (missing.isNotEmpty() || ambiguous.isNotEmpty()) TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.label_backup_details)) }
            },
            confirmButton = { Button(onClick = if (state.importResult == null) viewModel::apply else viewModel::clearPreview) {
                Text(stringResource(if (state.importResult == null) R.string.label_backup_restore else R.string.label_backup_ok))
            } },
            dismissButton = { if (state.importResult == null) TextButton(onClick = viewModel::clearPreview) { Text(stringResource(R.string.qs_cancel)) } })
    }
    val exported = state.exportResult
    val error = state.error
    if (exported != null || error != null) GalleryDialog(onDismissRequest = viewModel::dismissMessage,
        title = { Text(stringResource(if (error != null) R.string.label_backup_error else R.string.label_backup_export_done)) },
        text = { Text(error ?: stringResource(R.string.label_backup_export_result, exported!!.labels, exported.media, exported.skipped)) },
        confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.label_backup_ok)) } })
}
