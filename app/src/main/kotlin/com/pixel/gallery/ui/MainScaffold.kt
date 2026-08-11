package com.pixel.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.ui.home.PhotosScreen
import com.pixel.gallery.ui.home.AlbumsScreen
import com.pixel.gallery.ui.settings.SettingsScreen
import com.pixel.gallery.ui.settings.ShortcutManagerScreen
import com.pixel.gallery.ui.gallery.FavouritesScreen
import com.pixel.gallery.ui.gallery.TrashScreen
import com.pixel.gallery.ui.gallery.HiddenAlbumsScreen
import com.pixel.gallery.ui.gallery.PhotoScreen
import com.pixel.gallery.ui.locked.LockedFolderScreen
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import com.pixel.gallery.ui.viewer.ViewerScreen
import com.pixel.gallery.ui.viewer.ViewerTransformStateStore
import com.pixel.gallery.ui.settings.ExcludedFoldersScreen
import com.pixel.gallery.ui.settings.LicensesScreen
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.components.DeleteConfirmDialog
import com.pixel.gallery.ui.settings.PerformanceSettingsScreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.filled.MoreVert
import kotlinx.coroutines.flow.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import androidx.compose.material.icons.filled.Sort
import com.pixel.gallery.ui.viewmodel.PhotoSortOrder
import com.pixel.gallery.ui.viewmodel.AlbumSortOrder
import androidx.compose.foundation.clickable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import com.pixel.gallery.ui.components.SortDialog
import com.pixel.gallery.ui.components.SortCriterion

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.launch
import com.pixel.gallery.model.TransferMode
import com.pixel.gallery.ui.transfer.TransferDestinationScreen

// Height of the toolbar + gap, used to pad content so last items aren't hidden
private val FloatingBarHeight = 80.dp

private val AlbumGridStatesSaver = listSaver<MutableMap<String, LazyGridState>, Any>(
    save = { states ->
        buildList {
            add(1) // Snapshot format version.
            states.forEach { (albumName, state) ->
                add(albumName)
                add(state.firstVisibleItemIndex)
                add(state.firstVisibleItemScrollOffset)
            }
        }
    },
    restore = { values ->
        val states = mutableMapOf<String, LazyGridState>()
        if ((values.firstOrNull() as? Number)?.toInt() == 1) {
            values.drop(1).chunked(3).forEach { fields ->
                if (fields.size == 3) {
                    val albumName = fields[0] as? String ?: return@forEach
                    val index = (fields[1] as? Number)?.toInt() ?: return@forEach
                    val offset = (fields[2] as? Number)?.toInt() ?: return@forEach
                    states[albumName] = LazyGridState(
                        firstVisibleItemIndex = index.coerceAtLeast(0),
                        firstVisibleItemScrollOffset = offset.coerceAtLeast(0),
                    )
                }
            }
        }
        states
    },
)

sealed class Screen : Parcelable {
    @Parcelize object Home : Screen()
    @Parcelize object Settings : Screen()
    @Parcelize object Favourites : Screen()
    @Parcelize object Trash : Screen()
    @Parcelize object HiddenAlbums : Screen()
    @Parcelize object LockedFolder : Screen()
    @Parcelize data class Viewer(
        val initialId: Long,
        val source: ViewerSource = ViewerSource.All,
        val albumName: String? = null,
        val externalUri: String? = null,
        val externalMimeType: String? = null
    ) : Screen()
    @Parcelize object ExcludedFolders : Screen()
    @Parcelize object Licenses : Screen()
    @Parcelize data class Photo(val albumName: String) : Screen()
    @Parcelize object ShortcutManager : Screen()
    @Parcelize object PerformanceSettings : Screen()
    @Parcelize data class TransferDestination(
        val entryIds: LongArray,
        val origin: TransferOrigin
    ) : Screen()

