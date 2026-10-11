package com.pixel.gallery.ui.quicksort

import android.os.Environment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.matchesTransferDestinationQuery
import com.pixel.gallery.ui.home.AlbumCoverCard
import com.pixel.gallery.ui.theme.ExpressiveShapes
import java.util.Locale

/** Explicit target selection. Draft order and edge are only persisted on Apply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSortTargetsSheet(
    availableTargets: List<TransferDestination>,
    selectedTargets: List<TransferDestination>,
    dockTop: Boolean,
    onDockTopChange: (Boolean) -> Unit,
    onApply: (List<TransferDestination>) -> Unit,
    onDismiss: () -> Unit,
    onPanelHeightChange: (Dp) -> Unit = {},
    onTargetsChange: (List<TransferDestination>) -> Unit = {},
    onCreateAlbum: (
        parent: TransferDestination,
        name: String,
        onResult: (Result<TransferDestination>) -> Unit,
    ) -> Unit = { _, _, onResult ->
        onResult(Result.failure(UnsupportedOperationException()))
    },
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var draft by remember { mutableStateOf(selectedTargets.distinctBy { it.stableKey }) }
    LaunchedEffect(selectedTargets) { draft = selectedTargets.distinctBy { it.stableKey } }
    var query by remember { mutableStateOf("") }
    var createdTargets by remember { mutableStateOf(emptyList<TransferDestination>()) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var albumName by remember { mutableStateOf("") }
    var albumNameError by remember { mutableStateOf<String?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    // A multi-selection has no single parent; use the same Pictures fallback as Copy/Move.
    val createParent = remember {
        TransferDestination(
            stableKey = "default-pictures",
            displayName = Environment.DIRECTORY_PICTURES,
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath,
        )
    }
    // Selecting or reordering a target must not move cards under the user's finger.
    // Keep newly created empty albums available even if they are later deselected.
    val available = remember(availableTargets, selectedTargets, createdTargets) {
        (createdTargets + availableTargets + selectedTargets).distinctBy { it.stableKey }
    }
    val parentLabels = remember(available) { quickSortTargetParentLabels(available) }
    val filtered = remember(available, query) {
        val search = query.trim()
        available.filter {
            search.isEmpty() || it.displayName.contains(search, ignoreCase = true) ||
                matchesTransferDestinationQuery(it, search)
        }
    }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var dragDistance by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelHeight by animateDpAsState(
            // Keep a stable strip above the panel for the quick-sort capsules.
            // The capsules stay mounted at the bottom of the grid and must
            // never be covered by the chooser surface.
            targetValue = if (expanded) (maxHeight - 320.dp).coerceAtLeast(68.dp) else 68.dp,
            label = "quick-sort-target-panel-height",
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(panelHeight)
                .onGloballyPositioned { coordinates ->
                    onPanelHeightChange(with(density) { coordinates.size.height.toDp() })
                },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Spacer(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(expanded, isCreating) {
                            if (!isCreating) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { _, amount -> dragDistance += amount },
                                    onDragEnd = {
                                        if (dragDistance > 48f) expanded = false
                                        if (dragDistance < -48f) expanded = true
                                        dragDistance = 0f
                                    },
                                    onDragCancel = { dragDistance = 0f },
                                )
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Text(
                    stringResource(R.string.qs_manage_albums),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.qs_targets_selected_count, draft.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(
                    onClick = { if (!isCreating) expanded = false },
                    enabled = !isCreating,
                ) {
                    Icon(Icons.Outlined.Check, stringResource(R.string.qs_done))
                }
                }
            if (expanded) {
            Text(
                stringResource(R.string.qs_choose_sorting_albums_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isCreating,
                placeholder = { Text(stringResource(R.string.qs_targets_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, enabled = !isCreating) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.qs_targets_clear_search))
                        }
                    }
                },
                shape = CircleShape,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        albumName = ""
                        albumNameError = null
                        showCreateAlbum = true
                    },
                    enabled = !isCreating,
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.qs_targets_new_album))
                }

            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (query.isBlank()) R.string.qs_targets_empty else R.string.qs_targets_no_results,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(filtered, key = { it.stableKey }) { target ->
                        val index = draft.indexOfFirst { it.stableKey == target.stableKey }
                        QuickSortTargetAlbumCard(
                            target = target,
                            selectionIndex = index,
                            selectedCount = draft.size,
                            parentLabel = parentLabels[target.stableKey],
                            enabled = !isCreating,
                            onToggle = {
                                val next = if (index >= 0) {
                                    draft.filterNot { it.stableKey == target.stableKey }
                                } else {
                                    draft + target
                                }
                                draft = next
                                onTargetsChange(next)
                            },
                            onMoveEarlier = {
                                val next = moveQuickSortTarget(draft, index, index - 1)
                                draft = next
                                onTargetsChange(next)
                            },
                            onMoveLater = {
                                val next = moveQuickSortTarget(draft, index, index + 1)
                                draft = next
                                onTargetsChange(next)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            }
            }
        }
    }

    if (showCreateAlbum) {
        AlertDialog(
            onDismissRequest = { if (!isCreating) showCreateAlbum = false },
            title = { Text(stringResource(R.string.qs_targets_new_album)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.qs_targets_create_in, createParent.displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = albumName,
                        onValueChange = {
                            albumName = it
                            albumNameError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.qs_targets_album_name)) },
                        singleLine = true,
                        enabled = !isCreating,
                        isError = albumNameError != null,
                        supportingText = albumNameError?.let { { Text(it) } },
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = albumName.isNotBlank() && !isCreating,
                    onClick = {
                        isCreating = true
                        albumNameError = null
                        try {
                            onCreateAlbum(createParent, albumName.trim()) { result ->
                                isCreating = false
                                result.onSuccess { target ->
                                    createdTargets = listOf(target) + createdTargets.filterNot {
                                        it.stableKey == target.stableKey
                                    }
                                    // Add the new album without changing existing target order.
                                    val next = (draft + target).distinctBy { it.stableKey }
                                    draft = next
                                    onTargetsChange(next)
                                    query = ""
                                    showCreateAlbum = false
                                    albumName = ""
                                }.onFailure { error ->
                                    albumNameError = error.message
                                        ?: context.getString(R.string.qs_targets_create_error)
                                }
                            }
                        } catch (error: Exception) {
                            isCreating = false
                            albumNameError = error.message
                                ?: context.getString(R.string.qs_targets_create_error)
                        }
                    },
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (isCreating) R.string.qs_targets_creating else R.string.qs_targets_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAlbum = false }, enabled = !isCreating) {
                    Text(stringResource(R.string.qs_cancel))
                }
            },
        )
    }
}

@Composable
private fun QuickSortTargetAlbumCard(
    target: TransferDestination,
    selectionIndex: Int,
    selectedCount: Int,
    parentLabel: String?,
    enabled: Boolean,
    onToggle: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
) {
    val isSelected = selectionIndex >= 0
    val selectedDescription = if (isSelected) {
        stringResource(R.string.qs_targets_selected_position, selectionIndex + 1)
    } else {
        stringResource(R.string.qs_targets_not_selected)
    }
    Column {
        Box(
            Modifier.alpha(if (enabled) 1f else 0.45f).semantics(mergeDescendants = true) {
                selected = isSelected
                stateDescription = selectedDescription
            },
        ) {
            AlbumCoverCard(
                title = target.displayName,
                subtitle = pluralStringResource(R.plurals.qs_targets_item_count, target.itemCount, target.itemCount),
                coverUri = target.coverUri?.takeIf { it.isNotBlank() },
                coverModifiedMillis = target.lastModified,
                onClick = onToggle,
                columns = 3,
                enabled = enabled,
            ) {
                if (parentLabel != null) {
                    Text(
                        parentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clearAndSetSemantics {}.then(
                    if (isSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, ExpressiveShapes.ExtraLargeIncreased)
                    } else Modifier,
                ),
            ) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            CircleShape,
                        )
                        .then(
                            if (isSelected) Modifier else Modifier.border(
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape,
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Text(
                            (selectionIndex + 1).toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (isSelected) {
                IconButton(enabled = enabled && selectionIndex > 0, onClick = onMoveEarlier) {
                    Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.qs_move_earlier))
                }
                IconButton(enabled = enabled && selectionIndex < selectedCount - 1, onClick = onMoveLater) {
                    Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.qs_move_later))
                }
            }
        }
    }
}

/** Only duplicate names need a path hint; prefer the shortest distinguishing parent suffix. */
private fun quickSortTargetParentLabels(targets: List<TransferDestination>): Map<String, String> = buildMap {
    targets.groupBy { it.displayName.lowercase(Locale.ROOT) }.values
        .filter { it.size > 1 }
        .forEach { duplicates ->
            val parents = duplicates.associate { target ->
                target.stableKey to target.path.replace('\\', '/').trimEnd('/').split('/')
                    .filter { it.isNotEmpty() }.dropLast(1)
            }
            duplicates.forEach { target ->
                val parent = parents.getValue(target.stableKey)
                val depth = (1..parent.size).firstOrNull { count ->
                    val suffix = parent.takeLast(count)
                    parents.none { (key, parts) ->
                        key != target.stableKey && parts.takeLast(count) == suffix
                    }
                } ?: parent.size
                put(target.stableKey, parent.takeLast(depth).joinToString("/").ifEmpty { "/" })
            }
        }
}
