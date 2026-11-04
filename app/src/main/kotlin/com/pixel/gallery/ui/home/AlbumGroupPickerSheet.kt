package com.pixel.gallery.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.model.Album
import com.pixel.gallery.model.AlbumGroup
import com.pixel.gallery.model.normalizeLabelName
import com.pixel.gallery.ui.components.SelectionBottomSheet
import com.pixel.gallery.ui.components.SelectionGrid
import com.pixel.gallery.ui.components.SelectionSearchField

/** The same bounded, searchable grid used by label selection. One target per album. */
@Composable
fun AlbumGroupPickerSheet(
    expandRequest: Int = 0,
    groups: List<AlbumGroup>, albums: List<Album>, selectedPaths: Set<String>,
    groupCovers: Map<String, String> = emptyMap(),
    labelMode: Boolean = false,
    nameValidationGroups: List<AlbumGroup> = groups,
    columns: Int, onColumnsChange: (Int) -> Unit,
    isSaving: Boolean, errorMessage: String?, onPanelHeightChange: (Dp) -> Unit,
    onDismiss: () -> Unit, onAssign: (String?) -> Unit, onCreate: (String, Int, () -> Unit) -> Unit,
) {
    var query by rememberSaveable(selectedPaths) { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    val normalized = normalizeLabelName(query)
    val matching = remember(groups, normalized) { groups.filter { normalizeLabelName(it.name).contains(normalized) } }
    val current = remember(groups, selectedPaths) { groups.filter { group -> group.paths.any { it in selectedPaths } } }
    val common = current.singleOrNull()?.takeIf { it.paths.containsAll(selectedPaths) }
    val canCreate = query.trim().isNotEmpty() && query.trim().length <= 60 && nameValidationGroups.none { normalizeLabelName(it.name) == normalized }
    val byPath = remember(albums) { albums.associateBy { it.path } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(normalized) { gridState.scrollToItem(0) }

    if (creating) {
        GroupCreateDialog(nameValidationGroups, query, isSaving, errorMessage, { creating = false }, labelMode) { name, art ->
            onCreate(name, art) { creating = false; query = "" }
        }
        return
    }
    SelectionBottomSheet(
        expandRequest = expandRequest,
        title = stringResource(R.string.album_group_add), centerTitle = true, showBackButton = false,
        subtitle = stringResource(if (labelMode) R.string.label_group_selected_count else R.string.album_group_selected_count, selectedPaths.size),
        onDismiss = onDismiss, confirmDismiss = { !isSaving }, onPanelHeightChange = onPanelHeightChange,
    ) {
        if (isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        SelectionSearchField(
            query = query, onQueryChange = { query = it }, placeholder = stringResource(R.string.album_group_search),
            enabled = !isSaving,
        )
        Spacer(Modifier.height(8.dp))
        SelectionGrid(columns = columns, onColumnsChange = onColumnsChange, state = gridState,
            modifier = Modifier.weight(1f, fill = false)) {
            item(key = "all-header", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.album_group_all), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (normalized.isEmpty()) item(key = "ungrouped") {
                AlbumCoverCard(
                    title = stringResource(if (labelMode) R.string.qs_labels_title else R.string.album_group_ungrouped),
                    subtitle = stringResource(R.string.album_group_remove),
                    coverUri = null, coverModifiedMillis = 0L, columns = columns,
                    emptyIcon = Icons.Outlined.FolderOff, enabled = !isSaving && selectedPaths.isNotEmpty(),
                    onClick = { if (selectedPaths.isNotEmpty()) onAssign(null) },
                    onClickLabel = stringResource(R.string.album_group_remove),
                    titleMaxLines = 2,
                )
            }
            items(matching, key = { it.id }, contentType = { "group" }) { group ->
                val cover = group.paths.firstNotNullOfOrNull { byPath[it]?.takeIf { album -> album.coverUri.isNotBlank() } }
                AlbumCoverCard(
                    title = group.name, subtitle = stringResource(if (labelMode) R.string.album_group_label_count else R.string.album_group_count, group.paths.size),
                    coverUri = groupCovers[group.id] ?: cover?.coverUri, coverModifiedMillis = cover?.lastModified ?: 0L,
                    columns = columns,
                    coverContent = { GroupIllustration(group.id, Modifier.fillMaxSize().padding(8.dp), labelMode) },
                    coverBorder = if (group in current) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    coverBadge = if (group in current) ({
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(if (columns >= 5) 3.dp else 6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                stringResource(if (common?.id == group.id) R.string.album_group_joined else R.string.album_group_partial),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = (if (columns >= 5) 10 else 11).sp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = if (columns >= 5) 4.dp else 8.dp, vertical = 2.dp),
                            )
                        }
                    }) else null,
                    onClickLabel = stringResource(if (common?.id == group.id) R.string.album_group_remove else R.string.album_group_add),
                    titleMaxLines = 2,
                    enabled = !isSaving && selectedPaths.isNotEmpty(), emptyIcon = Icons.Outlined.Folder, onClick = { onAssign(if (common?.id == group.id) null else group.id) },
                )
            }
            if (matching.isEmpty() && normalized.isNotEmpty() && !canCreate) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.album_group_no_results), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
            if (canCreate) item(key = "create", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = {
                    creating = true
                }, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.album_group_new_from_query, query.trim()), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            errorMessage?.let { message ->
                item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    Text(message, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}
