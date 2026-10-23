package com.pixel.gallery.ui.labels

import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.ConfirmationReminder
import com.pixel.gallery.ui.components.ConfirmationFact
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import com.pixel.gallery.ui.components.GalleryScrollbar
import com.pixel.gallery.ui.utils.pinchToZoomColumns
import com.pixel.gallery.ui.components.GalleryOverflowMenu
import com.pixel.gallery.ui.components.GalleryMenuRow
import com.pixel.gallery.ui.viewmodel.LabelSortMode
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import com.pixel.gallery.ui.components.SortDialog
import com.pixel.gallery.ui.components.SortCriterion

@Composable
fun labelDisplayName(label: LabelEntry): String =
    if (label.isSystem) stringResource(R.string.labels_favourites) else label.name

private data class LabelHeaderTransitionState(
    val isSearchMode: Boolean = false,
    val dragOffsetPx: Float = 0f,
)

private fun shouldSwitchLabelHeader(dragOffsetPx: Float, widthPx: Float, velocityPxPerSecond: Float): Boolean {
    if (widthPx <= 0f) return false
    return abs(dragOffsetPx) / widthPx >= 0.2f || abs(velocityPxPerSecond) / widthPx >= 1.25f
}

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
    onColumnsChange: (Int) -> Unit = {},
    sortMode: LabelSortMode = LabelSortMode.CUSTOM,
    onSortModeChange: (LabelSortMode) -> Unit = {},
    onMoveLabel: (Long, Int) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {}
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf<LabelEntry?>(null) }
    var deletingLabel by remember { mutableStateOf<LabelEntry?>(null) }
    var selectedManagementLabel by remember { mutableStateOf<LabelEntry?>(null) }
    var managing by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var headerTransition by remember { mutableStateOf(LabelHeaderTransitionState()) }
    var isHeaderAnimating by remember { mutableStateOf(false) }
    var headerWidthPx by remember { mutableFloatStateOf(0f) }
    val gridState = rememberLazyGridState()
    val gridColumns = columns.coerceIn(1, 4)
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val headerDraggableState = rememberDraggableState { delta ->
        if (!isHeaderAnimating && headerWidthPx > 0f) {
            headerTransition = headerTransition.copy(
                dragOffsetPx = (headerTransition.dragOffsetPx + delta).coerceIn(-headerWidthPx, headerWidthPx)
            )
        }
    }
    LaunchedEffect(headerTransition.isSearchMode) {
        if (headerTransition.isSearchMode) searchFocusRequester.requestFocus()
        else {
            focusManager.clearFocus(force = true)
            searchQuery = ""
        }
    }
    BackHandler(enabled = headerTransition.isSearchMode) {
        headerTransition = LabelHeaderTransitionState()
    }
    val sortedLabels = remember(labels, searchQuery) {
        val query = searchQuery.trim()
        labels.filter { query.isEmpty() || it.label.name.contains(query, ignoreCase = true) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    val dragOffsetPx = headerTransition.dragOffsetPx
                    val dragDirection = if (dragOffsetPx < 0f) -1f else 1f
                    val nextPageOffsetPx = dragOffsetPx - dragDirection * headerWidthPx
                    val titleOffsetPx = if (headerTransition.isSearchMode) nextPageOffsetPx else dragOffsetPx
                    val searchOffsetPx = if (headerTransition.isSearchMode) dragOffsetPx else nextPageOffsetPx
                    val headerPage: @Composable (Boolean, Modifier) -> Unit = { searchPage, pageModifier ->
                        Box(
                            modifier = pageModifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (searchPage) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(searchFocusRequester),
                                    placeholder = { Text("Search labels") },
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, "Clear search")
                                        }
                                    },
                                    singleLine = true,
                                    shape = CircleShape,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.labels_title),
                                    style = EmphasizedTypography.TitleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clipToBounds()
                            .onSizeChanged { headerWidthPx = it.width.toFloat() }
                            .draggable(
                                state = headerDraggableState,
                                orientation = Orientation.Horizontal,
                                enabled = !isHeaderAnimating,
                                onDragStopped = { velocity ->
                                    if (headerWidthPx <= 0f || headerTransition.dragOffsetPx == 0f) {
                                        headerTransition = headerTransition.copy(dragOffsetPx = 0f)
                                        return@draggable
                                    }
                                    val startingState = headerTransition
                                    val switchPage = shouldSwitchLabelHeader(
                                        startingState.dragOffsetPx, headerWidthPx, velocity
                                    )
                                    val direction = if (startingState.dragOffsetPx > 0f) 1f else -1f
                                    val targetOffset = if (switchPage) direction * headerWidthPx else 0f
                                    isHeaderAnimating = true
                                    try {
                                        val animation = Animatable(startingState.dragOffsetPx)
                                        animation.animateTo(targetOffset, tween(180)) {
                                            headerTransition = startingState.copy(dragOffsetPx = value)
                                        }
                                        headerTransition = LabelHeaderTransitionState(
                                            isSearchMode = if (switchPage) !startingState.isSearchMode else startingState.isSearchMode
                                        )
                                    } finally {
                                        isHeaderAnimating = false
                                    }
                                },
                            ),
                    ) {
                        if (!headerTransition.isSearchMode || dragOffsetPx != 0f) {
                            headerPage(false, Modifier.offset { IntOffset(titleOffsetPx.roundToInt(), 0) })
                        }
                        if (headerTransition.isSearchMode || dragOffsetPx != 0f) {
                            headerPage(true, Modifier.offset { IntOffset(searchOffsetPx.roundToInt(), 0) })
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back))
                    }
                },
                actions = {
                    Row {
                        if (managing) {
                            IconButton(onClick = { managing = false; selectedManagementLabel = null }) {
                                Icon(Icons.Default.Done, stringResource(R.string.labels_done))
                            }
                        } else {
                            IconButton(onClick = { managing = true }) {
                                Icon(Icons.Outlined.Edit, "Manage labels")
                            }
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            GalleryOverflowMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                GalleryMenuRow("Sort Labels", Icons.Default.Sort, accented = true, onClick = {
                                    showMenu = false
                                    showSortDialog = true
                                })
                                if (managing) {
                                    GalleryMenuRow(
                                        "Delete label",
                                        Icons.Outlined.Delete,
                                        accented = true,
                                        enabled = selectedManagementLabel != null && !isSaving,
                                        onClick = {
                                            deletingLabel = selectedManagementLabel
                                            showMenu = false
                                        },
                                    )
                                }
                                GalleryMenuRow(stringResource(R.string.labels_new), Icons.Default.Add, onClick = {
                                    editingLabel = null; showNameDialog = true; showMenu = false
                                })
                                GalleryMenuRow("Settings", Icons.Outlined.Edit, onClick = {
                                    showMenu = false; managing = true; onOpenSettings()
                                })
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                state = gridState,
                modifier = Modifier.weight(1f).pinchToZoomColumns(
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
                        selected = managing && selectedManagementLabel?.id == summary.label.id,
                        emptyIcon = if (summary.label.isSystem) Icons.Default.Favorite else Icons.Outlined.Label,
                        enabled = !isSaving && (!managing || !summary.label.isSystem),
                        onClick = {
                            if (managing) selectedManagementLabel = summary.label
                            else onLabelClick(summary.label.id)
                        }
                    ) {
                        if (managing && !summary.label.isSystem) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (sortMode == LabelSortMode.CUSTOM) {
                                    IconButton(onClick = { onMoveLabel(summary.label.id, -1) }, enabled = !isSaving) {
                                        Icon(Icons.Default.KeyboardArrowUp, "Move up")
                                    }
                                    IconButton(onClick = { onMoveLabel(summary.label.id, 1) }, enabled = !isSaving) {
                                        Icon(Icons.Default.KeyboardArrowDown, "Move down")
                                    }
                                }
                                IconButton(onClick = { editingLabel = summary.label; showNameDialog = true }, enabled = !isSaving) {
                                    Icon(Icons.Outlined.Edit, stringResource(R.string.labels_rename))
                                }
                            }
                        }
                    }
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
    deletingLabel?.let { label ->
        GalleryDialog(
            onDismissRequest = { deletingLabel = null },
            icon = Icons.Outlined.Delete,
            title = { Text(stringResource(R.string.labels_delete_title, label.name)) },
            text = { ConfirmationReminder(stringResource(R.string.labels_delete_message)) },
            confirmButton = { Button(onClick = { onDeleteLabel(label.id); deletingLabel = null }) { Text(stringResource(R.string.labels_delete)) } },
            dismissButton = { TextButton(onClick = { deletingLabel = null }) { Text(stringResource(R.string.labels_cancel)) } }
        )
    }
    val sortCriteria = remember {
        listOf(
            SortCriterion("NAME", "Name", "A to Z", "Z to A"),
            SortCriterion("COUNT", "Count", "Least first", "Most first"),
            SortCriterion("CUSTOM", "Custom", "Manual order", "Manual order"),
        )
    }
    AnimatedVisibility(
        visible = showSortDialog,
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
        exit = fadeOut() + scaleOut(targetScale = 0.92f),
    ) {
        val currentCriterion = when (sortMode) {
            LabelSortMode.NAME_ASC, LabelSortMode.NAME_DESC -> "NAME"
            LabelSortMode.COUNT_ASC, LabelSortMode.COUNT_DESC -> "COUNT"
            LabelSortMode.CUSTOM -> "CUSTOM"
        }
        val currentDirection = when (sortMode) {
            LabelSortMode.NAME_DESC, LabelSortMode.COUNT_DESC -> "DESC"
            else -> "ASC"
        }
        SortDialog(
            visible = showSortDialog,
            onDismissRequest = { showSortDialog = false },
            title = "Sort Labels By",
            criteria = sortCriteria,
            initialCriterion = currentCriterion,
            initialDirection = currentDirection,
            onConfirm = { criterion, direction ->
                onSortModeChange(
                    when (criterion) {
                        "NAME" -> if (direction == "DESC") LabelSortMode.NAME_DESC else LabelSortMode.NAME_ASC
                        "COUNT" -> if (direction == "DESC") LabelSortMode.COUNT_DESC else LabelSortMode.COUNT_ASC
                        else -> LabelSortMode.CUSTOM
                    }
                )
                showSortDialog = false
            },
        )
    }
}
