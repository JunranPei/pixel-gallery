package com.pixel.gallery.ui.quicksort

import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.ConfirmationReminder
import com.pixel.gallery.ui.components.ConfirmationFact
import android.os.Environment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.QuickSortAlbumUse
import com.pixel.gallery.model.frequentQuickSortAlbums
import com.pixel.gallery.ui.components.rememberVerticalRevealState
import com.pixel.gallery.ui.components.SettingsOptionControl
import com.pixel.gallery.ui.components.verticalRevealDrag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import com.pixel.gallery.model.matchesTransferDestinationQuery
import com.pixel.gallery.ui.home.AlbumCoverCard
import com.pixel.gallery.ui.theme.ExpressiveShapes
import java.util.Locale

/** Non-modal chooser: selections apply immediately and the photo dock remains available above it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSortTargetsSheet(
    availableTargets: List<TransferDestination>,
    albumUses: List<QuickSortAlbumUse> = emptyList(),
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
    var query by rememberSaveable { mutableStateOf("") }
    var activeTab by rememberSaveable { mutableStateOf("all") }
    val albumGridState = rememberLazyGridState()
    LaunchedEffect(activeTab, query) { albumGridState.scrollToItem(0) }
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
    val usageNow = remember(albumUses) { System.currentTimeMillis() }
    val frequent = remember(available, albumUses, usageNow) {
        frequentQuickSortAlbums(available, albumUses, usageNow)
    }
    val filtered = remember(available, frequent, draft, activeTab, query) {
        val source = when (activeTab) {
            "frequent" -> frequent
            "selected" -> draft
            else -> available
        }
        val search = query.trim()
        source.filter { search.isEmpty() || it.displayName.contains(search, ignoreCase = true) ||
            matchesTransferDestinationQuery(it, search) }
    }
    val selectionIndices = remember(draft) { draft.mapIndexed { index, target -> target.stableKey to index }.toMap() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val panel = rememberVerticalRevealState(expanded) { expanded = it }
    val flingThreshold = with(density) { 600.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val collapsedHeight = 68.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val expandedHeight = (maxHeight - 240.dp).coerceAtLeast(collapsedHeight)
        val range = with(density) { (expandedHeight - collapsedHeight).toPx() }
        val panelHeight = collapsedHeight + (expandedHeight - collapsedHeight) * panel.fraction
        val latestRange by rememberUpdatedState(range)
        val enabled by rememberUpdatedState(!isCreating)
        val nestedScroll = remember(panel) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (enabled && source == NestedScrollSource.UserInput && available.y < 0f && panel.fraction < 1f) {
                        if (!panel.dragging) panel.start()
                        val before = panel.fraction
                        panel.dragBy(available.y, latestRange)
                        return Offset(0f, -(panel.fraction - before) * latestRange)
                    }
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (enabled && source == NestedScrollSource.UserInput && available.y > 0f && panel.fraction > 0f) {
                        if (!panel.dragging) panel.start()
                        val before = panel.fraction
                        panel.dragBy(available.y, latestRange)
                        return Offset(0f, -(panel.fraction - before) * latestRange)
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!panel.dragging) return Velocity.Zero
                    panel.finish(available.y, flingThreshold)
                    return available
                }
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelHeight)
                .onGloballyPositioned { coordinates ->
                    onPanelHeightChange(with(density) { coordinates.size.height.toDp() })
                },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Column(Modifier.fillMaxWidth().verticalRevealDrag(panel, range, !isCreating, flingThreshold)) {
                    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                        Spacer(Modifier.width(32.dp).height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.qs_targets_title), style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        InputChip(selected = true,
                            onClick = { draft = emptyList(); onTargetsChange(emptyList()) },
                            enabled = draft.isNotEmpty() && !isCreating,
                            label = { Text(stringResource(R.string.qs_targets_selected_count, draft.size), maxLines = 1) },
                            trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.qs_targets_clear_selection), Modifier.size(18.dp)) },
                            shape = CircleShape,
                            colors = InputChipDefaults.inputChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer),
                            border = null)
                    }
                }
                // Keep the grid mounted through the entire drag, including a return from collapsed.
                if (panel.fraction > 0f) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                            singleLine = true, enabled = !isCreating, shape = CircleShape,
                            placeholder = { Text(stringResource(R.string.qs_search_albums), style = MaterialTheme.typography.bodySmall) },
                            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.qs_targets_clear_search))
                            } },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            ))
                        FilledTonalIconButton(onClick = { albumName = ""; albumNameError = null; showCreateAlbum = true },
                            enabled = !isCreating, modifier = Modifier.size(48.dp), shape = ExpressiveShapes.LargeIncreased) {
                            Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.qs_targets_new_album))
                        }
                    }
                    SettingsOptionControl(
                        options = listOf(
                            "frequent" to stringResource(R.string.qs_targets_frequent),
                            "all" to stringResource(R.string.qs_targets_all),
                            "selected" to stringResource(R.string.qs_targets_selected),
                        ),
                        selectedMode = activeTab,
                        onSelected = { activeTab = it },
                        enabled = !isCreating,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(stringResource(when {
                                query.isNotBlank() -> R.string.qs_targets_no_results
                                activeTab == "frequent" -> R.string.qs_targets_no_frequent
                                activeTab == "selected" -> R.string.qs_targets_no_selection
                                else -> R.string.qs_targets_empty
                            }), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(24.dp))
                        }
                    } else {
                        LazyVerticalGrid(columns = GridCells.Fixed(4), state = albumGridState,
                            modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(nestedScroll),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(filtered, key = { it.stableKey }) { target ->
                                val index = selectionIndices[target.stableKey] ?: -1
                                QuickSortTargetAlbumCard(target, index, draft.size, parentLabels[target.stableKey], !isCreating,
                                    onToggle = {
                                        val next = if (index >= 0) draft.filterNot { it.stableKey == target.stableKey } else draft + target
                                        draft = next; onTargetsChange(next)
                                    },
                                    onMoveEarlier = { val next = moveQuickSortTarget(draft, index, index - 1); draft = next; onTargetsChange(next) },
                                    onMoveLater = { val next = moveQuickSortTarget(draft, index, index + 1); draft = next; onTargetsChange(next) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateAlbum) {
        GalleryDialog(
            onDismissRequest = { if (!isCreating) showCreateAlbum = false },
            dismissEnabled = !isCreating,
            icon = Icons.Outlined.CreateNewFolder,
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
    val earlierLabel = stringResource(R.string.qs_move_earlier)
    val laterLabel = stringResource(R.string.qs_move_later)
    Box(Modifier.alpha(if (enabled) 1f else .45f).semantics(mergeDescendants = true) {
        selected = isSelected
        stateDescription = selectedDescription
        customActions = buildList {
            if (enabled && isSelected && selectionIndex > 0) add(CustomAccessibilityAction(earlierLabel) { onMoveEarlier(); true })
            if (enabled && isSelected && selectionIndex < selectedCount - 1) add(CustomAccessibilityAction(laterLabel) { onMoveLater(); true })
        }
    }) {
        AlbumCoverCard(title = target.displayName,
            subtitle = pluralStringResource(R.plurals.qs_targets_item_count, target.itemCount, target.itemCount),
            coverUri = target.coverUri?.takeIf { it.isNotBlank() }, coverModifiedMillis = target.lastModified,
            onClick = onToggle, columns = 4, enabled = enabled, coverShape = ExpressiveShapes.LargeIncreased, titleMaxLines = 1) {
            if (parentLabel != null) Text(parentLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clearAndSetSemantics {}.then(
            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, ExpressiveShapes.LargeIncreased) else Modifier)) {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = .9f), CircleShape)
                .then(if (isSelected) Modifier else Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)),
                contentAlignment = Alignment.Center) {
                if (isSelected) Text((selectionIndex + 1).toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
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
