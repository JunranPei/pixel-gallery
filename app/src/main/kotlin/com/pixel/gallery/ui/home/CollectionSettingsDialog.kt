package com.pixel.gallery.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.CoverTarget
import com.pixel.gallery.ui.*
import com.pixel.gallery.ui.settings.*
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.gallery.rememberAlbumSearch
import com.pixel.gallery.ui.viewmodel.PhotosViewModel

@Composable
fun CollectionSettingsDialog(
    target: CoverTarget, name: String, onNameChange: (String) -> Unit, canRename: Boolean,
    preview: MediaEntry?, automatic: Boolean, saving: Boolean, error: Boolean,
    labelGroup: Boolean = false, onBack: () -> Unit, onSave: (Int?) -> Unit, onChoose: () -> Unit, onAutomatic: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("group_illustrations", 0) }
    val groupId = target.key.takeIf { it.startsWith("group:") }?.removePrefix("group:")
    var draftArt by rememberSaveable(target.key) { mutableIntStateOf(groupId?.let { prefs.getInt(it, 0) }?.coerceIn(groupArt.indices) ?: 0) }
    var pickingArt by rememberSaveable(target.key) { mutableStateOf(false) }
    if (pickingArt) {
        GroupIllustrationPicker(draftArt, { draftArt = it; pickingArt = false }, { pickingArt = false }, labelGroup)
        return
    }
    com.pixel.gallery.ui.components.GalleryDialog(
        onDismissRequest = { if (!saving) onBack() }, icon = Icons.Outlined.Edit,
        title = { Text(stringResource(R.string.collection_settings)) },
        dismissEnabled = !saving,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val coverState = stringResource(
                    if (automatic) R.string.cover_automatic else R.string.cover_manual,
                )
                if (groupId != null) {
                    Surface(onClick = { pickingArt = true }, enabled = !saving,
                        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.size(112.dp)) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(groupIllustrationResource(draftArt, labelGroup)),
                            contentDescription = stringResource(R.string.group_choose_illustration), modifier = Modifier.padding(8.dp))
                    }
                } else Box(Modifier.size(128.dp)) {
                    Box(
                        Modifier.align(Alignment.Center).size(112.dp)
                            .semantics { stateDescription = coverState },
                    ) {
                        AlbumCoverCard(
                            "", "", preview?.uri, preview?.dateModifiedMillis ?: 0L, onChoose,
                            enabled = !saving, showCaption = false, columns = 4,
                            onClickLabel = stringResource(R.string.cover_choose_image),
                            coverMimeType = preview?.sourceMimeType,
                            coverSizeBytes = preview?.sizeBytes,
                            coverRotationDegrees = preview?.sourceRotationDegrees ?: 0,
                        )
                    }
                    if (!automatic) IconButton(
                        onClick = onAutomatic,
                        enabled = !saving,
                        modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = androidx.compose.foundation.BorderStroke(
                                2.dp, MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.cover_use_auto),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                if (canRename) OutlinedTextField(
                    value = name, onValueChange = onNameChange,
                    singleLine = true, enabled = !saving,
                    label = { Text(stringResource(R.string.album_name)) },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) else Text(target.title, style = MaterialTheme.typography.titleMedium)
                if (error) Text(stringResource(R.string.cover_save_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = { onSave(draftArt.takeIf { groupId != null }) }, enabled = !saving && (!canRename || name.trim().isNotEmpty())) {
            Text(stringResource(R.string.labels_save))
        } },
        dismissButton = { TextButton(onClick = onBack, enabled = !saving) { Text(stringResource(R.string.labels_cancel)) } },
    )
}

/** Label/group collection selection uses the same media grid and date sections as album browsing. */
@Composable
fun CollectionCoverPhotoPage(
    title: String, media: List<MediaEntry>, columns: Int, onColumnsChange: (Int) -> Unit,
    photosViewModel: PhotosViewModel, onBack: () -> Unit, onChoose: (Long) -> Unit,
): GalleryPageContent {
    var query by rememberSaveable(title) { mutableStateOf("") }
    var searching by rememberSaveable(title) { mutableStateOf(false) }
    var focus by remember { mutableStateOf(false) }
    val state = rememberLazyGridState()
    val filtered = remember(media, query) { media.filter { it.path.substringAfterLast('/').contains(query.trim(), true) } }
    val items = remember(filtered, columns) { photosViewModel.groupMedia(filtered, columns) }
    return GalleryPage(topBar = { GalleryHeader(
        title = { Column {
            Text(title, style = EmphasizedTypography.TitleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(stringResource(R.string.cover_choose_image), style = MaterialTheme.typography.labelMedium)
        } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back)) } },
        search = rememberAlbumSearch(searching, { searching = it; focus = it }, query, { query = it },
            stringResource(R.string.cover_search), stringResource(R.string.album_clear_search), focus, { focus = false }),
    ) }) { padding -> Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
        if (filtered.isEmpty()) com.pixel.gallery.ui.deduplication.DuplicateResultContent(
            title = stringResource(if (query.isBlank()) R.string.cover_empty_title else R.string.cover_no_results),
            noDuplicates = true, showCheck = false,
            detail = stringResource(if (query.isBlank()) R.string.cover_empty else R.string.cover_search_empty_hint),
        ) else PhotosScreen(items = items, onNavigateToViewer = onChoose, columns = columns, onColumnsChange = onColumnsChange,
            state = state, emptyMessage = if (filtered.isEmpty()) stringResource(R.string.cover_empty) else null,
            onToggleSelection = onChoose, selectionMode = false)
    } }
}
