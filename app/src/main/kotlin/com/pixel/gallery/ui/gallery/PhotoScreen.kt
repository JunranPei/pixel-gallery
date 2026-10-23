package com.pixel.gallery.ui.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.R
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.ui.home.PhotosScreen
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.filled.Sort
import com.pixel.gallery.ui.viewmodel.PhotoSortOrder
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import com.pixel.gallery.ui.components.SortDialog
import com.pixel.gallery.ui.components.SortCriterion
import com.pixel.gallery.data.local.entity.MediaEntry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.pixel.gallery.ui.viewmodel.MediaBrowseScope
import com.pixel.gallery.ui.viewmodel.MediaBrowseResult
import com.pixel.gallery.ui.quicksort.QuickSortUiConfig
import com.pixel.gallery.ui.components.GalleryOverflowButton
import com.pixel.gallery.ui.components.MediaTypeMenuSection
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.ui.components.GalleryMenuRow
import com.pixel.gallery.ui.components.galleryContentEdge

internal fun MediaEntry.matchesAlbumSearch(query: String): Boolean {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return true
    return path.contains(normalizedQuery, ignoreCase = true) ||
        uri.contains(normalizedQuery, ignoreCase = true)
}

internal fun filterAlbumGridItems(
    items: List<GridItem>,
    query: String,
    mediaType: MediaTypeFilter = MediaTypeFilter.ALL,
): List<GridItem> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty() && mediaType == MediaTypeFilter.ALL) return items

    val result = ArrayList<GridItem>(items.size)
    var pendingHeader: GridItem.Header? = null
    items.forEach { item ->
        when (item) {
            is GridItem.Header -> pendingHeader = item
            is GridItem.Photo -> if (mediaType.matches(item.entry) && item.entry.matchesAlbumSearch(normalizedQuery)) {
                pendingHeader?.let(result::add)
                pendingHeader = null
                result.add(item)
            }
        }
    }
    return result
}

