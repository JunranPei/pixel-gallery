package com.pixel.gallery.ui.labels

import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.AlbumSelectionTopBar
import androidx.activity.compose.BackHandler
import com.pixel.gallery.ui.components.ConfirmationReminder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.pixel.gallery.ui.home.AlbumGrid
import com.pixel.gallery.ui.components.GalleryOverflowMenu
import com.pixel.gallery.ui.components.GalleryMenuRow
import com.pixel.gallery.model.AlbumSortOrder
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import com.pixel.gallery.ui.components.AlbumSortDialog
import com.pixel.gallery.ui.gallery.AlbumSearchTitle
import com.pixel.gallery.ui.components.galleryContentEdge

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
    onDeleteLabels: (Set<Long>) -> Unit,
    errorMessage: String? = null,
    onClearError: () -> Unit = {},
    isSaving: Boolean = false,
    columns: Int = 2,
    onColumnsChange: (Int) -> Unit = {},
    sortMode: AlbumSortOrder = AlbumSortOrder.NAME_ASC,
    onSortModeChange: (AlbumSortOrder) -> Unit = {},
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf<LabelEntry?>(null) }
    var deletingLabels by remember { mutableStateOf(emptyList<LabelEntry>()) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()
    val toggleSelection: (Long) -> Unit = { id ->
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }
    LaunchedEffect(labels) {
        selectedIds = selectedIds.intersect(labels.filterNot { it.label.isSystem }.map { it.label.id }.toSet())
    }
    var showMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchMode by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val gridColumns = columns.coerceIn(2, 6)
    val sortedLabels = remember(labels, searchQuery) {
        val query = searchQuery.trim()
        labels.filter { query.isEmpty() || it.label.name.contains(query, ignoreCase = true) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selectionMode) {
                AlbumSelectionTopBar(
                    count = selectedIds.size,
                    enabled = !isSaving,
                    renameDescription = stringResource(R.string.labels_rename),
                    onClear = { selectedIds = emptySet() },
                    onRename = {
                        editingLabel = labels.firstOrNull { it.label.id == selectedIds.singleOrNull() }?.label
                        showNameDialog = editingLabel != null
                    },
                    onDelete = { deletingLabels = labels.filter { it.label.id in selectedIds }.map { it.label } },
                )
            } else {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                title = {
                    AlbumSearchTitle(
                        isSearchMode = searchMode,
                        onSearchModeChange = { searchMode = it },
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        searchPlaceholder = stringResource(R.string.labels_search),
                        clearSearchDescription = stringResource(R.string.album_clear_search),
                        title = {
                            Text(
                                stringResource(R.string.labels_title),
                                style = EmphasizedTypography.TitleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back))
                    }
                },
                actions = {
                    Row {
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            GalleryOverflowMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                GalleryMenuRow("Sort Labels", Icons.Default.Sort, accented = true, onClick = {
                                    showMenu = false
                                    showSortDialog = true
                                })
                                GalleryMenuRow(stringResource(R.string.labels_new), Icons.Default.Add, onClick = {
                                    editingLabel = null; showNameDialog = true; showMenu = false
                                })
                            }
                        }
                    }
                }
            )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).galleryContentEdge()) {
            AlbumGrid(
                columns = gridColumns,
                onColumnsChange = onColumnsChange,
                state = gridState,
                modifier = Modifier.fillMaxSize(),
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
                        enabled = !isSaving,
                        selected = summary.label.id in selectedIds,
                        selectionMode = selectionMode && !summary.label.isSystem,
                        onClick = {
                            if (!selectionMode) onLabelClick(summary.label.id)
                            else if (!summary.label.isSystem) toggleSelection(summary.label.id)
                        },
                        onLongClick = if (summary.label.isSystem) null else {{ toggleSelection(summary.label.id) }},
                    )
                }
                if (sortedLabels.none { !it.label.isSystem }) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.labels_empty), style = MaterialTheme.typography.titleMedium)
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
        }
    }
    if (showNameDialog) {
        var name by remember(editingLabel?.id) { mutableStateOf(editingLabel?.name.orEmpty()) }
        var attempted by remember { mutableStateOf(false) }
        val duplicate = labels.any { it.label.id != editingLabel?.id && it.label.normalizedName == normalizeLabelName(name) }
        val valid = isValidLabelName(name) && !duplicate
        GalleryDialog(
            onDismissRequest = { showNameDialog = false },
            icon = Icons.Outlined.Label,
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
                Button(onClick = {
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
    if (deletingLabels.isNotEmpty()) {
        GalleryDialog(
            onDismissRequest = { deletingLabels = emptyList() },
            icon = Icons.Outlined.Delete,
            title = { Text(if (deletingLabels.size == 1) stringResource(R.string.labels_delete_title, deletingLabels.single().name)
                else stringResource(R.string.labels_delete_selected_title, deletingLabels.size)) },
            text = { ConfirmationReminder(stringResource(R.string.labels_delete_message)) },
            confirmButton = { Button(enabled = !isSaving, onClick = {
                onDeleteLabels(deletingLabels.map { it.id }.toSet())
                deletingLabels = emptyList()
                selectedIds = emptySet()
            }) { Text(stringResource(R.string.labels_delete)) } },
            dismissButton = { TextButton(onClick = { deletingLabels = emptyList() }) { Text(stringResource(R.string.labels_cancel)) } }
        )
    }
    AnimatedVisibility(
        visible = showSortDialog,
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
        exit = fadeOut() + scaleOut(targetScale = 0.92f),
    ) {
        AlbumSortDialog(
            visible = showSortDialog,
            onDismissRequest = { showSortDialog = false },
            title = "Sort Labels By",
            initialOrder = sortMode,
            onConfirm = { order ->
                onSortModeChange(order)
                showSortDialog = false
            },
        )
    }
}