    enum class ViewerSource { All, Favourites, Trash, Album, Vault, External }
    enum class TransferOrigin { Grid, Viewer }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainScaffold(
    initialScreen: Screen = Screen.Home,
    initialHomeTab: Int = -1,
    photosViewModel: PhotosViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val allPhotos by photosViewModel.photos.collectAsState()
    val groupedPhotos by photosViewModel.groupedPhotos.collectAsState()
    val favourites by photosViewModel.favourites.collectAsState()
    val groupedFavourites by photosViewModel.groupedFavourites.collectAsState()
    val trash by photosViewModel.trashedMedia.collectAsState()
    val groupedTrash by photosViewModel.groupedTrashedMedia.collectAsState()
    val vault by photosViewModel.vaultEntries.collectAsState()
    val groupedVault by photosViewModel.groupedVaultEntries.collectAsState()
    val albums by photosViewModel.albums.collectAsState()
    val gridColumns by photosViewModel.gridColumns.collectAsState()
    val albumGridColumns by photosViewModel.albumGridColumns.collectAsState()
    val externalMedia by photosViewModel.externalMedia.collectAsState()

    val photoSortOrder by photosViewModel.photoSortOrder.collectAsState()
    val albumSortOrder by photosViewModel.albumSortOrder.collectAsState()

    var showPhotoSortDialog by remember { mutableStateOf(false) }
    var showAlbumSortDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isDeletePermanentlyConfirm by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var isVaultRestoreRunning by remember { mutableStateOf(false) }

    // Simple navigation stack
    var navigationStack by rememberSaveable {
        mutableStateOf(
            if (initialScreen == Screen.Home) {
                listOf<Screen>(Screen.Home)
            } else {
                listOf<Screen>(Screen.Home, initialScreen)
            }
        )
    }

    LaunchedEffect(externalMedia) {
        externalMedia?.let { media ->
            ViewerLoadMetrics.entryRequested(
                context = context.applicationContext,
                contentId = -1L,
                source = Screen.ViewerSource.External.name,
                sourceItems = 1,
                detail = "externalUri=${media.uri}",
            )
            navigationStack = listOf(Screen.Home, Screen.Viewer(initialId = -1L, source = Screen.ViewerSource.External, externalUri = media.uri, externalMimeType = media.mimeType))
            photosViewModel.clearExternalMediaUri()
        }
    }

    val currentScreen = navigationStack.last()

    LaunchedEffect(allPhotos) {
        if (ViewerLoadMetrics.currentEntryId() != 0L) {
            ViewerLoadMetrics.event(
                "SOURCE_FLOW_EMIT",
                "flow=allPhotos count=${allPhotos.size} screen=${currentScreen.javaClass.simpleName}",
            )
        }
    }
    LaunchedEffect(favourites) {
        if (ViewerLoadMetrics.currentEntryId() != 0L) {
            ViewerLoadMetrics.event("SOURCE_FLOW_EMIT", "flow=favourites count=${favourites.size}")
        }
    }
    LaunchedEffect(trash) {
        if (ViewerLoadMetrics.currentEntryId() != 0L) {
            ViewerLoadMetrics.event("SOURCE_FLOW_EMIT", "flow=trash count=${trash.size}")
        }
    }
    LaunchedEffect(vault) {
        if (ViewerLoadMetrics.currentEntryId() != 0L) {
            ViewerLoadMetrics.event("SOURCE_FLOW_EMIT", "flow=vault count=${vault.size}")
        }
    }
    LaunchedEffect(groupedPhotos) {
        if (ViewerLoadMetrics.currentEntryId() != 0L) {
            ViewerLoadMetrics.event("SOURCE_FLOW_EMIT", "flow=groupedPhotos groups=${groupedPhotos.size}")
        }
    }
    LaunchedEffect(albums) {
        if (ViewerLoadMetrics.currentEntryId() != 0L) {
            ViewerLoadMetrics.event("SOURCE_FLOW_EMIT", "flow=albums count=${albums.size}")
        }
    }

    // Hoisted Grid States for persistence
    val recentsGridState = rememberLazyGridState()
    val albumsGridState = rememberLazyGridState()
    val favouritesGridState = rememberLazyGridState()
    val trashGridState = rememberLazyGridState()
    val vaultGridState = rememberLazyGridState()
    val albumGridStates = rememberSaveable(saver = AlbumGridStatesSaver) {
        mutableMapOf<String, LazyGridState>()
    }
    val albumPhotoLists = remember {
        mutableStateMapOf<String, List<com.pixel.gallery.data.local.entity.MediaEntry>>()
    }
    val viewerTransformStateStore = rememberSaveable(saver = ViewerTransformStateStore.Saver) {
        ViewerTransformStateStore()
    }

    val startupAtAlbums by photosViewModel.startupAtAlbums.collectAsState()
    val homePagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Initialize tab based on preference once
    var hasInitializedTab by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startupAtAlbums, initialHomeTab) {
        if (!hasInitializedTab) {
            val initialPage = when (initialHomeTab) {
                0 -> 0
                1 -> 1
                else -> if (startupAtAlbums) 1 else 0
            }
            homePagerState.scrollToPage(initialPage)
            hasInitializedTab = true
        }
    }

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    val toggleSelection = { id: Long ->
        selectedIds = if (selectedIds.contains(id)) {
            selectedIds - id
        } else {
            selectedIds + id
        }
    }