private data class AlbumBrowseResult(
    val mediaType: MediaTypeFilter = MediaTypeFilter.ALL,
    val query: String = "",
    val media: MediaBrowseResult = MediaBrowseResult(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoScreen(
    albumName: String,
    onBack: () -> Unit,
    onNavigateToViewer: (Long, MediaTypeFilter, String) -> Unit,
    selectedIds: Set<Long> = emptySet(),
    onSelectionChange: (Set<Long>) -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    onAlbumPhotosChanged: (List<MediaEntry>) -> Unit = {},
    onDeduplicate: (List<MediaEntry>) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState(),
    quickSort: QuickSortUiConfig? = null,
    onToggleQuickSort: () -> Unit = {},
    initialMediaType: MediaTypeFilter = MediaTypeFilter.ALL,
    isForeground: Boolean = true,
    selectionMode: Boolean = selectedIds.isNotEmpty(),
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val browsingIndex by viewModel.browsingIndex.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val photoSortOrder by viewModel.photoSortOrder.collectAsState()
    var showSortDialog by remember { mutableStateOf(false) }
    var searchMode by rememberSaveable(albumName) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(albumName) { mutableStateOf("") }
    val normalizedSearchQuery = searchQuery.trim()
    // This visit inherits the parent once; subsequent choices stay local.
    var mediaType by rememberSaveable(albumName) { mutableStateOf(initialMediaType) }
    val scope = rememberCoroutineScope()
    
    val albumScope by produceState<MediaBrowseScope?>(null, browsingIndex, albumName) {
        value = withContext(Dispatchers.Default) {
            val taskContext = coroutineContext
            browsingIndex.album(albumName) { taskContext.ensureActive() }
        }
    }
    val albumResult by produceState(AlbumBrowseResult(), albumScope, mediaType, normalizedSearchQuery) {
        val type = mediaType
        val query = normalizedSearchQuery
        value = withContext(Dispatchers.Default) {
            val taskContext = coroutineContext
            val media = albumScope?.get(type)?.search(query, { taskContext.ensureActive() }) { entry, text ->
                entry.matchesAlbumSearch(text)
            } ?: MediaBrowseResult()
            AlbumBrowseResult(type, query, media)
        }
    }
    val albumPhotos = albumScope?.get(MediaTypeFilter.ALL)?.media?.photos.orEmpty()
    val visibleMedia = if (normalizedSearchQuery.isEmpty()) {
        albumScope?.get(mediaType)?.media ?: MediaBrowseResult()
    } else if (albumResult.mediaType == mediaType && albumResult.query == normalizedSearchQuery) {
        albumResult.media
    } else MediaBrowseResult()
    val displayedAlbumItems = visibleMedia.gridItems
    val displayedAlbumPhotos = visibleMedia.photos
    LaunchedEffect(albumName, displayedAlbumPhotos) {
        onAlbumPhotosChanged(displayedAlbumPhotos)
    }
    val photoCount = displayedAlbumPhotos.size

    var showMenu by remember { mutableStateOf(false) }
    val albumPath = remember(albumPhotos) {
        albumPhotos.firstOrNull()?.path?.substringBeforeLast('/', "").orEmpty()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
        topBar = {
            if (!selectionMode) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    title = {
                        AlbumSearchTitle(
                            isSearchMode = searchMode,
                            onSearchModeChange = { searchMode = it },
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            searchPlaceholder = stringResource(R.string.album_search_files),
                            clearSearchDescription = stringResource(R.string.album_clear_search),
                            backEnabled = isForeground,
                            title = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    java.io.File(albumName).name,
                                    style = EmphasizedTypography.TitleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    pluralStringResource(
                                        when (mediaType) {
                                            MediaTypeFilter.ALL -> R.plurals.media_type_item_count
                                            MediaTypeFilter.IMAGES -> R.plurals.media_type_image_count
                                            MediaTypeFilter.VIDEOS -> R.plurals.media_type_video_count
                                        },
                                        photoCount, photoCount,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        GalleryOverflowButton(
                            expanded = showMenu,
                            onExpandedChange = { showMenu = it },
                        ) {
                            MediaTypeMenuSection(mediaType) { type ->
                                showMenu = false
                                if (type != mediaType) {
                                    mediaType = type
                                    onSelectionChange(emptySet())
                                    scope.launch { gridState.scrollToItem(0) }
                                }
                            }
                            if (quickSort == null) GalleryMenuRow(
                                label = stringResource(R.string.qs_start),
                                icon = Icons.Outlined.DriveFileMove,
                                accented = true,
                                onClick = { showMenu = false; onToggleQuickSort() },
                            )
                            GalleryMenuRow(
                                label = "Sort Photos",
                                icon = Icons.Default.Sort,
                                accented = true,
                                onClick = {
                                    showMenu = false
                                    showSortDialog = true
                                },
                            )
                            GalleryMenuRow(
                                label = stringResource(R.string.duplicate_find_album),
                                icon = Icons.Outlined.FileCopy,
                                accented = true,
                                enabled = albumPhotos.size > 1,
                                onClick = { showMenu = false; onDeduplicate(albumPhotos) },
                            )
                            Spacer(Modifier.height(8.dp))
                            GalleryMenuRow(
                                label = "Hide Album",
                                icon = Icons.Outlined.VisibilityOff,
                                onClick = {
                                    showMenu = false
                                    if (albumPath.isNotEmpty()) {
                                        viewModel.addHiddenFolder(albumPath)
                                        onBack()
                                    }
                                },
                            )
                            GalleryMenuRow(
                                label = "Exclude Album",
                                icon = Icons.Outlined.FolderOff,
                                onClick = {
                                    showMenu = false
                                    if (albumPath.isNotEmpty()) {
                                        viewModel.addExcludedFolder(albumPath)
                                        onBack()
                                    }
                                },
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).galleryContentEdge()) {


            PhotosScreen(
                quickSort = quickSort,
                bottomPadding = if (quickSort != null) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp,
                items = displayedAlbumItems,
                positionLabels = visibleMedia.positionLabels,
                onNavigateToViewer = { id ->
                    // Publish this exact displayed snapshot before the parent opens its viewer.
                    onAlbumPhotosChanged(displayedAlbumPhotos)
                    onNavigateToViewer(id, mediaType, if (normalizedSearchQuery.isEmpty()) "" else albumResult.query)
                },
                selectedIds = selectedIds,
                selectionMode = selectionMode,
                onSelectionChange = onSelectionChange,
                onToggleSelection = onToggleSelection,
                columns = gridColumns,
                onColumnsChange = { viewModel.setGridColumns(it) },
                state = gridState,
                emptyMessage = if (normalizedSearchQuery.isNotEmpty()) {
                    stringResource(R.string.album_no_matching_files)
                } else when (mediaType) {
                    MediaTypeFilter.ALL -> null
                    MediaTypeFilter.IMAGES -> stringResource(R.string.media_type_no_images)
                    MediaTypeFilter.VIDEOS -> stringResource(R.string.media_type_no_videos)
                },
            )
        }
    }

    val photoCriteria = remember {
            listOf(
                SortCriterion("DATE", "Date", "Oldest first", "Newest first"),
                SortCriterion("NAME", "Name", "A to Z", "Z to A"),
                SortCriterion("SIZE", "Size", "Smallest first", "Largest first")
            )
        }

        AnimatedVisibility(
            visible = showSortDialog,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(150))
        ) {
            val currentCriterion = when (photoSortOrder) {
                PhotoSortOrder.DATE_DESC, PhotoSortOrder.DATE_ASC -> "DATE"
                PhotoSortOrder.NAME_DESC, PhotoSortOrder.NAME_ASC -> "NAME"
                PhotoSortOrder.SIZE_DESC, PhotoSortOrder.SIZE_ASC -> "SIZE"
            }
            val currentDirection = when (photoSortOrder) {
                PhotoSortOrder.DATE_DESC, PhotoSortOrder.NAME_DESC, PhotoSortOrder.SIZE_DESC -> "DESC"
                PhotoSortOrder.DATE_ASC, PhotoSortOrder.NAME_ASC, PhotoSortOrder.SIZE_ASC -> "ASC"
            }
            SortDialog(
                visible = showSortDialog,
                onDismissRequest = { showSortDialog = false },
                title = "Sort Photos By",
                criteria = photoCriteria,
                initialCriterion = currentCriterion,
                initialDirection = currentDirection,
                onConfirm = { criterion, direction ->
                    val newOrder = PhotoSortOrder.valueOf("${criterion}_${direction}")
                    viewModel.setPhotoSortOrder(newOrder)
                    showSortDialog = false
                }
            )
        }
    }
}
