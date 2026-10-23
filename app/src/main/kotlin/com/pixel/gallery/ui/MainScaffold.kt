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
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.ui.viewmodel.MediaBrowseIndex
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.pixel.gallery.ui.gallery.matchesAlbumSearch
import com.pixel.gallery.model.Album
import com.pixel.gallery.ui.home.PhotosScreen
import com.pixel.gallery.ui.home.AlbumsScreen
import com.pixel.gallery.ui.home.AlbumRenameDialog
import com.pixel.gallery.ui.components.AlbumSelectionTopBar
import com.pixel.gallery.ui.settings.SettingsScreen
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
import com.pixel.gallery.ui.components.HomeOverflowMenu
import com.pixel.gallery.ui.components.galleryContentEdge
import com.pixel.gallery.ui.settings.PerformanceSettingsScreen
import com.pixel.gallery.ui.settings.ThemeSettingsScreen
import com.pixel.gallery.ui.settings.DisplaySettingsScreen
import com.pixel.gallery.ui.settings.LibrarySettingsScreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.filled.MoreVert
import kotlinx.coroutines.flow.*
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.filled.CheckCircle
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import com.pixel.gallery.ui.viewmodel.PhotoSortOrder
import com.pixel.gallery.model.AlbumSortOrder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import com.pixel.gallery.ui.components.SortDialog
import com.pixel.gallery.ui.components.SortCriterion
import com.pixel.gallery.ui.components.AlbumSortDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Label
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.model.DeduplicationScope
import com.pixel.gallery.ui.deduplication.DuplicateReviewDialog
import com.pixel.gallery.ui.deduplication.DuplicateViewModel

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.pixel.gallery.model.TransferMode
import com.pixel.gallery.ui.transfer.TransferDestinationScreen
import com.pixel.gallery.ui.viewmodel.LabelsViewModel
import com.pixel.gallery.ui.viewmodel.QuickSortViewModel
import com.pixel.gallery.ui.labels.*
import com.pixel.gallery.ui.quicksort.*
import com.pixel.gallery.model.QuickSortEnqueueResult
import com.pixel.gallery.model.QuickSortJobStatus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.roundToInt

// Height of the toolbar + gap, used to pad content so last items aren't hidden
private val FloatingBarHeight = 80.dp
private const val HomeHeaderSwipeDistanceFraction = 0.2f
private const val HomeHeaderSwipeVelocityInWidthsPerSecond = 1.25f
private const val HomeSearchDebounceMillis = 80L

private data class HomeHeaderTransitionState(
    val isSearchMode: Boolean = false,
    val dragOffsetPx: Float = 0f,
)

private data class HomePhotoSearchResult(
    val mediaType: MediaTypeFilter,
    val photoQuery: String = "",
    val photos: List<MediaEntry> = emptyList(),
    val gridItems: List<GridItem> = emptyList(),
    val positionLabels: List<String?> = emptyList(),
)

private data class HomeAlbumSearchResult(
    val mediaType: MediaTypeFilter,
    val albumQuery: String = "",
    val albums: List<Album> = emptyList(),
)

internal fun shouldSwitchHomeHeader(
    dragOffsetPx: Float,
    widthPx: Float,
    velocityPxPerSecond: Float,
): Boolean {
    if (widthPx <= 0f) return false
    return abs(dragOffsetPx) / widthPx >= HomeHeaderSwipeDistanceFraction ||
        abs(velocityPxPerSecond) / widthPx >= HomeHeaderSwipeVelocityInWidthsPerSecond
}

internal fun toggleAlbumSelection(
    selectedIds: Set<Long>,
    albumIds: Set<Long>,
    reservedIds: Set<Long>,
): Set<Long> {
    val selectableIds = albumIds - reservedIds
    if (selectableIds.isEmpty()) return selectedIds - reservedIds
    return if (selectableIds.all { it in selectedIds }) {
        selectedIds - selectableIds
    } else {
        (selectedIds + selectableIds) - reservedIds
    }
}

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

internal fun MediaEntry.matchesFileSearch(query: String): Boolean {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return true

    val fileName = path
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { uri.substringAfterLast('/') }
    return fileName.contains(normalizedQuery, ignoreCase = true)
}

internal fun filterPhotoGridItems(
    items: List<GridItem>,
    query: String,
): List<GridItem> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return items

    val result = ArrayList<GridItem>(items.size)
    var pendingHeader: GridItem.Header? = null
    items.forEach { item ->
        when (item) {
            is GridItem.Header -> pendingHeader = item
            is GridItem.Photo -> {
                if (item.entry.matchesFileSearch(normalizedQuery)) {
                    pendingHeader?.let(result::add)
                    pendingHeader = null
                    result.add(item)
                }
            }
        }
    }
    return result
}

sealed class Screen : Parcelable {
    @Parcelize object Home : Screen()
    @Parcelize object Settings : Screen()
    @Parcelize object Favourites : Screen()
    @Parcelize object Labels : Screen()
    @Parcelize data class Label(val labelId: Long) : Screen()
    @Parcelize object Trash : Screen()
    @Parcelize object HiddenAlbums : Screen()
    @Parcelize object LockedFolder : Screen()
    @Parcelize data class Viewer(
        val initialId: Long,
        val source: ViewerSource = ViewerSource.All,
        val albumName: String? = null,
        val externalUri: String? = null,
        val externalMimeType: String? = null,
        val searchQuery: String? = null,
        val labelId: Long? = null,
        val mediaType: MediaTypeFilter = MediaTypeFilter.ALL,
    ) : Screen()
    @Parcelize object ExcludedFolders : Screen()
    @Parcelize object Licenses : Screen()
    @Parcelize data class Photo(
        val albumName: String,
        val initialMediaType: MediaTypeFilter = MediaTypeFilter.ALL,
    ) : Screen()
    @Parcelize object PerformanceSettings : Screen()
    @Parcelize object ThemeSettings : Screen()
    @Parcelize object DisplaySettings : Screen()
    @Parcelize object LibrarySettings : Screen()
    @Parcelize data class TransferDestination(
        val entryIds: LongArray,
        val origin: TransferOrigin
    ) : Screen()

