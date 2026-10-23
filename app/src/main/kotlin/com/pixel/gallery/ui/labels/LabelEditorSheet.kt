package com.pixel.gallery.ui.labels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.model.LabelSummary
import com.pixel.gallery.model.LabelEditRequest
import com.pixel.gallery.model.LabelSelection
import com.pixel.gallery.model.isValidLabelName
import com.pixel.gallery.model.labelSelection
import com.pixel.gallery.model.normalizeLabelName
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes
import com.pixel.gallery.ui.components.GalleryScrollbar
import com.pixel.gallery.ui.components.SelectionBottomSheet
import com.pixel.gallery.ui.home.AlbumCoverCard
import com.pixel.gallery.ui.utils.pinchToZoomColumns

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LabelEditorSheet(
    labels: List<LabelEntry>,
    labelSummaries: List<LabelSummary> = emptyList(),
    memberships: List<MediaLabelEntry>,
    selectedIds: Set<Long>,
    recentLabelIds: List<Long> = emptyList(),
    columns: Int = 2,
    onColumnsChange: (Int) -> Unit = {},
    onDismiss: () -> Unit,
    onApply: (LabelEditRequest) -> Unit,
    onReorderLabels: (List<Long>) -> Unit = {},
    isSaving: Boolean = false,
    errorMessage: String? = null
) {
    var query by rememberSaveable(selectedIds) { mutableStateOf("") }
    var changes by rememberSaveable(selectedIds, stateSaver = LabelEditsSaver) {
        mutableStateOf<Map<Long, Boolean>>(emptyMap())
    }
    var newNames by rememberSaveable(selectedIds) { mutableStateOf<List<String>>(emptyList()) }
    val gridColumns = columns.coerceIn(2, 6)
    val favouriteName = stringResource(R.string.labels_favourites)
    val normalizedQuery = normalizeLabelName(query)
    val matchingLabels = labels.filter {
        normalizeLabelName(if (it.isSystem) favouriteName else it.name).contains(normalizedQuery)
    }
    val selectedLabels = matchingLabels.filter { label ->
        changes[label.id] ?: (labelSelection(label.id, selectedIds, memberships) != LabelSelection.NONE)
    }
    var selectedOrder by rememberSaveable(selectedIds) { mutableStateOf(selectedLabels.map { it.id }) }
    LaunchedEffect(selectedLabels.map { it.id }) {
        val ids = selectedLabels.map { it.id }
        selectedOrder = selectedOrder.filter { it in ids } + ids.filterNot { it in selectedOrder }
    }
    val selectedById = selectedLabels.associateBy { it.id }
    val summariesById = labelSummaries.associateBy { it.label.id }
    val orderedSelectedLabels = selectedOrder.mapNotNull(selectedById::get)
    val allLabels = matchingLabels
    val matchingDrafts = newNames.filter { normalizeLabelName(it).contains(normalizedQuery) }
    val canCreate = isValidLabelName(query) && labels.none {
        it.normalizedName == normalizedQuery || (it.isSystem && normalizeLabelName(favouriteName) == normalizedQuery)
    } && newNames.none { normalizeLabelName(it) == normalizedQuery }
    val finishEditing = rememberUpdatedState<() -> Unit> {
        if (!isSaving) {
            if (changes.isEmpty() && newNames.isEmpty()) onDismiss()
            else onApply(LabelEditRequest(changes, newNames))
        }
    }
    val gridState = rememberLazyGridState()

    fun moveSelected(id: Long, delta: Int) {
        val index = selectedOrder.indexOf(id)
        val target = index + delta
        if (index !in selectedOrder.indices || target !in selectedOrder.indices) return
        val next = selectedOrder.toMutableList().also {
            val value = it.removeAt(index)
            it.add(target, value)
        }
        selectedOrder = next
        val orderedGlobal = next + labels.filter { !it.isSystem && it.id !in next }.map { it.id }
        onReorderLabels(orderedGlobal)
    }

    SelectionBottomSheet(
        title = stringResource(R.string.label_edit),
        subtitle = stringResource(R.string.labels_selection_count, selectedIds.size),
        onDismiss = { finishEditing.value() },
        confirmDismiss = {
            if (!isSaving) finishEditing.value()
            false
        },
    ) {
        if (isSaving) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.labels_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                shape = ExpressiveShapes.LargeIncreased,
                singleLine = true,
                enabled = !isSaving
            )
            if (orderedSelectedLabels.isNotEmpty()) {
                Text(stringResource(R.string.labels_selected), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    orderedSelectedLabels.forEach { label ->
                        val title = if (label.isSystem) favouriteName else label.name
                        AssistChip(
                            onClick = {},
                            label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    if (!isSaving) changes = changes + (label.id to false)
                                }, enabled = !isSaving, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, stringResource(R.string.labels_remove_from), modifier = Modifier.size(16.dp))
                                }
                            },
                            modifier = Modifier.pointerInput(label.id, isSaving) {
                                detectDragGesturesAfterLongPress(
                                    onDragEnd = {},
                                    onDragCancel = {},
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (dragAmount.x > 48f) moveSelected(label.id, 1)
                                        else if (dragAmount.x < -48f) moveSelected(label.id, -1)
                                    }
                                )
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().pinchToZoomColumns(
                        currentColumns = gridColumns,
                        onColumnsChange = onColumnsChange,
                        minColumns = 2,
                        maxColumns = 6,
                    ),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                item(key = "all-header", span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.labels_all), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(allLabels, key = { it.id }) { label ->
                    val initial = labelSelection(label.id, selectedIds, memberships)
                    val state = changes[label.id]?.let { if (it) ToggleableState.On else ToggleableState.Off }
                        ?: when (initial) {
                            LabelSelection.NONE -> ToggleableState.Off
                            LabelSelection.SOME -> ToggleableState.Indeterminate
                            LabelSelection.ALL -> ToggleableState.On
                        }
                    val onToggle = { changes = changes + (label.id to (state != ToggleableState.On)) }
                    val summary = summariesById[label.id]
                    if (summary != null) {
                        AlbumCoverCard(
                            title = if (label.isSystem) favouriteName else label.name,
                            subtitle = stringResource(R.string.labels_count, summary.count),
                            coverUri = summary.cover?.uri,
                            coverModifiedMillis = summary.cover?.dateModifiedMillis ?: 0L,
                            coverMimeType = summary.cover?.sourceMimeType,
                            coverSizeBytes = summary.cover?.sizeBytes,
                            coverRotationDegrees = summary.cover?.sourceRotationDegrees ?: 0,
                            columns = gridColumns,
                            selected = state != ToggleableState.Off,
                            enabled = !isSaving,
                            onClick = onToggle,
                            emptyIcon = if (label.isSystem) Icons.Default.Favorite else Icons.Outlined.Label,
                        )
                    } else {
                        LabelCheckboxRow(
                            title = if (label.isSystem) favouriteName else label.name,
                            subtitle = if (state == ToggleableState.Indeterminate) stringResource(R.string.labels_mixed) else null,
                            state = state,
                            isFavourite = label.isSystem,
                            enabled = !isSaving,
                            onClick = onToggle,
                        )
                    }
                }
                items(matchingDrafts, key = { "draft:$it" }) { name ->
                    LabelCheckboxRow(
                        title = name,
                        subtitle = stringResource(R.string.labels_pending),
                        state = ToggleableState.On,
                        isFavourite = false,
                        enabled = !isSaving,
                        onClick = { newNames = newNames - name }
                    )
                }
                if (canCreate) {
                    item(key = "create", span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = { newNames = newNames + query.trim(); query = "" }, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.labels_new_from_query, query.trim()), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                errorMessage?.let {
                    item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                            TextButton(
                                onClick = {
                                    changes = emptyMap()
                                    newNames = emptyList()
                                    onDismiss()
                                },
                                enabled = !isSaving,
                                modifier = Modifier.align(Alignment.End)
                            ) { Text(stringResource(R.string.labels_discard_changes)) }
                        }
                    }
                }
                }
                GalleryScrollbar(
                    lazyGridState = gridState,
                    layoutModifier = Modifier.align(Alignment.CenterEnd),
                    onDragStateChanged = {},
                )
            }
    }
}

private val LabelEditsSaver = mapSaver<Map<Long, Boolean>>(
    save = { edits -> edits.mapKeys { it.key.toString() } },
    restore = { saved -> saved.entries.associate { it.key.toLong() to (it.value as Boolean) } }
)

@Composable
private fun LabelCheckboxRow(
    title: String,
    subtitle: String?,
    state: ToggleableState,
    isFavourite: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .triStateToggleable(state = state, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isFavourite) Icons.Default.Favorite else Icons.Outlined.Label, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            TriStateCheckbox(state = state, onClick = null, enabled = enabled)
        }
    }
}
