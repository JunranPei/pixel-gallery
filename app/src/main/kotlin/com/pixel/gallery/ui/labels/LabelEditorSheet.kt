package com.pixel.gallery.ui.labels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.model.LabelEditRequest
import com.pixel.gallery.model.LabelSelection
import com.pixel.gallery.model.isValidLabelName
import com.pixel.gallery.model.labelSelection
import com.pixel.gallery.model.normalizeLabelName
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelEditorSheet(
    labels: List<LabelEntry>,
    memberships: List<MediaLabelEntry>,
    selectedIds: Set<Long>,
    onDismiss: () -> Unit,
    onApply: (LabelEditRequest) -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null
) {
    var query by rememberSaveable(selectedIds) { mutableStateOf("") }
    var changes by rememberSaveable(selectedIds, stateSaver = LabelEditsSaver) {
        mutableStateOf<Map<Long, Boolean>>(emptyMap())
    }
    var newNames by rememberSaveable(selectedIds) { mutableStateOf<List<String>>(emptyList()) }
    val favouriteName = stringResource(R.string.labels_favourites)
    val normalizedQuery = normalizeLabelName(query)
    val matchingLabels = labels.filter {
        normalizeLabelName(if (it.isSystem) favouriteName else it.name).contains(normalizedQuery)
    }
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

    ModalBottomSheet(
        onDismissRequest = { finishEditing.value() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                if (target == SheetValue.Hidden) {
                    // Keep the sheet visible until persistence succeeds. The
                    // owner closes it from onApply's success callback.
                    finishEditing.value()
                    false
                } else true
            }
        ),
        shape = ExpressiveShapes.ExtraLargeIncreased
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finishEditing.value() }, enabled = !isSaving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back))
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(stringResource(R.string.label_edit), style = EmphasizedTypography.TitleLarge)
                    Text(stringResource(R.string.labels_selection_count, selectedIds.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isSaving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
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
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                items(matchingLabels, key = { it.id }) { label ->
                    val initial = labelSelection(label.id, selectedIds, memberships)
                    val state = changes[label.id]?.let { if (it) ToggleableState.On else ToggleableState.Off }
                        ?: when (initial) {
                            LabelSelection.NONE -> ToggleableState.Off
                            LabelSelection.SOME -> ToggleableState.Indeterminate
                            LabelSelection.ALL -> ToggleableState.On
                        }
                    LabelCheckboxRow(
                        title = if (label.isSystem) favouriteName else label.name,
                        subtitle = if (state == ToggleableState.Indeterminate) stringResource(R.string.labels_mixed) else null,
                        state = state,
                        isFavourite = label.isSystem,
                        enabled = !isSaving,
                        onClick = { changes = changes + (label.id to (state != ToggleableState.On)) }
                    )
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
                    item(key = "create") {
                        TextButton(onClick = { newNames = newNames + query.trim(); query = "" }, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.labels_new_from_query, query.trim()), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            errorMessage?.let {
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
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
            .triStateToggleable(state = state, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (isFavourite) Icons.Default.Favorite else Icons.Outlined.Label, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TriStateCheckbox(state = state, onClick = null, enabled = enabled)
    }
}
