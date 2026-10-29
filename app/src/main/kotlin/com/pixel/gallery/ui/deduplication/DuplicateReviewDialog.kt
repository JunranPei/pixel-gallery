package com.pixel.gallery.ui.deduplication

import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.ConfirmationReminder
import com.pixel.gallery.ui.components.ConfirmationFact
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.pixel.gallery.R
import com.pixel.gallery.ui.GalleryHeader
import com.pixel.gallery.ui.GalleryPage
import com.pixel.gallery.ui.GalleryPageContent
import com.pixel.gallery.ui.components.galleryContentEdge
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.model.duplicateMediaTimestamp
import com.pixel.gallery.model.DuplicateGroup
import com.pixel.gallery.model.allDuplicateCopiesSelected
import com.pixel.gallery.model.toggleAllDuplicateSelection
import com.pixel.gallery.data.local.entity.MediaEntry
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A read-only scan and review until the user confirms the selected copies. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateReviewDialog(
    state: DuplicateUiState,
    onDismiss: () -> Unit,
    onToggle: (Long) -> Unit,
    onConfirm: () -> Unit,
): GalleryPageContent? {
    if (state.phase == DuplicatePhase.IDLE) return null

    var showConfirmation by remember(state.phase) { mutableStateOf(false) }
    val context = LocalContext.current
    val groups = state.result?.groups.orEmpty()
    val cachedPreview = state.phase == DuplicatePhase.SCANNING && state.result != null
    val selectedEntries = groups.flatMap { it.entries }
        .filter { it.contentId in state.selectedIds }
    val selectedBytes = selectedEntries.sumOf { it.sizeBytes }
    val allCopiesSelected = allDuplicateCopiesSelected(groups, state.selectedIds)
    val canDismiss = state.phase != DuplicatePhase.AWAITING_PERMISSION &&
        state.phase != DuplicatePhase.VERIFYING
    val showFooter = state.phase == DuplicatePhase.REVIEW && groups.isNotEmpty()
    var footerHeightPx by remember { mutableIntStateOf(0) }
    val footerInset = if (showFooter) with(LocalDensity.current) { footerHeightPx.toDp() } else 0.dp


    return GalleryPage(
        topBar = {
            GalleryHeader(
                title = { Text(stringResource(R.string.duplicate_review_title), style = EmphasizedTypography.TitleLarge) },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = canDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.duplicate_close))
                    }
                },
            )
        },
        modifier = Modifier.background(MaterialTheme.colorScheme.surface).galleryContentEdge(),
    ) { _ ->
        BackHandler { if (canDismiss) onDismiss() }
        Box(
            Modifier.fillMaxSize()
                .then(if (showFooter) Modifier else Modifier.navigationBarsPadding()),
        ) {
            Box(Modifier.fillMaxSize()) {
                when {
                    state.phase == DuplicatePhase.REVIEW && groups.isEmpty() -> {
                        val incomplete = (state.result?.skippedCount ?: 0) > 0
                        DuplicateResultContent(
                            title = stringResource(if (incomplete) R.string.duplicate_check_incomplete else R.string.duplicate_no_duplicates_short),
                            noDuplicates = !incomplete,
                            detail = if (incomplete) stringResource(R.string.duplicate_status_incomplete) else null,
                        )
                    }
                    !cachedPreview && state.phase in setOf(DuplicatePhase.SCANNING,
                        DuplicatePhase.VALIDATING, DuplicatePhase.AWAITING_PERMISSION, DuplicatePhase.VERIFYING) -> {
                        DuplicateScanContent(state)
                    }

                    state.phase == DuplicatePhase.REVIEW || cachedPreview -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = footerInset),
                        ) {
                            item("summary") {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(stringResource(R.string.duplicate_groups_count, groups.size), style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                    }
                                    if (cachedPreview) {
                                        Text(stringResource(R.string.duplicate_index_updating), style = MaterialTheme.typography.bodyMedium)
                                        DuplicateProgressBar(null, Modifier.fillMaxWidth())
                                    }
                                    if (!state.canTrash) {
                                        Text(stringResource(R.string.duplicate_trash_unavailable), style = MaterialTheme.typography.bodySmall)
                                    }
                                    val skipped = state.result?.skippedCount ?: 0
                                    if (skipped > 0) {
                                        Text(
                                            stringResource(R.string.duplicate_scan_skipped, skipped),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    if (groups.isEmpty() && !cachedPreview) {
                                        Text(
                                            stringResource(R.string.duplicate_none_found),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                }
                            }
                            groups.forEachIndexed { index, group ->
                                item("group-${group.keeper.contentId}") {
                                    DuplicateGroupStrip(
                                        index = index,
                                        group = group,
                                        selectedIds = state.selectedIds,
                                        enabled = !cachedPreview,
                                        onToggle = { id -> if (!cachedPreview) onToggle(id) },
                                    )
                                }
                            }
                            item("end") { Spacer(Modifier.height(16.dp)) }
                        }
                    }

                    state.phase == DuplicatePhase.COMPLETE || state.phase == DuplicatePhase.ERROR -> {
                        val noDuplicates = state.phase == DuplicatePhase.COMPLETE && !state.cancelled &&
                            state.skippedCount == 0 && groups.isEmpty()
                        DuplicateResultContent(
                            title = stringResource(when {
                                state.phase == DuplicatePhase.ERROR -> R.string.duplicate_failed
                                state.cancelled -> R.string.duplicate_cleanup_cancelled
                                noDuplicates -> R.string.duplicate_no_duplicates_short
                                else -> R.string.duplicate_check_incomplete
                            }),
                            noDuplicates = noDuplicates,
                            detail = when {
                                state.phase == DuplicatePhase.ERROR -> state.error
                                !state.cancelled && !noDuplicates -> stringResource(R.string.duplicate_status_incomplete)
                                else -> null
                            },
                        )
                    }
                }
            }
            if (showFooter) {
                // Only the island paints a surface. Content continues underneath
                // the surrounding transparent space, including the navigation inset.
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .onSizeChanged { footerHeightPx = it.height }
                        .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                stringResource(R.string.duplicate_selected_count, selectedEntries.size),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                Formatter.formatShortFileSize(context, selectedBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(
                            onClick = { toggleCopySelection(groups, state.selectedIds, onToggle) },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Text(stringResource(if (allCopiesSelected) R.string.duplicate_clear_all_short else R.string.duplicate_select_all_short),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { showConfirmation = true },
                            enabled = selectedEntries.isNotEmpty() && state.canTrash,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Text(stringResource(R.string.duplicate_move_to_bin_short))
                        }
                    }
                }
            }
        }

        if (showConfirmation && state.phase == DuplicatePhase.REVIEW) {
            GalleryDialog(
                onDismissRequest = { showConfirmation = false },
                title = { Text(stringResource(R.string.dialog_duplicate_title)) },
                icon = Icons.Outlined.DeleteOutline,
                text = {
                    ConfirmationFact(stringResource(R.string.dialog_selected_copies), pluralStringResource(R.plurals.dialog_item_count, selectedEntries.size, selectedEntries.size))
                    ConfirmationReminder(stringResource(R.string.dialog_duplicate_action), Icons.Outlined.DeleteOutline)
                    ConfirmationReminder(stringResource(R.string.dialog_duplicate_safety))
                    ConfirmationReminder(stringResource(R.string.dialog_restore), Icons.Outlined.Restore)
                },
                confirmButton = {
                    Button(
                        enabled = selectedEntries.isNotEmpty() && state.canTrash,
                        onClick = {
                            showConfirmation = false
                            onConfirm()
                        },
                    ) { Text(stringResource(R.string.dialog_move_to_bin)) }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmation = false }) {
                        Text(stringResource(R.string.duplicate_cancel))
                    }
                },
            )
        }
    }
}