    enum class ViewerSource { All, Search, Favourites, Label, Trash, Album, Vault, External }
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
    val labelsViewModel: LabelsViewModel = hiltViewModel()
    val quickSortViewModel: QuickSortViewModel = hiltViewModel()
    val labelEntries by labelsViewModel.labelEntries.collectAsState()
    val labelSummaries by labelsViewModel.labels.collectAsState()
    val labelMemberships by labelsViewModel.memberships.collectAsState()
    val recentLabelIds by labelsViewModel.recentLabelIds.collectAsState()
    val editorLabelEntries = remember(labelEntries, labelSummaries) {
        val byId = labelEntries.associateBy { it.id }
        val orderedIds = labelSummaries.map { it.label.id }
        orderedIds.mapNotNull(byId::get) + labelEntries.filterNot { it.id in orderedIds }
    }
    val labelsSaving by labelsViewModel.isSaving.collectAsState()
    val labelsError by labelsViewModel.errorMessage.collectAsState()
    val labelSaveGeneration by labelsViewModel.editorSaveGeneration.collectAsState()
    var observedLabelSaveGeneration by rememberSaveable { mutableLongStateOf(0L) }
    val quickQueue by quickSortViewModel.state.collectAsState()
    val quickReceipts = remember(quickQueue.jobs) { quickQueue.jobs.flatMap { job ->
        job.items.flatMap { listOfNotNull(it.replacementReceipt, it.receipt, it.replacementRestoreReceipt) }
    } }
    val quickPreferences by photosViewModel.quickSortPreferences.collectAsState()
    val transferTargets by photosViewModel.transferDestinations.collectAsState()
    var editingLabelIds by rememberSaveable(stateSaver = listSaver(
        save = { ids: Set<Long> -> ids.toList() }, restore = { it.toSet() },
    )) { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(labelSaveGeneration) {
        if (labelSaveGeneration > observedLabelSaveGeneration) editingLabelIds = emptySet()
        observedLabelSaveGeneration = labelSaveGeneration
    }
    var quickSortEnabled by rememberSaveable { mutableStateOf(false) }
    var quickSortOwner by rememberSaveable { mutableStateOf<Screen?>(null) }
    val quickSortSessionId = remember(quickSortEnabled) {
        if (quickSortEnabled) java.util.UUID.randomUUID().toString() else null
    }
    DisposableEffect(quickSortSessionId) {
        onDispose { quickSortSessionId?.let(quickSortViewModel::finishSession) }
    }
    val handedness by photosViewModel.handedness.collectAsState()
    val undoableQuickJob = com.pixel.gallery.model.latestUndoableQuickSortJob(quickQueue, quickSortSessionId)
    var quickWarning by remember { mutableStateOf<String?>(null) }
    val quickFailure by quickSortViewModel.failure.collectAsState()
    var showQuickTargets by remember { mutableStateOf(false) }
    var quickTargetsPanelHeight by remember { mutableStateOf(0.dp) }
    var pendingQuickJob by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingQuickToken by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val quickPermissionRequest by quickSortViewModel.permissionRequest.collectAsState()
    val quickPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val job = pendingQuickJob
        val token = pendingQuickToken
        pendingQuickJob = null
        pendingQuickToken = null
        if (job != null && token != null) quickSortViewModel.authorize(job, token, result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(quickQueue.needsPermission?.permissionToken, quickQueue.permissionRevision, pendingQuickToken, lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED && pendingQuickToken == null) quickSortViewModel.requestNextPermission()
    }
    LaunchedEffect(quickPermissionRequest?.token, quickQueue.needsPermission?.permissionToken, pendingQuickToken, lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED && pendingQuickToken == null) {
            quickPermissionRequest?.let { request ->
                if (quickQueue.needsPermission?.permissionToken != request.token) return@let
                pendingQuickJob = request.jobId
                pendingQuickToken = request.token
                try { quickPermissionLauncher.launch(request.request) }
                catch (_: Exception) {
                    pendingQuickJob = null
                    pendingQuickToken = null
                    quickSortViewModel.authorize(request.jobId, request.token, false)
                }
            }
        }
    }
    LaunchedEffect(quickQueue.loaded, lifecycleState, quickQueue.serviceActive, quickQueue.jobs.map { it.status }) {
        if (lifecycleState == Lifecycle.State.RESUMED && quickQueue.loaded &&
            !quickQueue.serviceActive && quickQueue.jobs.any { it.status == QuickSortJobStatus.Queued }) {
            quickSortViewModel.resume()
        }
    }
    val duplicateViewModel: DuplicateViewModel = hiltViewModel()
    val duplicateState by duplicateViewModel.state.collectAsState()
    val duplicateTrashRequest by duplicateViewModel.trashRequest.collectAsState()
    val duplicateOriginalAccessRequest by duplicateViewModel.originalAccessRequest.collectAsState()
    val duplicateOriginalAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        duplicateViewModel.onOriginalAccessResult(granted, context.getString(R.string.duplicate_original_access_required))
    }
    LaunchedEffect(duplicateOriginalAccessRequest) {
        if (duplicateOriginalAccessRequest) {
            duplicateViewModel.originalRequestLaunched()
            duplicateOriginalAccessLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    val duplicateTrashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        duplicateViewModel.onTrashResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(duplicateState.removedCount) {
        if (duplicateState.removedCount > 0) photosViewModel.refresh()
    }
    LaunchedEffect(duplicateTrashRequest) {
        duplicateTrashRequest?.let { request ->
            duplicateViewModel.requestLaunched()
            try {
                duplicateTrashLauncher.launch(request)
            } catch (error: Exception) {
                duplicateViewModel.requestFailed(error)
            }
        }
    }
    val allPhotos by photosViewModel.photos.collectAsState()
    val browsingIndex by photosViewModel.browsingIndex.collectAsState()
    val groupedPhotos by photosViewModel.groupedPhotos.collectAsState()
    val favourites by photosViewModel.favourites.collectAsState()
    val groupedFavourites by photosViewModel.groupedFavourites.collectAsState()
    val trash by photosViewModel.trashedMedia.collectAsState()
    val groupedTrash by photosViewModel.groupedTrashedMedia.collectAsState()
    val vault by photosViewModel.vaultEntries.collectAsState()
    val groupedVault by photosViewModel.groupedVaultEntries.collectAsState()
    val albums by photosViewModel.albums.collectAsState()
    val gridColumns by photosViewModel.gridColumns.collectAsState()
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
    // A viewer belongs to its underlying grid; another album or Home starts a different scope.
    val quickSortScope = navigationStack.lastOrNull { it !is Screen.Viewer }
    val activeLabelId = when (val labelSource = navigationStack.lastOrNull { it !is Screen.TransferDestination }) {
        is Screen.Label -> labelSource.labelId
        is Screen.Viewer -> labelSource.labelId
        else -> null
    }
    val activeLabelPhotos = remember(activeLabelId, allPhotos, labelMemberships) {
        val ids = labelMemberships.filter { it.labelId == activeLabelId }.mapTo(hashSetOf()) { it.contentId }
        allPhotos.filter { it.contentId in ids }
    }
    val groupedLabelPhotos by produceState<List<GridItem>>(emptyList(), activeLabelPhotos, gridColumns, photoSortOrder) {
        value = withContext(Dispatchers.Default) { photosViewModel.groupMedia(activeLabelPhotos, gridColumns, photoSortOrder) }
    }
    val favouriteLabelName = stringResource(R.string.labels_favourites)
    val labelNamesByMedia = remember(labelEntries, labelMemberships, favouriteLabelName) {
        val names = labelEntries.associate { it.id to if (it.isSystem) favouriteLabelName else it.name }
        labelMemberships.groupBy { it.contentId }.mapValues { (_, links) -> links.mapNotNull { names[it.labelId] } }
    }
    val labelIdsByMedia = remember(labelMemberships) {
        labelMemberships.groupBy { it.contentId }.mapValues { (_, links) -> links.map { it.labelId } }
    }

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
    var homeHeaderTransition by remember { mutableStateOf(HomeHeaderTransitionState()) }
    val isHomeSearchMode = homeHeaderTransition.isSearchMode
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val rootView = LocalView.current
    var homeHeaderCenterCorrectionPx by remember { mutableIntStateOf(0) }
    var homeHeaderWidthPx by remember { mutableFloatStateOf(0f) }
    var isHomeHeaderAnimating by remember { mutableStateOf(false) }
    val homeHeaderDraggableState = rememberDraggableState { delta ->
        if (!isHomeHeaderAnimating && homeHeaderWidthPx > 0f) {
            homeHeaderTransition = homeHeaderTransition.copy(
                dragOffsetPx = (homeHeaderTransition.dragOffsetPx + delta)
                    .coerceIn(-homeHeaderWidthPx, homeHeaderWidthPx),
            )
        }
    }
    var homeMediaType by rememberSaveable { mutableStateOf(MediaTypeFilter.ALL) }
    var photoSearchQuery by rememberSaveable { mutableStateOf("") }
    var albumSearchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedPhotoSearchQuery = photoSearchQuery.trim()
    val normalizedAlbumSearchQuery = albumSearchQuery.trim()
    // Cached category views are selected immediately. Only search scans run again.
    val categoryView = browsingIndex.library[homeMediaType]
    val photoSearchResult by produceState(
        HomePhotoSearchResult(homeMediaType), browsingIndex, homeMediaType, normalizedPhotoSearchQuery,
    ) {
        val type = homeMediaType
        val query = normalizedPhotoSearchQuery
        if (query.isNotEmpty()) delay(HomeSearchDebounceMillis)
        value = withContext(Dispatchers.Default) {
            val taskContext = coroutineContext
            val result = browsingIndex.library[type].search(query, { taskContext.ensureActive() }) { entry, text ->
                entry.matchesFileSearch(text)
            }
            HomePhotoSearchResult(type, query, result.photos, result.gridItems, result.positionLabels)
        }
    }
    val albumSearchResult by produceState(
        HomeAlbumSearchResult(homeMediaType), browsingIndex, homeMediaType, albumSortOrder, normalizedAlbumSearchQuery,
    ) {
        val type = homeMediaType
        val query = normalizedAlbumSearchQuery
        if (query.isNotEmpty()) delay(HomeSearchDebounceMillis)
        value = withContext(Dispatchers.Default) {
            val albums = browsingIndex.library[type].albums(albumSortOrder)
            HomeAlbumSearchResult(type, query, if (query.isEmpty()) albums else albums.filter {
                it.name.contains(query, ignoreCase = true)
            })
        }
    }
    val searchedPhotos = if (normalizedPhotoSearchQuery.isEmpty()) categoryView.media.photos
        else if (photoSearchResult.mediaType == homeMediaType) photoSearchResult.photos else emptyList()
    val searchedPhotoGridItems = if (normalizedPhotoSearchQuery.isEmpty()) categoryView.media.gridItems
        else if (photoSearchResult.mediaType == homeMediaType) photoSearchResult.gridItems else emptyList()
    val searchedAlbums = if (albumSearchResult.mediaType == homeMediaType) albumSearchResult.albums else emptyList()
    val searchedPositionLabels = if (normalizedPhotoSearchQuery.isEmpty()) categoryView.media.positionLabels
        else if (photoSearchResult.mediaType == homeMediaType) photoSearchResult.positionLabels else emptyList()
    val photoSearchGridState = rememberLazyGridState()
    val albumSearchGridState = rememberLazyGridState()

    LaunchedEffect(photoSearchResult.photoQuery) {
        if (photoSearchResult.photoQuery.isNotEmpty()) photoSearchGridState.scrollToItem(0)
    }
    LaunchedEffect(albumSearchResult.albumQuery) {
        if (albumSearchResult.albumQuery.isNotEmpty()) albumSearchGridState.scrollToItem(0)
    }

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
    var selectedAlbumPaths by remember { mutableStateOf(emptySet<String>()) }
    var renamingAlbum by remember { mutableStateOf<Album?>(null) }
    val albumSelectionMode = currentScreen == Screen.Home && selectedAlbumPaths.isNotEmpty()
    val toggleAlbumPath: (String) -> Unit = { path ->
        selectedAlbumPaths = if (path in selectedAlbumPaths) selectedAlbumPaths - path else selectedAlbumPaths + path
    }
    var retainEmptyAlbumSelection by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty() ||
        (currentScreen is Screen.Photo && retainEmptyAlbumSelection)
    LaunchedEffect(quickQueue.reservedIds) { selectedIds = selectedIds - quickQueue.reservedIds }
    val openLabelEditor: (Set<Long>) -> Unit = { ids ->
        labelsViewModel.clearError()
        editingLabelIds = ids - quickQueue.reservedIds
    }
    val onQuickEnqueued: (QuickSortEnqueueResult) -> Unit = { result ->
        when (result) {
            is QuickSortEnqueueResult.Accepted -> {
                selectedIds = selectedIds - result.acceptedIds
            }
            is QuickSortEnqueueResult.Rejected -> {
                quickWarning = result.reason
            }
        }
    }
    val toggleQuickSort: () -> Unit = {
        quickSortEnabled = !quickSortEnabled
        if (quickSortEnabled) {
            quickSortOwner = quickSortScope
            photosViewModel.setQuickSortDockTop(false)
            showQuickTargets = true
        } else {
            showQuickTargets = false
            quickTargetsPanelHeight = 0.dp
        }
    }
    // Keep user order, while refreshing covers from the current album inventory.
    val quickSortTargets = remember(quickPreferences.targets, transferTargets) {
        val currentAlbums = transferTargets.associateBy { it.stableKey }
        quickPreferences.targets.map { currentAlbums[it.stableKey] ?: it }
    }
    val quickSortConfig = if (quickSortEnabled && quickSortOwner == quickSortScope) QuickSortUiConfig(
        targets = quickSortTargets,
        dockTop = false,
        onDockTopChange = {},
        onReorderTargets = photosViewModel::setQuickSortTargets,
        onManageTargets = { showQuickTargets = true },
        onMove = { ids, destination ->
            quickSortViewModel.enqueue(allPhotos.filter { it.contentId in ids }, destination, onQuickEnqueued, quickSortSessionId)
        },
        // Do not draw the dock at the screen edge during the first frame of
        // the chooser. It is measured immediately by the sheet; reserving a
        // temporary off-screen inset prevents a one-frame overlap.
        dockBottomInset = if (showQuickTargets && quickTargetsPanelHeight == 0.dp) {
            1000.dp
        } else {
            quickTargetsPanelHeight
        },
        reservedIds = quickQueue.reservedIds,
        handedness = handedness,
        undoAvailable = undoableQuickJob != null,
        onUndo = {
            val job = undoableQuickJob
            val session = quickSortSessionId
            if (job != null && session != null) quickSortViewModel.undo(job.id, session) { error ->
                if (error != null) {
                    quickWarning = error
                }
            }
        },
        onExit = {
            selectedIds = emptySet()
            retainEmptyAlbumSelection = false
            quickSortEnabled = false
            showQuickTargets = false
            quickTargetsPanelHeight = 0.dp
        },
    ) else null

    // Focus only when the title itself transitions into search. Returning from
    // selection or the viewer keeps the keyboard closed until the field is tapped.
    LaunchedEffect(isHomeSearchMode) {
        if (currentScreen == Screen.Home && isHomeSearchMode) {
            searchFocusRequester.requestFocus()
        } else {
            focusManager.clearFocus()
            if (!isHomeSearchMode) {
                photoSearchQuery = ""
                albumSearchQuery = ""
            }
        }
    }
    LaunchedEffect(currentScreen, selectedIds.isNotEmpty()) {
        if (currentScreen != Screen.Home || selectedIds.isNotEmpty()) {
            focusManager.clearFocus()
        }
    }

    val toggleSelection = { id: Long ->
        retainEmptyAlbumSelection = false
        selectedIds = if (selectedIds.contains(id)) {
            selectedIds - id
        } else {
            selectedIds + (setOf(id) - quickQueue.reservedIds)
        }
    }

    val updateSelection = { ids: Set<Long> ->
        retainEmptyAlbumSelection = false
        selectedIds = ids - quickQueue.reservedIds
    }

    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(isHomeSearchMode) {
        if (isHomeSearchMode) showMenu = false
    }

    // System back button handling
    BackHandler(
        enabled = navigationStack.size > 1 ||
            quickSortEnabled ||
            albumSelectionMode ||
            selectionMode ||
            (currentScreen == Screen.Home && isHomeSearchMode)
    ) {
        if (albumSelectionMode) {
            selectedAlbumPaths = emptySet()
        } else if (selectionMode) {
            retainEmptyAlbumSelection = false
            selectedIds = emptySet()
        } else if (quickSortEnabled && currentScreen !is Screen.Viewer) {
            quickSortEnabled = false
            showQuickTargets = false
            quickTargetsPanelHeight = 0.dp
        } else if (currentScreen == Screen.Home && isHomeSearchMode) {
            homeHeaderTransition = HomeHeaderTransitionState()
        } else {
            navigationStack = navigationStack.dropLast(1)
        }
    }


    // Reset selection when navigating
    LaunchedEffect(currentScreen) {
        selectedAlbumPaths = emptySet()
        if (ViewerLoadMetrics.currentEntryId() != 0L) {
            ViewerLoadMetrics.event(
                "NAVIGATION_SCREEN",
                "screen=$currentScreen baseGridRetained=${currentScreen is Screen.Viewer}",
            )
        }
        if (currentScreen !is Screen.TransferDestination) {
            selectedIds = emptySet()
            retainEmptyAlbumSelection = false
        }
        if (quickSortOwner != quickSortScope ||
            (currentScreen !is Screen.Viewer && currentScreen !is Screen.Photo && currentScreen !is Screen.Label && currentScreen != Screen.Home)) {
            quickSortEnabled = false
            quickSortOwner = null
            showQuickTargets = false
            quickTargetsPanelHeight = 0.dp
        }
    }

    // Scroll behavior: bar exits when scrolling down, returns when scrolling up
    val scrollBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(
        exitDirection = FloatingToolbarExitDirection.Bottom
    )
    // Equivalent to Simple Gallery's SCROLL | ENTER_ALWAYS AppBarLayout flags.
    // It reacts only to nested-scroll events; there is no polling or background loop.
    val homeTopBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    LaunchedEffect(isHomeSearchMode) {
        if (isHomeSearchMode) {
            // A focused search field must not remain partially collapsed from the
            // list's previous scroll position.
            homeTopBarScrollBehavior.state.heightOffset = 0f
        }
    }

    // Window Insets
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottomPadding = if (quickSortEnabled) {
        navBarPadding
    } else if (currentScreen == Screen.Home) {
        FloatingBarHeight + 16.dp + navBarPadding
    } else {
        navBarPadding + 16.dp
    }

    val colorScheme = MaterialTheme.colorScheme
    val appShellColor = colorScheme.surface
    val headerColor = colorScheme.surfaceContainerLow
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(headerColor)
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0), // Manual padding for full control
        containerColor = appShellColor,
            modifier = Modifier
                .nestedScroll(scrollBehavior)
                .then(
                    if (
                        currentScreen == Screen.Home &&
                        !albumSelectionMode &&
                        selectedIds.isEmpty() &&
                        !isHomeSearchMode && !quickSortEnabled
                    ) {
                        Modifier.nestedScroll(homeTopBarScrollBehavior.nestedScrollConnection)
                    } else {
                        Modifier
                    }
                ),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (albumSelectionMode) {
                AlbumSelectionTopBar(
                    count = selectedAlbumPaths.size,
                    onClear = { selectedAlbumPaths = emptySet() },
                    onRename = {
                        renamingAlbum = searchedAlbums.firstOrNull { it.path == selectedAlbumPaths.singleOrNull() }
                    },
                    onHide = {
                        selectedAlbumPaths.forEach(photosViewModel::addHiddenFolder)
                        selectedAlbumPaths = emptySet()
                    },
                    onExclude = {
                        selectedAlbumPaths.forEach(photosViewModel::addExcludedFolder)
                        selectedAlbumPaths = emptySet()
                    },
                )
            } else if (selectionMode && currentScreen !is Screen.TransferDestination) {
                // Contextual Top Bar for Selection
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = headerColor),
                    title = { com.pixel.gallery.ui.components.SelectedCountBadge(selectedIds.size) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet(); retainEmptyAlbumSelection = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        Row(
                            // Keep each IconButton's 48dp touch target while reducing
                            // the visual center-to-center spacing to 40dp.
                            horizontalArrangement = Arrangement.spacedBy((-8).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (currentScreen is Screen.Photo) {
                                val currentAlbumName = currentScreen.albumName
                                val currentAlbumIds = albumPhotoLists[currentAlbumName]
                                    .orEmpty()
                                    .map { it.contentId }
                                    .filterNot { it in quickQueue.reservedIds }
                                    .toSet()
                                val isAlbumFullySelected = currentAlbumIds.isNotEmpty() &&
                                    currentAlbumIds.all { it in selectedIds }
                                IconButton(
                                    enabled = currentAlbumIds.isNotEmpty(),
                                    onClick = {
                                        selectedIds = toggleAlbumSelection(
                                            selectedIds = selectedIds,
                                            albumIds = currentAlbumIds,
                                            reservedIds = quickQueue.reservedIds,
                                        )
                                        retainEmptyAlbumSelection = selectedIds.isEmpty()
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = stringResource(
                                            if (isAlbumFullySelected) R.string.deselect_all else R.string.select_all
                                        ),
                                    )
                                }
                            }
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
                            } else if (currentScreen is Screen.Label) {
                                IconButton(
                                    enabled = selectedIds.isNotEmpty() && !labelsSaving,
                                    onClick = {
                                        labelsViewModel.removeMediaFromLabel(currentScreen.labelId, selectedIds) {
                                            selectedIds = emptySet()
                                        }
                                    },
                                ) {
                                    Icon(Icons.Outlined.Label, contentDescription = "Remove from label")
                                }
                            } else {
                                IconButton(enabled = selectedEntries.isNotEmpty(), onClick = {
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

                                IconButton(enabled = selectedEntries.isNotEmpty(), onClick = {
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

                                IconButton(enabled = selectedEntries.isNotEmpty(), onClick = {
                                    isDeletePermanentlyConfirm = false
                                    showDeleteConfirmDialog = true
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                                Box {
                                    IconButton(enabled = selectedEntries.isNotEmpty(), onClick = { showSelectionMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                                    }
                                    DropdownMenu(
                                        expanded = showSelectionMenu,
                                        onDismissRequest = { showSelectionMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.label_edit)) },
                                            onClick = { showSelectionMenu = false; openLabelEditor(selectedIds) },
                                            leadingIcon = { Icon(Icons.Outlined.Label, null) },
                                        )
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
                    }
                )
            } else if (currentScreen == Screen.Home) {
                CenterAlignedTopAppBar(
                    title = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .onGloballyPositioned { coordinates ->
                                    val headerCenterX =
                                        coordinates.positionInWindow().x +
                                            coordinates.size.width / 2f
                                    homeHeaderCenterCorrectionPx =
                                        (rootView.width / 2f - headerCenterX).roundToInt()
                                },
                        ) {
                            val searchingAlbums = homePagerState.currentPage == 1
                            val query = if (searchingAlbums) {
                                albumSearchQuery
                            } else {
                                photoSearchQuery
                            }
                            val dragOffsetPx = homeHeaderTransition.dragOffsetPx
                            val dragDirection = when {
                                dragOffsetPx > 0f -> 1f
                                dragOffsetPx < 0f -> -1f
                                else -> 1f
                            }
                            val nextPageOffsetPx =
                                dragOffsetPx - dragDirection * homeHeaderWidthPx
                            val titleOffsetPx = if (isHomeSearchMode) {
                                nextPageOffsetPx
                            } else {
                                dragOffsetPx
                            }
                            val searchOffsetPx = if (isHomeSearchMode) {
                                dragOffsetPx
                            } else {
                                nextPageOffsetPx
                            }

                            val headerPage: @Composable (Boolean, Modifier) -> Unit =
                                { searchPage, pageModifier ->
                                    Box(
                                        modifier = pageModifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (!searchPage) {
                                            Text(
                                                text = "Pixel Gallery",
                                                modifier = Modifier.align(Alignment.Center),
                                                style = EmphasizedTypography.HeadlineMedium,
                                            )
                                            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                                                IconButton(onClick = { showMenu = !showMenu }) {
                                                    Icon(
                                                        Icons.Default.MoreVert,
                                                        contentDescription = "More",
                                                    )
                                                }
                                                HomeOverflowMenu(
                                                    expanded = showMenu,
                                                    mediaType = homeMediaType,
                                                    onMediaTypeChange = { type ->
                                                        showMenu = false
                                                        if (type != homeMediaType) {
                                                            homeMediaType = type
                                                            selectedIds = emptySet()
                                                            scope.launch {
                                                                recentsGridState.scrollToItem(0)
                                                                albumsGridState.scrollToItem(0)
                                                                photoSearchGridState.scrollToItem(0)
                                                                albumSearchGridState.scrollToItem(0)
                                                            }
                                                        }
                                                    },
                                                    onDismissRequest = { showMenu = false },
                                                    showQuickSort = homePagerState.currentPage == 0 && !quickSortEnabled,
                                                    sortLabel = if (homePagerState.currentPage == 0) "Sort photos" else "Sort albums",
                                                    duplicateLabel = stringResource(
                                                        if (homePagerState.currentPage == 0) R.string.duplicate_find_all
                                                        else R.string.duplicate_find_each_album,
                                                    ),
                                                    canFindDuplicates = allPhotos.size > 1,
                                                    onQuickSort = {
                                                        showMenu = false
                                                        toggleQuickSort()
                                                    },
                                                    onSort = {
                                                        showMenu = false
                                                        if (homePagerState.currentPage == 0) showPhotoSortDialog = true
                                                        else showAlbumSortDialog = true
                                                    },
                                                    onFindDuplicates = {
                                                        showMenu = false
                                                        val isPhotosPage = homePagerState.currentPage == 0
                                                        duplicateViewModel.start(
                                                            allPhotos,
                                                            if (isPhotosPage) DeduplicationScope.ALL_FILES else DeduplicationScope.WITHIN_ALBUMS,
                                                            context.getString(
                                                                if (isPhotosPage) R.string.duplicate_scope_all else R.string.duplicate_scope_each_album,
                                                            ),
                                                        )
                                                    },
                                                    onHiddenAlbums = {
                                                        showMenu = false
                                                        navigationStack = navigationStack + Screen.HiddenAlbums
                                                    },
                                                    onLockedFolder = {
                                                        showMenu = false
                                                        navigationStack = navigationStack + Screen.LockedFolder
                                                    },
                                                    onSettings = {
                                                        showMenu = false
                                                        navigationStack = navigationStack + Screen.Settings
                                                    },
                                                )
                                            }
                                        } else {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Spacer(Modifier.width(16.dp).fillMaxHeight())
                                                OutlinedTextField(
                                                    value = query,
                                                    onValueChange = { value ->
                                                        if (searchingAlbums) {
                                                            albumSearchQuery = value
                                                        } else {
                                                            photoSearchQuery = value
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(56.dp)
                                                        .focusRequester(searchFocusRequester),
                                                    placeholder = {
                                                        Text(
                                                            text = if (searchingAlbums) {
                                                                "Search albums"
                                                            } else {
                                                                "Search files"
                                                            },
                                                            style = MaterialTheme.typography.bodyLarge,
                                                        )
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Default.Search,
                                                            contentDescription = null,
                                                        )
                                                    },
                                                    trailingIcon = {
                                                        if (query.isNotEmpty()) {
                                                            IconButton(
                                                                onClick = {
                                                                    if (searchingAlbums) {
                                                                        albumSearchQuery = ""
                                                                    } else {
                                                                        photoSearchQuery = ""
                                                                    }
                                                                },
                                                            ) {
                                                                Icon(
                                                                    Icons.Default.Close,
                                                                    contentDescription = "Clear search",
                                                                )
                                                            }
                                                        }
                                                    },
                                                    textStyle = MaterialTheme.typography.bodyLarge,
                                                    singleLine = true,
                                                    shape = CircleShape,
                                                )
                                                Spacer(Modifier.width(16.dp).fillMaxHeight())
                                            }
                                        }
                                    }
                                }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset {
                                        IntOffset(homeHeaderCenterCorrectionPx, 0)
                                    }
                                    .onSizeChanged {
                                        homeHeaderWidthPx = it.width.toFloat()
                                    }
                                    .draggable(
                                        state = homeHeaderDraggableState,
                                        orientation = Orientation.Horizontal,
                                        enabled = selectedIds.isEmpty() && !isHomeHeaderAnimating,
                                        onDragStopped = { velocity ->
                                            if (
                                                homeHeaderWidthPx <= 0f ||
                                                homeHeaderTransition.dragOffsetPx == 0f
                                            ) {
                                                homeHeaderTransition = homeHeaderTransition.copy(
                                                    dragOffsetPx = 0f,
                                                )
                                                return@draggable
                                            }

                                            val startingState = homeHeaderTransition
                                            val switchPage = shouldSwitchHomeHeader(
                                                dragOffsetPx = startingState.dragOffsetPx,
                                                widthPx = homeHeaderWidthPx,
                                                velocityPxPerSecond = velocity,
                                            )
                                            val direction =
                                                if (startingState.dragOffsetPx > 0f) 1f else -1f
                                            val targetOffset = if (switchPage) {
                                                direction * homeHeaderWidthPx
                                            } else {
                                                0f
                                            }

                                            isHomeHeaderAnimating = true
                                            try {
                                                val animation = androidx.compose.animation.core.Animatable(
                                                    startingState.dragOffsetPx,
                                                )
                                                animation.animateTo(
                                                    targetValue = targetOffset,
                                                    animationSpec = tween(durationMillis = 180),
                                                ) {
                                                    homeHeaderTransition = startingState.copy(
                                                        dragOffsetPx = value,
                                                    )
                                                }
                                                homeHeaderTransition = HomeHeaderTransitionState(
                                                    isSearchMode = if (switchPage) {
                                                        !startingState.isSearchMode
                                                    } else {
                                                        startingState.isSearchMode
                                                    },
                                                    dragOffsetPx = 0f,
                                                )
                                            } finally {
                                                isHomeHeaderAnimating = false
                                            }
                                        },
                                    ),
                            ) {
                                headerPage(
                                    false,
                                    Modifier.offset {
                                        IntOffset(titleOffsetPx.roundToInt(), 0)
                                    },
                                )
                                headerPage(
                                    true,
                                    Modifier.offset {
                                        IntOffset(searchOffsetPx.roundToInt(), 0)
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = headerColor,
                        scrolledContainerColor = headerColor,
                        titleContentColor = colorScheme.onSurface
                    ),
                    windowInsets = WindowInsets.statusBars,
                    scrollBehavior = if (isHomeSearchMode) null else homeTopBarScrollBehavior
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (currentScreen is Screen.Viewer) 0.dp else innerPadding.calculateTopPadding())
                .then(if (currentScreen == Screen.Home || selectionMode) Modifier.galleryContentEdge() else Modifier)
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
                        userScrollEnabled = selectedIds.isEmpty() && !albumSelectionMode && !quickSortEnabled
                    ) { page ->
                        when (page) {
                            0 -> PhotosScreen(
                                quickSort = quickSortConfig,
                                items = searchedPhotoGridItems,
                                positionLabels = searchedPositionLabels,
                                onNavigateToViewer = { id ->
                                    val isSearchActive = normalizedPhotoSearchQuery.isNotEmpty() && photoSearchResult.photoQuery.isNotEmpty()
                                    ViewerLoadMetrics.entryRequested(
                                        context.applicationContext,
                                        id,
                                        if (isSearchActive) {
                                            Screen.ViewerSource.Search.name
                                        } else {
                                            Screen.ViewerSource.All.name
                                        },
                                        searchedPhotos.size,
                                    )
                                    navigationStack = navigationStack + Screen.Viewer(
                                        initialId = id,
                                        source = if (isSearchActive) {
                                            Screen.ViewerSource.Search
                                        } else {
                                            Screen.ViewerSource.All
                                        },
                                        searchQuery = photoSearchResult.photoQuery.takeIf { isSearchActive },
                                        mediaType = homeMediaType,
                                    )
                                },
                                selectedIds = selectedIds,
                                onSelectionChange = updateSelection,
                                onToggleSelection = toggleSelection,
                                columns = gridColumns,
                                onColumnsChange = { photosViewModel.setGridColumns(it) },
                                bottomPadding = contentBottomPadding,
                                state = if (normalizedPhotoSearchQuery.isEmpty()) {
                                    recentsGridState
                                } else {
                                    photoSearchGridState
                                },
                                emptyMessage = if (normalizedPhotoSearchQuery.isNotEmpty()) {
                                    "No matching files"
                                } else when (homeMediaType) {
                                    MediaTypeFilter.ALL -> null
                                    MediaTypeFilter.IMAGES -> stringResource(R.string.media_type_no_images)
                                    MediaTypeFilter.VIDEOS -> stringResource(R.string.media_type_no_videos)
                                },
                            )
                            1 -> AlbumsScreen(
                                albums = searchedAlbums,
                                bottomPadding = contentBottomPadding,
                                gridState = if (normalizedAlbumSearchQuery.isEmpty()) {
                                    albumsGridState
                                } else {
                                    albumSearchGridState
                                },
                                onNavigateToFavourites = { navigationStack = navigationStack + Screen.Labels },
                                onNavigateToTrash = { navigationStack = navigationStack + Screen.Trash },
                                onNavigateToAlbum = { name -> navigationStack = navigationStack + Screen.Photo(name, homeMediaType) },
                                onExclude = { path -> photosViewModel.addExcludedFolder(path) },
                                onHide = { path -> photosViewModel.addHiddenFolder(path) },
                                onRename = photosViewModel::renameAlbum,
                                selectedPaths = selectedAlbumPaths,
                                selectionMode = albumSelectionMode,
                                onToggleAlbumSelection = toggleAlbumPath,
                                columns = gridColumns,
                                onColumnsChange = { photosViewModel.setGridColumns(it) },
                                showHeaderButtons = normalizedAlbumSearchQuery.isEmpty(),
                                emptyMessage = if (normalizedAlbumSearchQuery.isNotEmpty()) {
                                    "No matching albums"
                                } else when (homeMediaType) {
                                    MediaTypeFilter.ALL -> null
                                    MediaTypeFilter.IMAGES -> stringResource(R.string.media_type_no_image_albums)
                                    MediaTypeFilter.VIDEOS -> stringResource(R.string.media_type_no_video_albums)
                                },
                            )
                        }
                    }
                }
                Screen.Settings -> SettingsScreen(
                    onBack = navigateBack,
                    onNavigateToDisplaySettings = { navigationStack = navigationStack + Screen.DisplaySettings },
                    onNavigateToLicenses = { navigationStack = navigationStack + Screen.Licenses },
                    onNavigateToPerformanceSettings = { navigationStack = navigationStack + Screen.PerformanceSettings },
                    onNavigateToLibrarySettings = { navigationStack = navigationStack + Screen.LibrarySettings },
                )
                Screen.PerformanceSettings -> PerformanceSettingsScreen(
                    onBack = navigateBack
                )
                Screen.ThemeSettings -> ThemeSettingsScreen(
                    onBack = navigateBack,
                )
                Screen.DisplaySettings -> DisplaySettingsScreen(
                    onBack = navigateBack,
                    onNavigateToThemeSettings = { navigationStack = navigationStack + Screen.ThemeSettings },
                )
                Screen.LibrarySettings -> LibrarySettingsScreen(onBack = navigateBack,
                    onNavigateToExcludedFolders = { navigationStack = navigationStack + Screen.ExcludedFolders })
                Screen.Labels -> LabelsScreen(
                    columns = gridColumns,
                    onColumnsChange = photosViewModel::setGridColumns,
                    labels = labelSummaries,
                    onBack = navigateBack,
                    onLabelClick = { navigationStack = navigationStack + Screen.Label(it) },
                    onCreateLabel = { labelsViewModel.createLabel(it) },
                    onRenameLabel = { id, name -> labelsViewModel.renameLabel(id, name) },
                    onDeleteLabels = labelsViewModel::deleteLabels,
                    errorMessage = labelsError,
                    onClearError = labelsViewModel::clearError,
                    isSaving = labelsSaving,
                    sortMode = labelsViewModel.sortMode.collectAsState().value,
                    onSortModeChange = labelsViewModel::setSortMode,
                )
                is Screen.Label -> {
                    val labelId = baseScreen.labelId
                    val labelState = remember(labelId) { albumGridStates.getOrPut("label:$labelId") { LazyGridState() } }
                    LabelPhotosScreen(
                        label = labelEntries.find { it.id == labelId }, items = groupedLabelPhotos,
                        selectedIds = selectedIds, columns = gridColumns, gridState = labelState,
                        onBack = navigateBack,
                        onNavigateToViewer = { id -> navigationStack = navigationStack + Screen.Viewer(id, Screen.ViewerSource.Label, labelId = labelId) },
                        onSelectionChange = updateSelection, onToggleSelection = toggleSelection,
                        onColumnsChange = photosViewModel::setGridColumns,
                        quickSort = quickSortConfig, onToggleQuickSort = toggleQuickSort,
                        onSortPhotos = { showPhotoSortDialog = true },
                        onRemoveFromLabel = { ids ->
                            labelsViewModel.removeMediaFromLabel(labelId, ids) { selectedIds = emptySet() }
                        },
                        onEditLabel = { navigationStack = navigationStack.dropLast(1) + Screen.Labels },
                    )
                }
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
                    onEmptyBin = {
                        selectedIds = trash.map { it.contentId }.toSet()
                        isDeletePermanentlyConfirm = true
                        showDeleteConfirmDialog = true
                    },
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
                        quickSort = quickSortConfig,
                        onToggleQuickSort = toggleQuickSort,
                        albumName = albumName,
                        initialMediaType = baseScreen.initialMediaType,
                        isForeground = currentScreen == baseScreen,
                        onBack = navigateBack,
                        onNavigateToViewer = { id, type, query ->
                            ViewerLoadMetrics.entryRequested(
                                context.applicationContext,
                                id,
                                Screen.ViewerSource.Album.name,
                                allPhotos.size,
                                detail = "album=$albumName",
                            )
                            navigationStack = navigationStack + Screen.Viewer(
                                id, Screen.ViewerSource.Album, albumName,
                                searchQuery = query, mediaType = type,
                            )
                        },
                        selectedIds = selectedIds,
                        onSelectionChange = updateSelection,
                        onToggleSelection = toggleSelection,
                        onAlbumPhotosChanged = { photos ->
                            albumPhotoLists[albumName] = photos
                        },
                        selectionMode = selectionMode,
                        onDeduplicate = { photos ->
                            duplicateViewModel.start(
                                photos,
                                DeduplicationScope.WITHIN_ALBUMS,
                                context.getString(R.string.duplicate_scope_album, albumName),
                            )
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
                val livePhotosForViewer = remember(
                    viewer,
                    allPhotos,
                    browsingIndex,
                    photoSearchResult,
                    favourites,
                    trash,
                    vault,
                    cachedAlbumPhotos,
                    activeLabelPhotos,
                ) {
                    val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
                    var listSource = "DIRECT"
                    val result = when (viewer.source) {
                        Screen.ViewerSource.All -> browsingIndex.library[viewer.mediaType].media.photos
                        Screen.ViewerSource.Search -> if (photoSearchResult.mediaType == viewer.mediaType &&
                            photoSearchResult.photoQuery == viewer.searchQuery.orEmpty()) {
                            photoSearchResult.photos
                        } else {
                            browsingIndex.library[viewer.mediaType].media.photos.filter {
                                it.matchesFileSearch(viewer.searchQuery.orEmpty())
                            }
                        }
                        Screen.ViewerSource.Favourites -> favourites
                        Screen.ViewerSource.Label -> activeLabelPhotos
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
                                        val inAlbum = if (viewer.albumName?.contains('/') == true) parentPath == viewer.albumName else parentName == viewer.albumName
                                        inAlbum && viewer.mediaType.matches(it) && it.matchesAlbumSearch(viewer.searchQuery.orEmpty())
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
                // A Viewer session owns a stable sequence. Live MediaStore changes may
                // refresh an existing item's path/metadata, but must not reorder pages
                // underneath Pager or make the current numeric index point at another image.
                var photosForViewer by remember(viewer) {
                    mutableStateOf(livePhotosForViewer, referentialEqualityPolicy())
                }
                LaunchedEffect(viewer, livePhotosForViewer, quickReceipts) {
                    val current = photosForViewer
                    // A main-page session can contain over 100,000 entries. Receipt
                    // replay and metadata reconciliation must not delay the first image
                    // frame, even after the user has accumulated quick-sort history.
                    val reconciled = withContext(Dispatchers.Default) {
                        refreshViewerSessionPhotos(current, livePhotosForViewer, quickReceipts)
                    }
                    if (reconciled !== photosForViewer) {
                        photosForViewer = reconciled
                        ViewerLoadMetrics.event(
                            "VIEWER_SESSION_REFRESH",
                            "sessionCount=${reconciled.size} liveCount=${livePhotosForViewer.size}",
                        )
                    }
                }

                if (photosForViewer.isNotEmpty()) {
                    ViewerScreen(
                        onEditLabels = openLabelEditor,
                        labelsForMedia = { labelNamesByMedia[it].orEmpty() },
                        labelIdsForMedia = { labelIdsByMedia[it].orEmpty() },
                        onOpenLabel = { labelId ->
                            navigationStack = navigationStack.dropLast(1) + Screen.Label(labelId)
                        },
                        allowLabels = viewer.source !in setOf(Screen.ViewerSource.Trash, Screen.ViewerSource.Vault, Screen.ViewerSource.External),
                        reservedIds = quickQueue.reservedIds,
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
                        },
                        allowUnlockFromVault = viewer.source == Screen.ViewerSource.Vault,
                        onUnlockFromVault = { media ->
                            photosViewModel.restoreFromVault(media.contentId) { restored ->
                                if (restored) navigationStack = navigationStack.dropLast(1)
                            }
                        },
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
                    columns = gridColumns,
                    onColumnsChange = photosViewModel::setGridColumns,
                    onFinished = { summary ->
                        val action = if (summary.mode == TransferMode.MOVE) "Moved" else "Copied"
                        val message = buildString {
                            append("$action ${summary.succeeded} item")
                            if (summary.succeeded != 1) append('s')
                            if (summary.failed > 0) append("; ${summary.failed} failed")
                            if (summary.skipped > 0) append("; ${summary.skipped} skipped")
                        }
                        if (summary.failed == 0) scope.launch { snackbarHostState.showSnackbar(message) }
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
            if (currentScreen == Screen.Home && !quickSortEnabled && !albumSelectionMode) {
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

    if (editingLabelIds.isNotEmpty()) {
        LabelEditorSheet(
            labels = editorLabelEntries, labelSummaries = labelSummaries, memberships = labelMemberships, selectedIds = editingLabelIds, recentLabelIds = recentLabelIds,
            columns = gridColumns,
            onColumnsChange = photosViewModel::setGridColumns,
            onDismiss = { editingLabelIds = emptySet(); labelsViewModel.clearError() },
            onApply = { edits -> labelsViewModel.applyEdits(editingLabelIds, edits) { editingLabelIds = emptySet() } },
            onReorderLabels = labelsViewModel::reorderLabels,
            isSaving = labelsSaving, errorMessage = labelsError,
        )
    }
    if (showQuickTargets && quickSortEnabled && quickSortOwner == quickSortScope) {
        QuickSortTargetsSheet(
            albumUses = quickQueue.albumUses,
            availableTargets = transferTargets.filter { it.documentUri == null },
            selectedTargets = quickPreferences.targets,
            dockTop = quickPreferences.dockTop,
            onDockTopChange = photosViewModel::setQuickSortDockTop,
            onApply = { photosViewModel.setQuickSortTargets(it); showQuickTargets = false; quickTargetsPanelHeight = 0.dp },
            onDismiss = { showQuickTargets = false; quickTargetsPanelHeight = 0.dp },
            onPanelHeightChange = { quickTargetsPanelHeight = it },
            onTargetsChange = photosViewModel::setQuickSortTargets,
            onCreateAlbum = { parent, name, onResult -> photosViewModel.createTransferFolder(parent, name, onResult) },
        )
    }

    renamingAlbum?.let { album ->
        AlbumRenameDialog(
            album = album,
            onDismiss = { renamingAlbum = null },
            onRename = { path, name ->
                photosViewModel.renameAlbum(path, name).also { result ->
                    if (result.isSuccess) selectedAlbumPaths = emptySet()
                }
            },
        )
    }

    DuplicateReviewDialog(
        state = duplicateState,
        onDismiss = duplicateViewModel::dismiss,
        onToggle = duplicateViewModel::toggle,
        onConfirm = duplicateViewModel::confirm,
    )

    // Custom sort dialog overlays
        val photoCriteria = remember {
            listOf(
                SortCriterion("DATE", "Date", "Oldest first", "Newest first"),
                SortCriterion("NAME", "Name", "A to Z", "Z to A"),
                SortCriterion("SIZE", "Size", "Smallest first", "Largest first")
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
            AlbumSortDialog(
                visible = showAlbumSortDialog,
                onDismissRequest = { showAlbumSortDialog = false },
                title = "Sort Albums By",
                initialOrder = albumSortOrder,
                onConfirm = { order ->
                    photosViewModel.setAlbumSortOrder(order)
                    showAlbumSortDialog = false
                }
            )
        }

        DeleteConfirmDialog(
            visible = showDeleteConfirmDialog,
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = if (isDeletePermanentlyConfirm) "Delete permanently?" else "Move to Recycle Bin?",
            message = if (isDeletePermanentlyConfirm) {
                "This action cannot be undone."
            } else {
                context.getString(R.string.dialog_restore)
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

    // Render alerts above the album chooser when the activity is visible.
    val quickConflict = quickQueue.needsConflictDecision
    if (lifecycleState == Lifecycle.State.RESUMED && quickConflict != null) {
        QuickSortConflictDialog(quickConflict,
            onResolve = { quickSortViewModel.resolveConflict(quickConflict.id, it) },
            onCancel = { quickSortViewModel.cancel(quickConflict.id); quickSortViewModel.resume() })
    } else if (lifecycleState == Lifecycle.State.RESUMED) {
        quickWarning?.let { message ->
            QuickSortWarningDialog(message) { quickWarning = null }
        }
        if (quickWarning == null) quickFailure?.let { failure ->
            QuickSortFailureDialog(failure) { quickSortViewModel.dismissFailure(failure) }
        }
    }
}

private data class NavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)
