package com.pixel.gallery.ui.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.model.Album
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.ui.GalleryHeader
import com.pixel.gallery.ui.GalleryPage
import com.pixel.gallery.ui.GalleryPageContent
import com.pixel.gallery.ui.components.GalleryMenuRow
import com.pixel.gallery.ui.components.GalleryOverflowButton
import com.pixel.gallery.ui.components.MediaTypeMenuSection
import com.pixel.gallery.ui.home.AlbumCollectionGrid
import com.pixel.gallery.ui.home.AlbumCollectionItem
import com.pixel.gallery.ui.theme.EmphasizedTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenAlbumsScreen(
    albums: List<Album>,
    onBack: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onSort: () -> Unit,
    onDeduplicate: () -> Unit,
    columns: Int = 2,
    onColumnsChange: (Int) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState(),
    bottomPadding: Dp = 0.dp,
    selectedPaths: Set<String> = emptySet(),
    onToggleAlbumSelection: (String) -> Unit = {},
    isForeground: Boolean = true,
    mediaType: MediaTypeFilter = MediaTypeFilter.ALL,
    onMediaTypeChange: (MediaTypeFilter) -> Unit = {},
    emptyMessage: String? = null,
): GalleryPageContent {
    var showMenu by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var searchFocusRequested by remember { mutableStateOf(false) }
    val entries = remember(albums, searchQuery) {
        val query = searchQuery.trim()
        albums.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .map { album ->
                AlbumCollectionItem(
                    key = album.path,
                    title = album.name,
                    subtitle = "${album.itemCount} items",
                    coverUri = album.coverUri.takeIf { it.isNotBlank() },
                    coverModifiedMillis = album.lastModified,
                    emptyIcon = Icons.Outlined.VisibilityOff,
                )
            }
    }

    return GalleryPage(
        topBar = {
            GalleryHeader(
                search = rememberAlbumSearch(
                    isSearchMode = searchMode,
                    onSearchModeChange = { enabled ->
                        if (enabled && !searchMode) searchFocusRequested = true
                        if (!enabled) searchFocusRequested = false
                        searchMode = enabled
                    },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    searchPlaceholder = "Search albums",
                    clearSearchDescription = stringResource(R.string.album_clear_search),
                    focusRequested = searchFocusRequested,
                    onFocusRequestConsumed = { searchFocusRequested = false },
                    backEnabled = isForeground,
                ),
                title = {
                            Text(
                                "Hidden Albums",
                                style = EmphasizedTypography.TitleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    GalleryOverflowButton(expanded = showMenu, onExpandedChange = { showMenu = it }) {
                        MediaTypeMenuSection(mediaType) { type ->
                            showMenu = false
                            onMediaTypeChange(type)
                        }
                        GalleryMenuRow("Sort Albums", Icons.AutoMirrored.Filled.Sort, accented = true, onClick = {
                            showMenu = false
                            onSort()
                        })
                        GalleryMenuRow(
                            stringResource(R.string.duplicate_find_each_album),
                            Icons.Outlined.FileCopy,
                            accented = true,
                            enabled = albums.any { it.itemCount > 1 },
                            onClick = {
                                showMenu = false
                                onDeduplicate()
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            // Keep every saved hidden root, including empty folders, in the shared grid.
            AlbumCollectionGrid.instance.Content(
                entries = entries,
                columns = columns,
                onColumnsChange = onColumnsChange,
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                bottomPadding = bottomPadding,
                selectedKeys = selectedPaths,
                onOpen = onNavigateToAlbum,
                onToggleSelection = onToggleAlbumSelection,
                emptyMessage = if (searchQuery.isNotBlank() && entries.isEmpty()) "No matching albums" else emptyMessage,
            )
            if (albums.isEmpty() && emptyMessage == null && searchQuery.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(100.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "No Hidden Albums",
                        style = EmphasizedTypography.HeadlineMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
