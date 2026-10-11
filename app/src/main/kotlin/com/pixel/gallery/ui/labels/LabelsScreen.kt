package com.pixel.gallery.ui.labels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.model.LabelSummary
import com.pixel.gallery.model.isValidLabelName
import com.pixel.gallery.model.normalizeLabelName
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes
import com.pixel.gallery.ui.home.AlbumCoverCard
import com.pixel.gallery.ui.components.GalleryScrollbar
import com.pixel.gallery.ui.utils.pinchToZoomColumns

@Composable
fun labelDisplayName(label: LabelEntry): String =
    if (label.isSystem) stringResource(R.string.labels_favourites) else label.name

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LabelsScreen(
    labels: List<LabelSummary>,
    onBack: () -> Unit,
    onLabelClick: (Long) -> Unit,
    onCreateLabel: (String) -> Unit,
    onRenameLabel: (Long, String) -> Unit,
    onDeleteLabel: (Long) -> Unit,
    errorMessage: String? = null,
    onClearError: () -> Unit = {},
    isSaving: Boolean = false,
    columns: Int = 2,
    onColumnsChange: (Int) -> Unit = {}
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf<LabelEntry?>(null) }
    var deletingLabel by remember { mutableStateOf<LabelEntry?>(null) }
    var managing by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val gridColumns = columns.coerceIn(1, 4)
    val sortedLabels = remember(labels) { labels.sortedBy { if (it.label.isSystem) 0 else 1 } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.labels_title), style = EmphasizedTypography.TitleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back))
                    }
                },
                actions = {
                    IconButton(onClick = { managing = !managing }, enabled = !isSaving) {
                        Icon(
                            if (managing) Icons.Default.Done else Icons.Outlined.Edit,
                            stringResource(if (managing) R.string.labels_done else R.string.label_edit)
                        )
                    }
                    IconButton(onClick = { editingLabel = null; showNameDialog = true }, enabled = !isSaving) {
                        Icon(Icons.Default.Add, stringResource(R.string.labels_new))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                state = gridState,
                modifier = Modifier.fillMaxSize().pinchToZoomColumns(
                    currentColumns = gridColumns, onColumnsChange = onColumnsChange,
                    minColumns = 1, maxColumns = 4
                ),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                errorMessage?.let { message ->
                    item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                        Surface(shape = ExpressiveShapes.LargeIncreased, color = MaterialTheme.colorScheme.errorContainer) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = onClearError) { Text(stringResource(R.string.labels_dismiss_error)) }
                            }
                        }
                    }
                }
                items(sortedLabels, key = { it.label.id }) { summary ->
                    AlbumCoverCard(
                        title = labelDisplayName(summary.label),
                        subtitle = stringResource(R.string.labels_count, summary.count),
                        coverUri = summary.cover?.uri,
                        coverModifiedMillis = summary.cover?.dateModifiedMillis ?: 0L,
                        coverMimeType = summary.cover?.sourceMimeType,
                        coverSizeBytes = summary.cover?.sizeBytes,
                        coverRotationDegrees = summary.cover?.sourceRotationDegrees ?: 0,
                        columns = gridColumns,
                        emptyIcon = if (summary.label.isSystem) Icons.Default.Favorite else Icons.Outlined.Label,
                        enabled = !isSaving && (!managing || !summary.label.isSystem),
                        onClick = {
                            if (managing) {
                                editingLabel = summary.label
                                showNameDialog = true
                            } else onLabelClick(summary.label.id)
                        }
                    ) {
                        if (managing && !summary.label.isSystem) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = { editingLabel = summary.label; showNameDialog = true }, enabled = !isSaving) {
                                    Icon(Icons.Outlined.Edit, stringResource(R.string.labels_rename))
                                }
                                IconButton(onClick = { deletingLabel = summary.label }, enabled = !isSaving) {
                                    Icon(Icons.Outlined.Delete, stringResource(R.string.labels_delete))
                                }
                            }
                        }
                    }
                }
                if (labels.none { !it.label.isSystem }) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.labels_empty), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.labels_empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            FilledTonalButton(onClick = { editingLabel = null; showNameDialog = true }, enabled = !isSaving) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.labels_new))
                            }
                        }
                    }
                }
            }
            GalleryScrollbar(
                lazyGridState = gridState,
                layoutModifier = Modifier.align(Alignment.CenterEnd),
                onDragStateChanged = {}
            )
        }
    }
    if (showNameDialog) {
        var name by remember(editingLabel?.id) { mutableStateOf(editingLabel?.name.orEmpty()) }
        var attempted by remember { mutableStateOf(false) }
        val duplicate = labels.any { it.label.id != editingLabel?.id && it.label.normalizedName == normalizeLabelName(name) }
        val valid = isValidLabelName(name) && !duplicate
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(stringResource(if (editingLabel == null) R.string.labels_new else R.string.labels_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.labels_name)) },
                    singleLine = true,
                    isError = attempted && !valid,
                    supportingText = {
                        if (attempted && !valid) Text(stringResource(if (duplicate) R.string.labels_duplicate_name else R.string.labels_invalid_name))
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    attempted = true
                    if (valid) {
                        editingLabel?.let { onRenameLabel(it.id, name.trim()) } ?: onCreateLabel(name.trim())
                        showNameDialog = false
                    }
                }) { Text(stringResource(R.string.labels_save)) }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text(stringResource(R.string.labels_cancel)) } }
        )
    }
    deletingLabel?.let { label ->
        AlertDialog(
            onDismissRequest = { deletingLabel = null },
            title = { Text(stringResource(R.string.labels_delete_title, label.name)) },
            text = { Text(stringResource(R.string.labels_delete_message)) },
            confirmButton = { TextButton(onClick = { onDeleteLabel(label.id); deletingLabel = null }) { Text(stringResource(R.string.labels_delete)) } },
            dismissButton = { TextButton(onClick = { deletingLabel = null }) { Text(stringResource(R.string.labels_cancel)) } }
        )
    }
}
