package com.pixel.gallery.ui.labels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.ui.home.PhotosScreen
import com.pixel.gallery.ui.quicksort.QuickSortUiConfig
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.gallery.AlbumSearchTitle
import com.pixel.gallery.ui.components.galleryContentEdge
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import com.pixel.gallery.ui.components.GalleryOverflowButton
import com.pixel.gallery.ui.components.GalleryMenuRow
import com.pixel.gallery.ui.components.MediaTypeMenuSection
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.ui.gallery.filterAlbumGridItems

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelPhotosScreen(
    label: LabelEntry?, items: List<GridItem>, selectedIds: Set<Long>, columns: Int,
    gridState: LazyGridState, onBack: () -> Unit, onNavigateToViewer: (Long) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit, onToggleSelection: (Long) -> Unit,
    onColumnsChange: (Int) -> Unit, quickSort: QuickSortUiConfig?,
    onToggleQuickSort: () -> Unit,
    onSortPhotos: () -> Unit = {},
    onRemoveFromLabel: (Set<Long>) -> Unit = {},
    onEditLabel: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    var searchMode by rememberSaveable(label?.id) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(label?.id) { mutableStateOf("") }
    var mediaType by rememberSaveable(label?.id) { mutableStateOf(MediaTypeFilter.ALL) }
    val displayedItems = remember(items, searchQuery, mediaType) {
        filterAlbumGridItems(items, searchQuery, mediaType)
    }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        if (selectedIds.isEmpty()) TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            title = {
                AlbumSearchTitle(
                    isSearchMode = searchMode,
                    onSearchModeChange = { searchMode = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    searchPlaceholder = stringResource(R.string.album_search_files),
                    clearSearchDescription = stringResource(R.string.album_clear_search),
                    title = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            label?.let { labelDisplayName(it) } ?: stringResource(R.string.labels_title),
                            style = EmphasizedTypography.TitleLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        val count = displayedItems.count { it is GridItem.Photo }
                        Text(
                            androidx.compose.ui.res.pluralStringResource(
                                when (mediaType) {
                                    MediaTypeFilter.ALL -> R.plurals.media_type_item_count
                                    MediaTypeFilter.IMAGES -> R.plurals.media_type_image_count
                                    MediaTypeFilter.VIDEOS -> R.plurals.media_type_video_count
                                }, count, count,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    },
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back)) } },
            actions = {
                    GalleryOverflowButton(expanded = showMenu, onExpandedChange = { showMenu = it }) {
                        MediaTypeMenuSection(mediaType) { type -> mediaType = type; showMenu = false }
                        GalleryMenuRow("Sort Photos", Icons.Default.Sort, accented = true, onClick = { showMenu = false; onSortPhotos() })
                        GalleryMenuRow("Edit label", Icons.Outlined.Label, accented = true, onClick = { showMenu = false; onEditLabel() })
                        if (selectedIds.isNotEmpty()) GalleryMenuRow(
                            "Remove selected from label", Icons.Outlined.Label, accented = true,
                            onClick = { showMenu = false; onRemoveFromLabel(selectedIds) },
                        )
                        if (quickSort == null) GalleryMenuRow(
                            label = stringResource(R.string.qs_start),
                            icon = Icons.Outlined.DriveFileMove,
                            accented = true,
                            onClick = { showMenu = false; onToggleQuickSort() },
                        )
                    }
            })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).galleryContentEdge()) {
            PhotosScreen(items = displayedItems, onNavigateToViewer = onNavigateToViewer, selectedIds = selectedIds,
                onSelectionChange = onSelectionChange, onToggleSelection = onToggleSelection,
                columns = columns, onColumnsChange = onColumnsChange, state = gridState,
                bottomPadding = if (quickSort != null) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp,
                emptyMessage = if (searchQuery.isNotBlank()) "No matching files" else stringResource(R.string.labels_empty_contents), quickSort = quickSort)
        }
    }
}