    val updateSelection = { ids: Set<Long> ->
        selectedIds = ids
    }

    var showMenu by remember { mutableStateOf(false) }

    // System back button handling
    BackHandler(enabled = navigationStack.size > 1 || selectedIds.isNotEmpty()) {
        if (selectedIds.isNotEmpty()) {
            selectedIds = emptySet()
        } else {
            navigationStack = navigationStack.dropLast(1)
        }
    }


    // Reset selection when navigating
    LaunchedEffect(currentScreen) {
        if (ViewerLoadMetrics.currentEntryId() != 0L) {
            ViewerLoadMetrics.event(
                "NAVIGATION_SCREEN",
                "screen=$currentScreen baseGridRetained=${currentScreen is Screen.Viewer}",
            )
        }
        if (currentScreen !is Screen.TransferDestination) {
            selectedIds = emptySet()
        }
    }

    // Scroll behavior: bar exits when scrolling down, returns when scrolling up
    val scrollBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(
        exitDirection = FloatingToolbarExitDirection.Bottom
    )

    // Window Insets
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomPadding = if (currentScreen == Screen.Home) {
        FloatingBarHeight + 16.dp + navBarPadding
    } else {
        navBarPadding + 16.dp
    }

    val colorScheme = MaterialTheme.colorScheme
    val navigateBack: () -> Unit = {
        if (navigationStack.size > 1) {
            navigationStack = navigationStack.dropLast(1)
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    val selectedEntries = remember(selectedIds, allPhotos, trash, vault) {
        (allPhotos + trash + vault).filter { selectedIds.contains(it.contentId) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0), // Manual padding for full control
            modifier = Modifier.nestedScroll(scrollBehavior),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectedIds.isNotEmpty() && currentScreen !is Screen.TransferDestination) {
                // Contextual Top Bar for Selection
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        if (currentScreen == Screen.Trash) {
                            IconButton(onClick = {
                                photosViewModel.restoreMediaBulk(selectedEntries.map { it.uri })
                                selectedIds = emptySet()
                            }) {
                                Icon(Icons.Outlined.RestoreFromTrash, contentDescription = "Restore")
                            }
                            IconButton(onClick = {
                                isDeletePermanentlyConfirm = true
                                showDeleteConfirmDialog = true
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete permanently")
                            }
                        } else if (currentScreen == Screen.LockedFolder) {
                            IconButton(
                                enabled = !isVaultRestoreRunning,
                                onClick = {
                                    val idsToRestore = selectedIds.toList()
                                    isVaultRestoreRunning = true
                                    photosViewModel.restoreFromVaultBulk(idsToRestore) { result ->
                                        isVaultRestoreRunning = false
                                        selectedIds = result.failedIds.toSet()
                                        val message = buildString {
                                            append("Moved ${result.restoredIds.size} item")
                                            if (result.restoredIds.size != 1) append('s')
                                            append(" out of Locked Folder")
                                            if (result.failedIds.isNotEmpty()) {
                                                append("; ${result.failedIds.size} failed")
                                            }
                                        }
                                        scope.launch { snackbarHostState.showSnackbar(message) }
                                    }
                                }
                            ) {
                                if (isVaultRestoreRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.LockOpen,
                                        contentDescription = "Move out of Locked Folder"
                                    )
                                }
                            }
                        } else {
                            IconButton(onClick = {
                                val uris = selectedEntries.map {
                                    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", java.io.File(it.path))
                                }
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "*/*" // Could be more specific if all are same type
                                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Share Media"))
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "Share")
                            }

                            IconButton(onClick = {
                                photosViewModel.clearTransferState()
                                navigationStack = navigationStack + Screen.TransferDestination(
                                    selectedEntries.map { it.contentId }.toLongArray(),
                                    Screen.TransferOrigin.Grid
                                )
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_transfer_to_folder),
                                    contentDescription = "Move or copy to"
                                )
                            }

                            IconButton(onClick = {
                                isDeletePermanentlyConfirm = false
                                showDeleteConfirmDialog = true
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                            Box {
                                IconButton(onClick = { showSelectionMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(
                                    expanded = showSelectionMenu,
                                    onDismissRequest = { showSelectionMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Move to locked folder") },
                                        onClick = {
                                            showSelectionMenu = false
                                            selectedEntries.forEach { photosViewModel.moveToVault(it) }
                                            selectedIds = emptySet()
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    }
                )
            } else if (currentScreen == Screen.Home) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "Pixel Gallery",
                            style = EmphasizedTypography.HeadlineMedium
                        )
                    },
                    actions = {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (homePagerState.currentPage == 0) {
                                DropdownMenuItem(
                                    text = { Text("Sort Photos") },
                                    onClick = {
                                        showMenu = false
                                        showPhotoSortDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Sort Albums") },
                                    onClick = {
                                        showMenu = false
                                        showAlbumSortDialog = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Hidden Albums") },
                                onClick = {
                                    showMenu = false
                                    navigationStack = navigationStack + Screen.HiddenAlbums
                                },
                                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Locked Folder") },
                                onClick = {
                                    showMenu = false
                                    navigationStack = navigationStack + Screen.LockedFolder
                                },
                                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    navigationStack = navigationStack + Screen.Settings
                                },
                                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colorScheme.surface,
                        titleContentColor = colorScheme.onSurface
                    ),
                    windowInsets = WindowInsets.statusBars
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (currentScreen is Screen.Viewer) 0.dp else innerPadding.calculateTopPadding())
        ) {
            // Screen content management
            val baseScreen = remember(navigationStack) {
                navigationStack.lastOrNull {
                    it !is Screen.Viewer && it !is Screen.TransferDestination
                } ?: Screen.Home
            }

            // Keep the base screen composed so its LazyGridState and loaded thumbnails
            // survive back navigation, but do not draw it underneath the opaque viewer.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        if (currentScreen !is Screen.Viewer) drawContent()
                    }
            ) {
                when (baseScreen) {
                Screen.Home -> {
                    HorizontalPager(
                        state = homePagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = selectedIds.isEmpty() // Disable swiping during selection
                    ) { page ->
                        when (page) {
                            0 -> PhotosScreen(
                                items = groupedPhotos,
                                onNavigateToViewer = { id ->
                                    ViewerLoadMetrics.entryRequested(
                                        context.applicationContext,
                                        id,
                                        Screen.ViewerSource.All.name,
                                        allPhotos.size,
                                    )
                                    navigationStack = navigationStack + Screen.Viewer(id, Screen.ViewerSource.All)
                                },
                                selectedIds = selectedIds,
                                onSelectionChange = updateSelection,
                                onToggleSelection = toggleSelection,
                                columns = gridColumns,
                                onColumnsChange = { photosViewModel.setGridColumns(it) },
                                bottomPadding = contentBottomPadding,
                                state = recentsGridState
                            )
                            1 -> AlbumsScreen(
                                albums = albums,
                                bottomPadding = contentBottomPadding,
                                gridState = albumsGridState,
                                onNavigateToFavourites = { navigationStack = navigationStack + Screen.Favourites },
                                onNavigateToTrash = { navigationStack = navigationStack + Screen.Trash },
                                onNavigateToAlbum = { name -> navigationStack = navigationStack + Screen.Photo(name) },
                                onExclude = { path -> photosViewModel.addExcludedFolder(path) },
                                onHide = { path -> photosViewModel.addHiddenFolder(path) },
                                columns = albumGridColumns,
                                onColumnsChange = { photosViewModel.setAlbumGridColumns(it) }
                            )
                        }
                    }
                }
                Screen.Settings -> SettingsScreen(
                    onBack = navigateBack,
                    onNavigateToExcludedFolders = { navigationStack = navigationStack + Screen.ExcludedFolders },
                    onNavigateToLicenses = { navigationStack = navigationStack + Screen.Licenses },
                    onNavigateToShortcutManager = { navigationStack = navigationStack + Screen.ShortcutManager },
                    onNavigateToPerformanceSettings = { navigationStack = navigationStack + Screen.PerformanceSettings }
                )
                Screen.PerformanceSettings -> PerformanceSettingsScreen(
                    onBack = navigateBack
                )
                Screen.ShortcutManager -> ShortcutManagerScreen(
                    onBack = navigateBack
                )
                Screen.Favourites -> FavouritesScreen(
                    onBack = navigateBack,
                    onNavigateToViewer = { id ->
                        ViewerLoadMetrics.entryRequested(
                            context.applicationContext,
                            id,
                            Screen.ViewerSource.Favourites.name,
                            favourites.size,
                        )
                        navigationStack = navigationStack + Screen.Viewer(id, Screen.ViewerSource.Favourites)
                    },
                    selectedIds = selectedIds,
                    onSelectionChange = updateSelection,
                    onToggleSelection = toggleSelection,
                    items = groupedFavourites,
                    gridState = favouritesGridState
                )
                Screen.Trash -> TrashScreen(
                    onBack = navigateBack,
                    onNavigateToViewer = { id ->
                        ViewerLoadMetrics.entryRequested(
                            context.applicationContext,
                            id,
                            Screen.ViewerSource.Trash.name,
                            trash.size,
                        )
                        navigationStack = navigationStack + Screen.Viewer(id, Screen.ViewerSource.Trash)
                    },
                    selectedIds = selectedIds,
                    onSelectionChange = updateSelection,
                    onToggleSelection = toggleSelection,
                    items = groupedTrash,
                    gridState = trashGridState
                )
                Screen.HiddenAlbums -> HiddenAlbumsScreen(onBack = navigateBack)
                Screen.LockedFolder -> LockedFolderScreen(
                    onBack = navigateBack,
                    onNavigateToViewer = { id ->
                        ViewerLoadMetrics.entryRequested(
                            context.applicationContext,
                            id,
                            Screen.ViewerSource.Vault.name,
                            vault.size,
                        )
                        navigationStack = navigationStack + Screen.Viewer(id, Screen.ViewerSource.Vault)
                    },
                    selectedIds = selectedIds,
                    onSelectionChange = updateSelection,
                    onToggleSelection = toggleSelection,
                    items = groupedVault
                )
                Screen.ExcludedFolders -> ExcludedFoldersScreen(onBack = navigateBack)
                Screen.Licenses -> LicensesScreen(onBack = navigateBack)
                is Screen.Photo -> {
                    val albumName = (baseScreen as Screen.Photo).albumName
                    val albumState = remember(albumName) {
                        albumGridStates.getOrPut(albumName) { LazyGridState() }
                    }
                    PhotoScreen(
                        albumName = albumName,
                        onBack = navigateBack,
                        onNavigateToViewer = { id ->
                            ViewerLoadMetrics.entryRequested(
                                context.applicationContext,
                                id,
                                Screen.ViewerSource.Album.name,
                                allPhotos.size,
                                detail = "album=$albumName",
                            )
                            navigationStack = navigationStack + Screen.Viewer(id, Screen.ViewerSource.Album, albumName)
                        },
                        selectedIds = selectedIds,
                        onSelectionChange = updateSelection,
                        onToggleSelection = toggleSelection,
                        onAlbumPhotosChanged = { photos ->
                            albumPhotoLists[albumName] = photos
                        },
                        gridState = albumState
                    )
                }
                is Screen.Viewer -> {
                    // Fallback, baseScreen should not be Viewer
                }
                is Screen.TransferDestination -> {
                    // Fallback, transfer screens are rendered as overlays below.
                }
            }

            }

            // Overlay full-screen viewer if active
            if (currentScreen is Screen.Viewer) {
                val viewer = currentScreen
                val cachedAlbumPhotos = viewer.albumName?.let(albumPhotoLists::get)
                // [Legacy/Original Code commented out per user request - avoid emptyList() black screen flash on enter]
                // val photosForViewer by remember(viewer, allPhotos, favourites, trash, vault) {
                //     flow {
                //         val result = when (viewer.source) {
                //             Screen.ViewerSource.All -> allPhotos
                //             Screen.ViewerSource.Favourites -> favourites
                //             Screen.ViewerSource.Trash -> trash
                //             Screen.ViewerSource.Vault -> vault
                //             Screen.ViewerSource.Album -> {
                //                 allPhotos.filter {
                //                     val lastSlash = it.path.lastIndexOf('/')
                //                     if (lastSlash <= 0) false
                //                     else {
                //                         val parentPath = it.path.substring(0, lastSlash)
                //                         val prevSlash = parentPath.lastIndexOf('/')
                //                         val parentName = if (prevSlash >= 0) parentPath.substring(prevSlash + 1) else parentPath
                //                         parentName == viewer.albumName
                //                     }
                //                 }
                //             }
                //             Screen.ViewerSource.External -> {
                //                 val uri = viewer.externalUri ?: ""
                //                 val mimeType = viewer.externalMimeType ?: "image/*"
                //                 listOf(
                //                     com.pixel.gallery.data.local.entity.MediaEntry(
                //                         contentId = -1L,
                //                         path = uri,
                //                         uri = uri,
                //                         sourceMimeType = mimeType,
                //                         width = 0,
                //                         height = 0,
                //                         sourceRotationDegrees = 0,
                //                         sizeBytes = 0,
                //                         dateAddedSecs = 0,
                //                         dateModifiedMillis = 0,
                //                         isTrashed = false,
                //                         bestTimestamp = 0L
                //                     )
                //                 )
                //             }
                //         }
                //         emit(result)
                //     }.flowOn(kotlinx.coroutines.Dispatchers.Default)
                // }.collectAsState(initial = emptyList())
                val initialPhotosForViewer = remember(
                    viewer,
                    allPhotos,
                    favourites,
                    trash,
                    vault,
                    cachedAlbumPhotos,
                ) {
                    val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
                    var listSource = "DIRECT"
                    val result = when (viewer.source) {
                        Screen.ViewerSource.All -> allPhotos
                        Screen.ViewerSource.Favourites -> favourites
                        Screen.ViewerSource.Trash -> trash
                        Screen.ViewerSource.Vault -> vault
                        Screen.ViewerSource.Album -> {
                            cachedAlbumPhotos ?: run {
                                listSource = "FALLBACK_SCAN"
                                allPhotos.filter {
                                    val lastSlash = it.path.lastIndexOf('/')
                                    if (lastSlash <= 0) false
                                    else {
                                        val parentPath = it.path.substring(0, lastSlash)
                                        val prevSlash = parentPath.lastIndexOf('/')
                                        val parentName = if (prevSlash >= 0) parentPath.substring(prevSlash + 1) else parentPath
                                        parentName == viewer.albumName
                                    }
                                }
                            }
                        }
                        Screen.ViewerSource.External -> {
                            val uri = viewer.externalUri ?: ""
                            val mimeType = viewer.externalMimeType ?: "image/*"
                            listOf(
                                com.pixel.gallery.data.local.entity.MediaEntry(
                                    contentId = -1L,
                                    path = uri,
                                    uri = uri,
                                    sourceMimeType = mimeType,
                                    width = 0,
                                    height = 0,
                                    sourceRotationDegrees = 0,
                                    sizeBytes = 0,
                                    dateAddedSecs = 0,
                                    dateModifiedMillis = 0,
                                    isTrashed = false,
                                    bestTimestamp = 0L
                                )
                            )
                        }
                    }
                    ViewerLoadMetrics.event(
                        name = "VIEWER_LIST_READY",
                        detail = "source=${viewer.source} listSource=$listSource count=${result.size} " +
                            "duration=${(android.os.SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L}ms",
                    )
                    result
                }
                val photosForViewer by remember(viewer, initialPhotosForViewer) {
                    flow { emit(initialPhotosForViewer) }
                        .onStart {
                            ViewerLoadMetrics.event(
                                "VIEWER_LIST_FLOW_START",
                                "count=${initialPhotosForViewer.size}",
                            )
                        }
                        .onEach {
                            ViewerLoadMetrics.event("VIEWER_LIST_FLOW_EMIT", "count=${it.size}")
                        }
                        .flowOn(kotlinx.coroutines.Dispatchers.Default)
                }.collectAsState(initial = initialPhotosForViewer)

                if (photosForViewer.isNotEmpty()) {
                    ViewerScreen(
                        initialId = viewer.initialId,
                        photos = photosForViewer,
                        transformStateStore = viewerTransformStateStore,
                        onBack = { navigationStack = navigationStack.dropLast(1) },
                        allowTransfer = viewer.source !in setOf(
                            Screen.ViewerSource.Trash,
                            Screen.ViewerSource.Vault,
                            Screen.ViewerSource.External
                        ),
                        onRequestTransfer = { media ->
                            photosViewModel.clearTransferState()
                            navigationStack = navigationStack + Screen.TransferDestination(
                                longArrayOf(media.contentId),
                                Screen.TransferOrigin.Viewer
                            )
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                }
            }

            if (currentScreen is Screen.TransferDestination) {
                val transferScreen = currentScreen
                val transferableEntries = remember(transferScreen, allPhotos, favourites) {
                    val byId = (allPhotos + favourites).associateBy { it.contentId }
                    transferScreen.entryIds.toList().mapNotNull(byId::get)
                }
                TransferDestinationScreen(
                    entries = transferableEntries,
                    onBack = {
                        photosViewModel.clearTransferState()
                        navigationStack = navigationStack.dropLast(1)
                    },
                    onFinished = { summary ->
                        val action = if (summary.mode == TransferMode.MOVE) "Moved" else "Copied"
                        val message = buildString {
                            append("$action ${summary.succeeded} item")
                            if (summary.succeeded != 1) append('s')
                            if (summary.failed > 0) append("; ${summary.failed} failed")
                            if (summary.skipped > 0) append("; ${summary.skipped} skipped")
                        }
                        scope.launch { snackbarHostState.showSnackbar(message) }
                        selectedIds = emptySet()
                        navigationStack = if (
                            transferScreen.origin == Screen.TransferOrigin.Viewer &&
                            summary.mode == TransferMode.MOVE
                        ) {
                            navigationStack.dropLast(2)
                        } else {
                            navigationStack.dropLast(1)
                        }
                        photosViewModel.clearTransferState()
                    }
                )
            }



            // Only show the floating bar on the Home screen
            if (currentScreen == Screen.Home) {
                HorizontalFloatingToolbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 16.dp),
                    expanded = true,
                    scrollBehavior = scrollBehavior,
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
                    content = {
                        val tabs = listOf(
                            NavTab("Photos", Icons.Filled.Photo, Icons.Outlined.Photo),
                            NavTab("Albums", Icons.Filled.PhotoAlbum, Icons.Outlined.PhotoAlbum)
                        )

                        tabs.forEachIndexed { index, tab ->
                            val isSelected = homePagerState.currentPage == index

                            // Using a pill-shaped item that shows a label when selected
                            Surface(
                                onClick = {
                                    scope.launch {
                                        homePagerState.animateScrollToPage(index)
                                    }
                                },
                                shape = FloatingToolbarDefaults.ContainerShape,
                                color = if (isSelected) colorScheme.primaryContainer else colorScheme.surface,
                                contentColor = if (isSelected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    AnimatedVisibility(visible = isSelected) {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    // Custom sort dialog overlays
        val photoCriteria = remember {
            listOf(
                SortCriterion("DATE", "Date", "Oldest first", "Newest first"),
                SortCriterion("NAME", "Name", "A to Z", "Z to A"),
                SortCriterion("SIZE", "Size", "Smallest first", "Largest first")
            )
        }

        val albumCriteria = remember {
            listOf(
                SortCriterion("NAME", "Name", "A to Z", "Z to A"),
                SortCriterion("COUNT", "Count", "Least first", "Most first"),
                SortCriterion("DATE", "Date", "Oldest first", "Newest first")
            )
        }

        AnimatedVisibility(
            visible = showPhotoSortDialog,
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
                visible = showPhotoSortDialog,
                onDismissRequest = { showPhotoSortDialog = false },
                title = "Sort Photos By",
                criteria = photoCriteria,
                initialCriterion = currentCriterion,
                initialDirection = currentDirection,
                onConfirm = { criterion, direction ->
                    val newOrder = PhotoSortOrder.valueOf("${criterion}_${direction}")
                    photosViewModel.setPhotoSortOrder(newOrder)
                    showPhotoSortDialog = false
                }
            )
        }

        AnimatedVisibility(
            visible = showAlbumSortDialog,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(150))
        ) {
            val currentCriterion = when (albumSortOrder) {
                AlbumSortOrder.NAME_ASC, AlbumSortOrder.NAME_DESC -> "NAME"
                AlbumSortOrder.COUNT_DESC, AlbumSortOrder.COUNT_ASC -> "COUNT"
                AlbumSortOrder.DATE_DESC, AlbumSortOrder.DATE_ASC -> "DATE"
            }
            val currentDirection = when (albumSortOrder) {
                AlbumSortOrder.NAME_DESC, AlbumSortOrder.COUNT_DESC, AlbumSortOrder.DATE_DESC -> "DESC"
                AlbumSortOrder.NAME_ASC, AlbumSortOrder.COUNT_ASC, AlbumSortOrder.DATE_ASC -> "ASC"
            }
            SortDialog(
                visible = showAlbumSortDialog,
                onDismissRequest = { showAlbumSortDialog = false },
                title = "Sort Albums By",
                criteria = albumCriteria,
                initialCriterion = currentCriterion,
                initialDirection = currentDirection,
                onConfirm = { criterion, direction ->
                    val newOrder = AlbumSortOrder.valueOf("${criterion}_${direction}")
                    photosViewModel.setAlbumSortOrder(newOrder)
                    showAlbumSortDialog = false
                }
            )
        }

        DeleteConfirmDialog(
            visible = showDeleteConfirmDialog,
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = if (isDeletePermanentlyConfirm) "Delete permanently?" else "Move to Recycle Bin?",
            message = if (isDeletePermanentlyConfirm) {
                "Are you sure you want to permanently delete the selected items? This action cannot be undone."
            } else {
                "Move the selected items to the recycle bin?"
            },
            confirmLabel = if (isDeletePermanentlyConfirm) "Delete" else "Move to Bin",
            isDeletePermanently = isDeletePermanentlyConfirm,
            onConfirm = {
                if (isDeletePermanentlyConfirm) {
                    photosViewModel.deleteMediaBulk(selectedEntries.map { it.uri })
                } else {
                    photosViewModel.moveToTrashBulk(selectedEntries.map { it.uri })
                }
                selectedIds = emptySet()
            }
        )
    }
}

private data class NavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)