private fun toggleCopySelection(
    groups: List<DuplicateGroup>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    val next = toggleAllDuplicateSelection(groups, selectedIds)
    (selectedIds - next).forEach(onToggle)
    (next - selectedIds).forEach(onToggle)
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DuplicateGroupStrip(
    index: Int,
    group: com.pixel.gallery.model.DuplicateGroup,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    enabled: Boolean = true,
) {
    val entries = group.entries
    val allCopiesSelected = allDuplicateCopiesSelected(listOf(group), selectedIds)
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.duplicate_group_title, index + 1, entries.size),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(enabled = enabled, onClick = { toggleCopySelection(listOf(group), selectedIds, onToggle) }) {
                Text(stringResource(if (allCopiesSelected) R.string.duplicate_clear_all_short else R.string.duplicate_select_all_short))
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.contentId }) { position, entry ->
                val selected = entry.contentId in selectedIds
                DuplicateMediaTile(
                    entry = entry,
                    recommended = position == 0,
                    selected = selected,
                    disabled = !enabled,
                    onToggle = { onToggle(entry.contentId) },
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DuplicateMediaTile(
    entry: MediaEntry,
    recommended: Boolean,
    selected: Boolean,
    disabled: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val folder = File(entry.path).parentFile?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.duplicate_unknown_folder)
    val date = duplicateMediaTimestamp(entry)?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
    }
    val status = stringResource(if (selected) R.string.duplicate_move_status else R.string.duplicate_keep_status)
    Surface(
        modifier = Modifier.width(148.dp).semantics { stateDescription = status }.toggleable(
            value = selected, role = Role.Checkbox, enabled = !disabled,
            onValueChange = { onToggle() },
        ),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                GlideImage(
                    model = entry.uri, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                if (selected) {
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp))
                    }
                }
                if (recommended && !selected) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ) {
                        Text(stringResource(R.string.duplicate_keep_status),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(File(entry.path).name.ifBlank { entry.uri }, style = MaterialTheme.typography.bodySmall,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$folder · ${Formatter.formatShortFileSize(context, entry.sizeBytes)}", style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (date != null) {
                Text(date, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
