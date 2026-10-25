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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.model.LabelSummary
import com.pixel.gallery.model.commonLabelIds
import com.pixel.gallery.model.LabelEditRequest
import com.pixel.gallery.model.isValidLabelName
import com.pixel.gallery.model.normalizeLabelName
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes
import com.pixel.gallery.ui.components.GalleryScrollbar
import com.pixel.gallery.ui.components.SelectionGrid
import com.pixel.gallery.ui.components.SelectionSearchField
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
    onApply: (LabelEditRequest, () -> Unit) -> Unit,
    onReorderLabels: (List<Long>) -> Unit = {},
    isSaving: Boolean = false,
    errorMessage: String? = null,
    onPanelHeightChange: (androidx.compose.ui.unit.Dp) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val gridColumns = columns.coerceIn(2, 6)
    val favouriteName = stringResource(R.string.labels_favourites)
    val normalizedQuery = normalizeLabelName(query)
    val matchingLabels = labels.filter {
        normalizeLabelName(if (it.isSystem) favouriteName else it.name).contains(normalizedQuery)
    }
    val sharedIds = remember(selectedIds, memberships) { commonLabelIds(selectedIds, memberships) }
    val selectedLabels = labels.filter { it.id in sharedIds }
    var selectedOrder by rememberSaveable(selectedIds) { mutableStateOf(selectedLabels.map { it.id }) }
    LaunchedEffect(selectedLabels.map { it.id }) {
        val ids = selectedLabels.map { it.id }
        selectedOrder = selectedOrder.filter { it in ids } + ids.filterNot { it in selectedOrder }
    }
    val selectedById = selectedLabels.associateBy { it.id }
    val summariesById = labelSummaries.associateBy { it.label.id }
    val orderedSelectedLabels = selectedOrder.mapNotNull(selectedById::get)
    val allLabels = matchingLabels
    val canCreate = isValidLabelName(query) && labels.none {
        it.normalizedName == normalizedQuery || (it.isSystem && normalizeLabelName(favouriteName) == normalizedQuery)
    }
    fun applyLabel(labelId: Long, add: Boolean) {
        if (!isSaving && selectedIds.isNotEmpty()) {
            onApply(LabelEditRequest(changes = mapOf(labelId to add))) {}
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
        showBackButton = false,
        centerTitle = true,
        onPanelHeightChange = onPanelHeightChange,
        subtitle = stringResource(R.string.labels_selection_count, selectedIds.size),
        onDismiss = onDismiss,
        confirmDismiss = { !isSaving },
    ) {
        if (isSaving) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
            SelectionSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.labels_search),
                enabled = !isSaving && selectedIds.isNotEmpty(),
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
                                    applyLabel(label.id, false)
                                }, enabled = !isSaving && selectedIds.isNotEmpty(), modifier = Modifier.size(24.dp)) {
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
            SelectionGrid(
                columns = gridColumns,
                onColumnsChange = onColumnsChange,
                state = gridState,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                item(key = "all-header", span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.labels_all), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(allLabels, key = { it.id }) { label ->
                    val isShared = label.id in sharedIds
                    val onToggle = { applyLabel(label.id, !isShared) }
                    val summary = summariesById[label.id]
                    AlbumCoverCard(
                        title = if (label.isSystem) favouriteName else label.name,
                        subtitle = stringResource(R.string.labels_count, summary?.count ?: 0),
                        coverUri = summary?.cover?.uri,
                        coverModifiedMillis = summary?.cover?.dateModifiedMillis ?: 0L,
                        coverMimeType = summary?.cover?.sourceMimeType,
                        coverSizeBytes = summary?.cover?.sizeBytes,
                        coverRotationDegrees = summary?.cover?.sourceRotationDegrees ?: 0,
                        columns = gridColumns,
                        selected = isShared,
                        enabled = !isSaving && selectedIds.isNotEmpty(),
                        onClick = onToggle,
                        emptyIcon = if (label.isSystem) Icons.Default.Favorite else Icons.Outlined.Label,
                    )
                }
                if (canCreate) {
                    item(key = "create", span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = {
                            val submittedQuery = query
                            onApply(LabelEditRequest(newLabelNames = listOf(submittedQuery.trim()))) {
                                if (query == submittedQuery) query = ""
                            }
                        }, enabled = !isSaving && selectedIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
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

                        }
                    }
                }
            }
    }
}
