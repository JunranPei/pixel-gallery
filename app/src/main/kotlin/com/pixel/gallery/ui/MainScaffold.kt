package com.pixel.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.model.chronologicalTimestamp
import com.pixel.gallery.ui.viewmodel.MediaBrowseIndex
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.pixel.gallery.ui.gallery.matchesAlbumSearch
import com.pixel.gallery.model.Album
import com.pixel.gallery.model.labelGroupId
import com.pixel.gallery.model.canGroupAlbumSelection
import com.pixel.gallery.model.CoverTarget
import com.pixel.gallery.model.albumCoverKey
import com.pixel.gallery.model.labelCoverKey
import com.pixel.gallery.model.groupCoverKey
import com.pixel.gallery.model.applyAlbumCovers
import com.pixel.gallery.model.resolveCollectionCover
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
import com.pixel.gallery.ui.viewer.ViewerTransitionRegistry
import com.pixel.gallery.ui.viewer.LocalViewerTransitions
import com.pixel.gallery.ui.viewer.LocalViewerGridScope
import com.pixel.gallery.ui.settings.ExcludedFoldersScreen
import com.pixel.gallery.ui.settings.LicensesScreen
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.components.DeleteConfirmDialog
import com.pixel.gallery.ui.components.GalleryNavigationState
import com.pixel.gallery.ui.components.GalleryNavigationTab
import com.pixel.gallery.ui.components.RevealGalleryNavigation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.pixel.gallery.ui.components.GalleryMessageProvider
import com.pixel.gallery.ui.components.GalleryMessageHost
import com.pixel.gallery.ui.components.LocalGalleryMessages
import com.pixel.gallery.ui.components.HomeOverflowMenu
import com.pixel.gallery.ui.components.galleryContentEdge
import com.pixel.gallery.ui.settings.PerformanceSettingsScreen
import com.pixel.gallery.ui.settings.DisplaySettingsScreen
import com.pixel.gallery.ui.settings.LibrarySettingsScreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.RestoreFromTrash
import kotlinx.coroutines.flow.*
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.filled.CheckCircle
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import com.pixel.gallery.ui.viewmodel.PhotoSortOrder
import com.pixel.gallery.ui.viewmodel.TrashSortOrder
import com.pixel.gallery.model.*
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
import com.pixel.gallery.ui.components.LocalGallerySettingsAction
import com.pixel.gallery.model.isInsideMediaFolder
import com.pixel.gallery.ui.components.GalleryOverflowButton
import com.pixel.gallery.ui.components.GalleryMenuRow
import com.pixel.gallery.ui.components.shareMedia
import com.pixel.gallery.ui.components.MediaCollectionSelectionTopBar
import com.pixel.gallery.ui.components.MediaCollectionTopBarAction
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LabelOff
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.model.DeduplicationScope
import com.pixel.gallery.ui.deduplication.DuplicateReviewDialog
import com.pixel.gallery.ui.deduplication.DuplicateViewModel

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.pixel.gallery.ui.components.GalleryMotion
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
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
    val sourceGeneration: Int = 0,
)

private data class HomeAlbumSearchResult(
    val mediaType: MediaTypeFilter,
    val albumQuery: String = "",
    val albums: List<Album> = emptyList(),
    val sourceGeneration: Int = 0,
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

private fun mediaParentPath(path: String): String {
    val normalizedPath = path.replace('\\', '/')
    val separator = normalizedPath.lastIndexOf('/')
    return when {
        separator == 0 -> "/"
        separator > 0 -> normalizedPath.substring(0, separator)
        else -> ""
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
        val hiddenAlbum: Boolean = false,
    ) : Screen()
    @Parcelize object ExcludedFolders : Screen()
    @Parcelize object Licenses : Screen()
    @Parcelize data class Photo(
        val albumName: String,
        val initialMediaType: MediaTypeFilter = MediaTypeFilter.ALL,
        val hiddenAlbum: Boolean = false,
    ) : Screen()
    @Parcelize object PerformanceSettings : Screen()
    @Parcelize object DisplaySettings : Screen()
    @Parcelize object LibrarySettings : Screen()
    @Parcelize data class TransferDestination(
        val entryIds: LongArray,
        val origin: TransferOrigin
    ) : Screen()

    enum class ViewerSource { All, Search, Favourites, Label, Trash, Album, Vault, External }
    enum class TransferOrigin { Grid, Viewer }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MainScaffold(
    initialScreen: Screen = Screen.Home,
    initialHomeTab: Int = -1,
    photosViewModel: PhotosViewModel = hiltViewModel()
) {
    val transitions = remember { ViewerTransitionRegistry() }
    CompositionLocalProvider(LocalViewerTransitions provides transitions) {
        GalleryMessageProvider {
            MainScaffoldContent(initialScreen, initialHomeTab, photosViewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MainScaffoldContent(
    initialScreen: Screen,
    initialHomeTab: Int,
    photosViewModel: PhotosViewModel,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coversViewModel: com.pixel.gallery.ui.viewmodel.CollectionCoversViewModel = hiltViewModel()
    val collectionCovers by coversViewModel.covers.collectAsState()
    val coverSaving by coversViewModel.saving.collectAsState()
    val coverError by coversViewModel.error.collectAsState()
    var coverTarget by remember { mutableStateOf<CoverTarget?>(null) }
    var selectedCollection by remember { mutableStateOf<CoverTarget?>(null) }
    var pickingCover by remember { mutableStateOf(false) }
    var collectionEditSaving by remember { mutableStateOf(false) }
    var collectionEditError by remember { mutableStateOf(false) }
    var editingGroupName by remember { mutableStateOf<com.pixel.gallery.model.AlbumGroup?>(null) }
    var dissolvingCollection by remember { mutableStateOf<com.pixel.gallery.model.AlbumGroup?>(null) }
    var editingLabelName by remember { mutableStateOf<CoverTarget?>(null) }
    val albumGroupsViewModel: com.pixel.gallery.ui.viewmodel.AlbumGroupsViewModel = hiltViewModel()
    val groupingEnabled by albumGroupsViewModel.enabled.collectAsState()
    val albumGroupError by albumGroupsViewModel.error.collectAsState()
    val albumGroupSaving by albumGroupsViewModel.isSaving.collectAsState()
    val albumGroupPickerColumns by remember(photosViewModel) { photosViewModel.pageGridColumns("groups:picker") }.collectAsState(initial = 4)
    val labelGroupPickerColumns by remember(photosViewModel) { photosViewModel.pageGridColumns("label-groups:picker") }.collectAsState(initial = 4)
    var showAlbumGroupPicker by remember { mutableStateOf(false) }
    var sheetExpandRequest by remember { mutableIntStateOf(0) }
    var labelGroupSelection by remember { mutableStateOf<Set<Long>?>(null) }
    var clearLabelGroupSelection by remember { mutableStateOf<(() -> Unit)?>(null) }
    val labelsViewModel: LabelsViewModel = hiltViewModel()
    val quickSortViewModel: QuickSortViewModel = hiltViewModel()
    val labelEntries by labelsViewModel.labelEntries.collectAsState()
    val loadedLabelSummaries by labelsViewModel.loadedLabels.collectAsState()
    val labelSummaries = loadedLabelSummaries.orEmpty()
    val labelMemberships by labelsViewModel.memberships.collectAsState()
    val labelIdsByMedia by labelsViewModel.labelIdsByMedia.collectAsState()
    val recentLabelIds by labelsViewModel.recentLabelIds.collectAsState()
    val openLabelCover: (Long) -> Unit = { id ->
        labelSummaries.firstOrNull { it.label.id == id }?.let { summary ->
            coversViewModel.clearError()
            coverTarget = CoverTarget(labelCoverKey(id), if (summary.label.isSystem) context.getString(R.string.labels_favourites) else summary.label.name)
        }
    }
    val editorLabelEntries = remember(labelEntries, labelSummaries) {
        val byId = labelEntries.associateBy { it.id }
        val orderedIds = labelSummaries.map { it.label.id }
        orderedIds.mapNotNull(byId::get) + labelEntries.filterNot { it.id in orderedIds }
    }
    val labelsSaving by labelsViewModel.isSaving.collectAsState()
    val labelsError by labelsViewModel.errorMessage.collectAsState()
    val quickQueue by quickSortViewModel.state.collectAsState()
    val quickReceipts = quickQueue.moveReceipts
    val quickPreferences by photosViewModel.quickSortPreferences.collectAsState()
    val transferTargets by photosViewModel.transferDestinations.collectAsState()
    var editingLabelIds by rememberSaveable(stateSaver = listSaver(
        save = { ids: Set<Long> -> ids.toList() }, restore = { it.toSet() },
    )) { mutableStateOf<Set<Long>>(emptySet()) }
    var quickSortEnabled by rememberSaveable { mutableStateOf(false) }
    var quickSortOwner by rememberSaveable { mutableStateOf<Screen?>(null) }
    val quickSortSessionId = remember(quickSortEnabled) {
        if (quickSortEnabled) java.util.UUID.randomUUID().toString() else null
    }
    val batchVisibility = remember(quickSortSessionId) { com.pixel.gallery.ui.quicksort.QuickSortBatchVisibility() }
    val completedBatchPaths = remember(quickQueue.jobs, quickSortSessionId) {
        batchVisibility.update(quickQueue.jobs, quickSortSessionId)
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
    // Detail pages use repository-backed snapshots so Activity recreation does not
    // expose the grid's lifecycle-cleared empty value for one frame.
    val viewerPhotos by photosViewModel.viewerPhotos.collectAsState()
    val viewerFavourites by photosViewModel.viewerFavourites.collectAsState()
    val viewerTrash by photosViewModel.viewerTrash.collectAsState()
    val viewerVault by photosViewModel.viewerVault.collectAsState()
    val hiddenPhotos by photosViewModel.hiddenPhotos.collectAsState()
    val hiddenAlbums by photosViewModel.hiddenAlbums.collectAsState()
    val browsingIndex by photosViewModel.browsingIndex.collectAsState()
    val groupedPhotos by photosViewModel.groupedPhotos.collectAsState()
    val favourites by photosViewModel.favourites.collectAsState()
    val groupedFavourites by photosViewModel.groupedFavourites.collectAsState()
    val trash by photosViewModel.trashedMedia.collectAsState()
    val groupedTrash by photosViewModel.groupedTrashedMedia.collectAsState()
    val trashSortOrder by photosViewModel.trashSortOrder.collectAsState()
    val vault by photosViewModel.vaultEntries.collectAsState()
    val groupedVault by photosViewModel.groupedVaultEntries.collectAsState()
    val albums by photosViewModel.albums.collectAsState()
    val gridColumns by photosViewModel.gridColumns.collectAsState()
    val albumGridColumns by photosViewModel.albumGridColumns.collectAsState()
    val labelsGridColumns by remember(photosViewModel) { photosViewModel.pageGridColumns("labels") }.collectAsState(initial = 4)
    val hiddenAlbumsColumns by remember(photosViewModel) { photosViewModel.pageGridColumns("hidden-albums") }.collectAsState(initial = 4)
    val externalMedia by photosViewModel.externalMedia.collectAsState()

    val photoSortOrder by photosViewModel.photoSortOrder.collectAsState()
    val albumSortOrder by photosViewModel.albumSortOrder.collectAsState()


    var showPhotoSortDialog by remember { mutableStateOf(false) }
    var showTrashSortDialog by remember { mutableStateOf(false) }
    var showAlbumSortDialog by remember { mutableStateOf(false) }
    var groupSortScope by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isDeletePermanentlyConfirm by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var isVaultRestoreRunning by remember { mutableStateOf(false) }
    var isVaultDeleteRunning by remember { mutableStateOf(false) }

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedAlbumPaths by remember { mutableStateOf(emptySet<String>()) }
    var selectedLabelAlbumIds by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(labelSummaries) {
        selectedLabelAlbumIds = selectedLabelAlbumIds.intersect(labelSummaries.filterNot { it.label.isSystem }.map { it.label.id }.toSet())
    }
    var showLabelEditor by rememberSaveable { mutableStateOf(false) }
    var labelEditorFromGrid by rememberSaveable { mutableStateOf(false) }
    var gridTransferSession by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionPanelHeight by remember { mutableStateOf(0.dp) }
    val moveSheetColumns by photosViewModel.moveSheetColumns.collectAsState()
    val labelSheetColumns by photosViewModel.labelSheetColumns.collectAsState()
    val quickSortSheetColumns by photosViewModel.quickSortSheetColumns.collectAsState()
    var ordinaryTransferIds by remember { mutableStateOf(emptySet<Long>()) }
    val moveVisibility = remember(gridTransferSession) { com.pixel.gallery.ui.quicksort.QuickSortBatchVisibility() }
    val movedBatchPaths = remember(quickQueue.jobs, gridTransferSession) { moveVisibility.update(quickQueue.jobs, gridTransferSession) }
    DisposableEffect(gridTransferSession) {
        onDispose { gridTransferSession?.let(quickSortViewModel::finishSession) }
    }
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

    // Selection belongs to the base route, while viewer and destination pages
    // temporarily cover that route. Commit both changes before composing a frame.
    fun navigateToStack(nextStack: List<Screen>) {
        require(nextStack.isNotEmpty()) { "The gallery navigation stack must retain a base route" }
        val previousBaseIndex = navigationStack.indexOfLast {
            it !is Screen.Viewer && it !is Screen.TransferDestination
        }
        val nextBaseIndex = nextStack.indexOfLast {
            it !is Screen.Viewer && it !is Screen.TransferDestination
        }
        if (previousBaseIndex != nextBaseIndex ||
            navigationStack.getOrNull(previousBaseIndex) != nextStack.getOrNull(nextBaseIndex)
        ) {
            selectedIds = emptySet()
            showLabelEditor = false
            labelEditorFromGrid = false
            gridTransferSession = null
            selectedAlbumPaths = emptySet()
            selectedLabelAlbumIds = emptySet()
            showSelectionMenu = false
        }
        navigationStack = nextStack
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
            navigateToStack(listOf(Screen.Home, Screen.Viewer(initialId = -1L, source = Screen.ViewerSource.External, externalUri = media.uri, externalMimeType = media.mimeType)))
            photosViewModel.clearExternalMediaUri()
        }
    }

    val currentScreen = navigationStack.last()
    // Other tasks still share persisted groups. A covered/stopped route keeps
    // its presentation instead of rebuilding its grid for every remote toggle.
    val groupPresentationActive = lifecycleState.isAtLeast(Lifecycle.State.STARTED) &&
        (currentScreen == Screen.Home || showAlbumGroupPicker || coverTarget != null ||
            editingGroupName != null || dissolvingCollection != null)
    val groupingLoaded by albumGroupsViewModel.groupingLoaded.collectAsState()
    val albumGrouping by albumGroupsViewModel.grouping.collectAsRetainedState(groupPresentationActive, groupingLoaded)
    val orderingRows = remember(albums, labelSummaries, albumGrouping, albumSortOrder) {
        orderedAlbumRows(groupedAlbumRows(albums, albumGrouping.copy(collapsedIds = emptySet()), labelSummaries), albumGrouping, albumSortOrder)
    }
    // A destination picker overlays its source viewer instead of disposing it.
    // Retaining this same composition also preserves the current page and zoom.
    val visibleViewer = navigationStack.lastOrNull { it !is Screen.TransferDestination } as? Screen.Viewer
    val retainedViewer = navigationStack.lastOrNull { it is Screen.Viewer } as? Screen.Viewer
    val restoredDrawGate = com.pixel.gallery.ui.viewer.LocalRestoredWindowDrawGate.current
    androidx.compose.runtime.SideEffect {
        if (visibleViewer != null || currentScreen != Screen.Home) {
            val contentKey = visibleViewer?.let { "viewer:${it.initialId}" } ?: "route"
            restoredDrawGate?.expect(contentKey)
            if (visibleViewer == null) restoredDrawGate?.resolve(contentKey, "non-image-route")
        }
    }
    // A viewer belongs to its underlying grid; another album or Home starts a different scope.
    val quickSortScope = navigationStack.lastOrNull { it !is Screen.Viewer }
    LaunchedEffect(navigationStack, currentScreen, visibleViewer?.initialId, retainedViewer?.initialId) {
        ViewerLoadMetrics.event(
            "NAV_STATE",
            "stack=${navigationStack.joinToString(">") { it::class.simpleName ?: "unknown" }} " +
                "current=${currentScreen::class.simpleName ?: "unknown"} " +
                "visibleViewer=${visibleViewer?.initialId ?: "none"} " +
                "retainedViewer=${retainedViewer?.initialId ?: "none"}",
        )
    }
    val favouriteLabelName = stringResource(R.string.labels_favourites)
    val labelNamesById = remember(labelEntries, favouriteLabelName) {
        labelEntries.associate { it.id to if (it.isSystem) favouriteLabelName else it.name }
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
    val hiddenAlbumsGridState = rememberLazyGridState()
    val labelsGridState = rememberLazyGridState()
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
    val homeVisiblePage by remember { derivedStateOf { homePagerState.settledPage } }
    var homeHeaderTransition by remember { mutableStateOf(HomeHeaderTransitionState()) }
    val isHomeSearchMode = homeHeaderTransition.isSearchMode
    val scope = rememberCoroutineScope()
    val navigationReveal = remember { GalleryNavigationState(scope) }
    val navigationExpanded by remember { derivedStateOf { navigationReveal.expanded } }
    val messages = LocalGalleryMessages.current
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var homeMediaType by rememberSaveable { mutableStateOf(MediaTypeFilter.ALL) }
    var hiddenMediaType by rememberSaveable { mutableStateOf(MediaTypeFilter.ALL) }
    val hiddenTypePhotos = remember(hiddenPhotos, hiddenMediaType) {
        hiddenPhotos.filter(hiddenMediaType::matches)
    }
    val hiddenTypeAlbums = remember(hiddenAlbums, hiddenTypePhotos, hiddenMediaType, albumSortOrder) {
        if (hiddenMediaType == MediaTypeFilter.ALL) hiddenAlbums else {
            val filtered = hiddenAlbums.mapNotNull { album ->
                val entries = hiddenTypePhotos.filter { it.path.isInsideMediaFolder(album.path) }
                if (entries.isEmpty()) null else album.copy(
                    coverUri = entries.first().uri,
                    itemCount = entries.size,
                    lastModified = entries.maxOf { it.chronologicalTimestamp() },
                )
            }
            com.pixel.gallery.model.AlbumSorter.shared.sortAlbums(filtered, albumSortOrder)
        }
    }
    val requestedCoverGroups = remember(coverTarget?.key, selectedCollection?.key) {
        buildSet {
            listOfNotNull(coverTarget?.key, selectedCollection?.key).forEach {
                if (it.startsWith("group:")) add(it.removePrefix("group:"))
            }
        }
    }
    val needsCoverMedia = coverTarget != null || selectedCollection != null || requestedCoverGroups.isNotEmpty()
    val coverVisibleMedia = remember(allPhotos, vault, needsCoverMedia) {
        if (!needsCoverMedia) emptyList() else {
            val locked = vault.mapTo(hashSetOf()) { it.contentId }
            allPhotos.filter { !it.isTrashed && it.contentId !in locked }
        }
    }
    val groupCoverCandidates = remember(coverVisibleMedia, albums, albumGrouping.groups, labelMemberships, requestedCoverGroups) {
        com.pixel.gallery.model.groupCoverCandidates(
            coverVisibleMedia, albums, albumGrouping, labelMemberships, requestedCoverGroups,
        )
    }
    val resolvedGroupCovers = remember(allPhotos, albums, albumGrouping.groups, labelMemberships, collectionCovers, vault) {
        com.pixel.gallery.model.resolveGroupCoverUris(
            allPhotos, albums, albumGrouping, labelMemberships, collectionCovers,
            vault.mapTo(hashSetOf()) { it.contentId },
        )
    }
    val activeCoverKey = coverTarget?.key.orEmpty()
    val inlineCoverGroupId = if (pickingCover) activeCoverKey.takeIf { it.startsWith("group:") }?.removePrefix("group:") else null
    var collectionNameDraft by remember(activeCoverKey) { mutableStateOf(coverTarget?.title.orEmpty()) }
    var coverPickerAlbum by remember(activeCoverKey) { mutableStateOf<String?>(null) }
    var coverPickerLabel by remember(activeCoverKey) { mutableStateOf<Long?>(null) }
    var coverDraft by remember(activeCoverKey) { mutableStateOf(collectionCovers[activeCoverKey]) }
    LaunchedEffect(activeCoverKey) { pickingCover = false; collectionEditError = false }
    val coverDensityKey = "cover-picker:$activeCoverKey" + (coverPickerLabel?.let { ":label:$it" } ?: "")
    val coverColumns by remember(coverDensityKey, photosViewModel) {
        photosViewModel.pageGridColumns(coverDensityKey)
    }.collectAsState(initial = 4)
    val coverCandidates = remember(coverTarget, coverVisibleMedia, hiddenPhotos, groupCoverCandidates, labelMemberships) {
        val target = coverTarget
        when {
            target == null -> emptyList()
            target.key.startsWith("group:") -> groupCoverCandidates[target.key.removePrefix("group:")].orEmpty()
            target.key.startsWith("label:") -> {
                val id = target.key.removePrefix("label:").toLongOrNull()
                val ids = labelMemberships.filter { it.labelId == id }.mapTo(hashSetOf()) { it.contentId }
                coverVisibleMedia.filter { it.contentId in ids }
            }
            else -> (if (target.hidden) hiddenPhotos else coverVisibleMedia).filter {
                !it.isTrashed && it.path.substringBeforeLast('/') == target.key.removePrefix("album:")
            }
        }.distinctBy { it.uri }.sortedWith(compareByDescending<MediaEntry> { it.bestTimestamp }.thenByDescending { it.contentId })
    }
    val autoCover = remember(coverTarget, coverCandidates, browsingIndex, albums, labelSummaries, albumGrouping) {
        val key = coverTarget?.key.orEmpty()
        val uri = when {
            key.startsWith("album:") && coverTarget?.hidden != true -> browsingIndex.library[MediaTypeFilter.ALL]
                .albums(albumSortOrder).firstOrNull { it.path == key.removePrefix("album:") }?.coverUri
            key == groupCoverKey(com.pixel.gallery.model.LABELS_ALBUM_GROUP) -> labelSummaries.firstNotNullOfOrNull { it.cover?.uri }
            key.startsWith("group:") -> {
                val paths = albumGrouping.groups.firstOrNull { it.id == key.removePrefix("group:") }?.paths.orEmpty()
                albums.firstOrNull { it.path in paths && it.coverUri.isNotBlank() }?.coverUri
            }
            else -> null
        }
        coverCandidates.firstOrNull { it.uri == uri } ?: coverCandidates.firstOrNull()
    }
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
            HomePhotoSearchResult(type, query, result.photos, result.gridItems, result.positionLabels,
                System.identityHashCode(browsingIndex))
        }
    }
    val albumSearchResult by produceState(
        HomeAlbumSearchResult(homeMediaType), browsingIndex, homeMediaType, albumSortOrder, normalizedAlbumSearchQuery, collectionCovers,
    ) {
        val type = homeMediaType
        val query = normalizedAlbumSearchQuery
        if (query.isNotEmpty()) delay(HomeSearchDebounceMillis)
        value = withContext(Dispatchers.Default) {
            val library = browsingIndex.library[type]
            val albums = applyAlbumCovers(library.albums(albumSortOrder), library.media.photos, collectionCovers)
            HomeAlbumSearchResult(type, query, if (query.isEmpty()) albums else albums.filter {
                it.name.contains(query, ignoreCase = true)
            }, System.identityHashCode(browsingIndex))
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

    val restoreView = androidx.compose.ui.platform.LocalView.current
    val restoredHomeGrids = remember(restoredDrawGate, restoreView) {
        restoredDrawGate?.let { gate -> List(2) { com.pixel.gallery.ui.viewer.RestoredGridContent(gate, restoreView) } }
    }
    SideEffect {
        if (visibleViewer == null && currentScreen == Screen.Home && restoredDrawGate?.isWaiting == true && restoredHomeGrids != null) {
            val loaded = browsingIndex !== com.pixel.gallery.ui.viewmodel.MediaBrowseIndex.Unloaded
            val generation = System.identityHashCode(browsingIndex)
            restoredHomeGrids[0].expectedData = searchedPhotoGridItems
            restoredHomeGrids[1].expectedData = searchedAlbums
            restoredHomeGrids[1].expectedLabels = labelSummaries
            restoredHomeGrids[1].expectedGrouping = albumGrouping
            restoredHomeGrids[0].dataReady = loaded && (normalizedPhotoSearchQuery.isEmpty() ||
                (photoSearchResult.sourceGeneration == generation && photoSearchResult.photoQuery == normalizedPhotoSearchQuery &&
                    photoSearchResult.mediaType == homeMediaType))
            restoredHomeGrids[1].dataReady = loaded && groupingLoaded && loadedLabelSummaries != null &&
                albumSearchResult.sourceGeneration == generation && albumSearchResult.albumQuery == normalizedAlbumSearchQuery &&
                albumSearchResult.mediaType == homeMediaType
            val grid = restoredHomeGrids[homeVisiblePage]
            restoredDrawGate?.expectGrid("home:$homeVisiblePage", grid::ready)
        }
    }

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

    val selectedLabelMediaIds = remember(labelMemberships, selectedLabelAlbumIds) {
        labelMemberships.filter { it.labelId in selectedLabelAlbumIds }.mapTo(hashSetOf()) { it.contentId }
    }
    val selectedAlbumEntries = remember(allPhotos, hiddenPhotos, currentScreen, selectedAlbumPaths, selectedLabelMediaIds) {
        if (currentScreen == Screen.HiddenAlbums) {
            hiddenPhotos.filter { entry -> selectedAlbumPaths.any { entry.path.isInsideMediaFolder(it) } }
        } else allPhotos.filter { mediaParentPath(it.path) in selectedAlbumPaths || it.contentId in selectedLabelMediaIds }
    }
    var renamingAlbum by remember { mutableStateOf<Album?>(null) }
    LaunchedEffect(selectedCollection?.key) {
        val scope = groupSortScope
        if (scope != null && selectedCollection?.key != "group:$scope") {
            showAlbumSortDialog = false
            groupSortScope = null
        }
    }
    val albumSelectionMode = (currentScreen == Screen.Home || currentScreen == Screen.HiddenAlbums) && (selectedAlbumPaths.isNotEmpty() || selectedLabelAlbumIds.isNotEmpty())
    val toggleAlbumPath: (String) -> Unit = { path ->
        selectedCollection = null
        selectedAlbumPaths = if (path in selectedAlbumPaths) selectedAlbumPaths - path else selectedAlbumPaths + path
    }
    val toggleLabelAlbum: (Long) -> Unit = { id ->
        if (labelSummaries.any { it.label.id == id && !it.label.isSystem }) {
            selectedCollection = null
            selectedLabelAlbumIds = if (id in selectedLabelAlbumIds) selectedLabelAlbumIds - id else selectedLabelAlbumIds + id
        }
    }
    val hasOpenSelectionSheet = (showLabelEditor && labelEditorFromGrid) ||
        gridTransferSession != null || currentScreen is Screen.TransferDestination ||
        (showQuickTargets && quickSortEnabled && quickSortOwner == quickSortScope)
    val selectionMode = selectedIds.isNotEmpty() || hasOpenSelectionSheet
    LaunchedEffect(quickQueue.reservedIds) { selectedIds = selectedIds - quickQueue.reservedIds }
    val openLabelEditor: (Set<Long>) -> Unit = { ids ->
        labelsViewModel.clearError()
        editingLabelIds = ids - quickQueue.reservedIds
        labelEditorFromGrid = currentScreen !is Screen.Viewer
        gridTransferSession = null
        quickSortEnabled = false
        showQuickTargets = false
        selectionPanelHeight = 0.dp
        sheetExpandRequest++; showLabelEditor = true
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
            showLabelEditor = false
            gridTransferSession = null
            quickTargetsPanelHeight = 0.dp
            quickSortOwner = quickSortScope
            photosViewModel.setQuickSortDockTop(false)
            sheetExpandRequest++; showQuickTargets = true
        } else {
            showQuickTargets = false
        }
    }
    // Keep user order, while refreshing covers from the current album inventory.
    val quickSortTargets = remember(quickPreferences.targets, transferTargets) {
        val currentAlbums = transferTargets.associateBy { it.stableKey }
        quickPreferences.targets.map { currentAlbums[it.stableKey] ?: it }
    }
    val quickSortConfig = if (quickSortEnabled && quickSortOwner == quickSortScope) QuickSortUiConfig(
        sessionKey = quickSortSessionId,
        completedBatchPaths = completedBatchPaths,
        relocatedEntries = batchVisibility.relocatedEntries,
        albumRows = quickPreferences.albumRows,
        targets = quickSortTargets,
        dockTop = false,
        onDockTopChange = {},
        onReorderTargets = photosViewModel::setQuickSortTargets,
        onManageTargets = {
            if (!showQuickTargets) quickTargetsPanelHeight = 0.dp
            sheetExpandRequest++; showQuickTargets = true
        },
        onMove = { ids, destination ->
            quickSortViewModel.enqueue((allPhotos + hiddenPhotos).filter { it.contentId in ids }, destination, onQuickEnqueued, quickSortSessionId)
        },
        // Start at the compact header's real height, without an off-screen
        // placeholder or an automatic expansion. Only a user's drag changes it.
        dockBottomInset = if (showQuickTargets) {
            if (quickTargetsPanelHeight > 0.dp) quickTargetsPanelHeight
            else 68.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        } else 0.dp,
        reservedIds = quickQueue.reservedIds,
        heldItems = quickQueue.heldItems,
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
            quickSortEnabled = false
            showQuickTargets = false
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
        selectedIds = if (selectedIds.contains(id)) {
            selectedIds - id
        } else {
            selectedIds + (setOf(id) - quickQueue.reservedIds)
        }
    }

    val updateSelection = { ids: Set<Long> ->
        selectedIds = ids - quickQueue.reservedIds
    }

    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(isHomeSearchMode) {
        if (isHomeSearchMode) showMenu = false
    }
    LaunchedEffect(showMenu) {
        if (showMenu) navigationReveal.settle(0)
    }

    // System back button handling
    BackHandler(
        enabled = navigationStack.size > 1 ||
            quickSortEnabled ||
            albumSelectionMode ||
            selectionMode ||
            (currentScreen == Screen.Home && (isHomeSearchMode || navigationExpanded))
    ) {
        if (currentScreen is Screen.Viewer || currentScreen is Screen.TransferDestination) {
            navigateToStack(navigationStack.dropLast(1))
        } else if (albumSelectionMode) {
            selectedAlbumPaths = emptySet()
            selectedLabelAlbumIds = emptySet()
        } else if (selectionMode) {
            selectedIds = emptySet()
        } else if (quickSortEnabled && currentScreen !is Screen.Viewer) {
            quickSortEnabled = false
            showQuickTargets = false
        } else if (currentScreen == Screen.Home && isHomeSearchMode) {
            homeHeaderTransition = HomeHeaderTransitionState()
        } else if (currentScreen == Screen.Home && navigationExpanded) {
            navigationReveal.settle(0)
        } else if (navigationStack.size > 1) {
            // A back callback can still be queued while the previous route is disappearing.
            if (currentScreen == Screen.LockedFolder || currentScreen == Screen.HiddenAlbums) navigationReveal.settle(0)
            navigateToStack(navigationStack.dropLast(1))
        }
    }


    // Route-owned background work follows navigation; selection is updated
    // synchronously by navigateToStack before the new route is rendered.
    LaunchedEffect(currentScreen) {
        if (ViewerLoadMetrics.currentEntryId() != 0L) {
            ViewerLoadMetrics.event(
                "NAVIGATION_SCREEN",
                "screen=$currentScreen baseGridRetained=${currentScreen is Screen.Viewer}",
            )
        }
        if (quickSortOwner != quickSortScope ||
            (currentScreen !is Screen.Viewer && currentScreen !is Screen.Photo && currentScreen !is Screen.Label && currentScreen != Screen.Home)) {
            quickSortEnabled = false
            quickSortOwner = null
            showQuickTargets = false
        }
    }

    // Scroll behavior: bar exits when scrolling down, returns when scrolling up
    val scrollBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(
        exitDirection = FloatingToolbarExitDirection.Bottom
    )
    // The toolbar is a single mounted instance in UnifiedGalleryContainer, so every
    // route must drive the same top-bar scroll state. The floating bottom navigation
    // keeps its separate behavior because it is a different surface.
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    LaunchedEffect(isHomeSearchMode) {
        if (isHomeSearchMode) {
            topBarScrollBehavior.state.heightOffset = 0f
            topBarScrollBehavior.state.contentOffset = 0f
        }
    }
    // Window Insets
    // The full-screen viewer overlays this content. Keep the underlying grid's
    // geometry stable while Android animates the system bars on task return.
    val navigationInsets = if (currentScreen is Screen.Viewer) {
        WindowInsets.navigationBarsIgnoringVisibility
    } else WindowInsets.navigationBars
    val navBarPadding = navigationInsets.asPaddingValues().calculateBottomPadding()
    val contentPaddingScreen = navigationStack.lastOrNull {
        it !is Screen.Viewer && it !is Screen.TransferDestination
    } ?: currentScreen
    val contentBottomPadding = if (quickSortEnabled) {
        navBarPadding
    } else if (contentPaddingScreen == Screen.Home || contentPaddingScreen == Screen.HiddenAlbums || contentPaddingScreen == Screen.LockedFolder) {
        FloatingBarHeight + 16.dp + navBarPadding
    } else {
        navBarPadding + 16.dp
    }

    val colorScheme = MaterialTheme.colorScheme
    val appShellColor = colorScheme.surface
    val headerColor = colorScheme.surfaceContainerLow
    val navigateBack: () -> Unit = {
        if (currentScreen == Screen.LockedFolder || currentScreen == Screen.HiddenAlbums) navigationReveal.settle(0)
        if (navigationStack.size > 1) {
            navigateToStack(navigationStack.dropLast(1))
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    val selectedEntries = remember(selectedIds, allPhotos, hiddenPhotos, trash, vault) {
        if (selectedIds.isEmpty()) emptyList() else
            (allPhotos + hiddenPhotos + trash + vault).filter { selectedIds.contains(it.contentId) }
    }
    val selectedTrashEntries = remember(selectedIds, trash) { trash.filter { it.contentId in selectedIds } }
    val selectedVaultEntries = remember(selectedIds, vault) { vault.filter { it.contentId in selectedIds } }

    val homeTopBar: @Composable () -> GalleryHeaderSpec = {
        val searchingAlbums = homeVisiblePage == 1
        val query = if (searchingAlbums) albumSearchQuery else photoSearchQuery
        GalleryHeader(
            centerTitle = true,
            search = rememberGallerySearch(
                isSearchMode = isHomeSearchMode,
                onSearchModeChange = { homeHeaderTransition = HomeHeaderTransitionState(isSearchMode = it) },
                enabled = selectedIds.isEmpty(),
            ) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
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
                }
            },
            title = {
                Text("Pixel Gallery", style = EmphasizedTypography.HeadlineMedium)
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = headerColor,
                scrolledContainerColor = headerColor,
                titleContentColor = colorScheme.onSurface,
            ),
            actions = {
                HomeOverflowMenu(
                    expanded = showMenu,
                    onExpandedChange = { showMenu = it },
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
                    showQuickSort = homeVisiblePage == 0 && !quickSortEnabled,
                    sortLabel = if (homeVisiblePage == 0) "Sort photos" else "Sort albums",
                    duplicateLabel = stringResource(
                        if (homeVisiblePage == 0) R.string.duplicate_find_all
                        else R.string.duplicate_find_each_album,
                    ),
                    canFindDuplicates = allPhotos.size > 1,
                    onQuickSort = {
                        showMenu = false
                        toggleQuickSort()
                    },
                    onSort = {
                        showMenu = false
                        if (homeVisiblePage == 0) showPhotoSortDialog = true
                        else showAlbumSortDialog = true
                    },
                    onFindDuplicates = {
                        showMenu = false
                        val isPhotosPage = homeVisiblePage == 0
                        duplicateViewModel.start(
                            allPhotos,
                            if (isPhotosPage) DeduplicationScope.ALL_FILES else DeduplicationScope.WITHIN_ALBUMS,
                            context.getString(
                                if (isPhotosPage) R.string.duplicate_scope_all else R.string.duplicate_scope_each_album,
                            ),
                        )
                    },
                    onSettings = {
                        showMenu = false
                        navigateToStack(navigationStack + Screen.Settings)
                    },
                )
            },
        )
    }

    val baseScreenIndex = navigationStack.indexOfLast {
        it !is Screen.Viewer && it !is Screen.TransferDestination
    }.coerceAtLeast(0)
    val baseScreen = navigationStack[baseScreenIndex]
    val viewerTransitions = LocalViewerTransitions.current!!
    SideEffect { viewerTransitions.destinationScope = baseScreen }
    val routePages = mutableMapOf<Int, GalleryPageContent>()
    navigationStack.forEachIndexed { screenIndex, screen ->
        if (screen !is Screen.Viewer && screen !is Screen.TransferDestination) {
            key(screenIndex, remember(screen) { screen.stableStateKey() }) {
                val isActiveRoute = screenIndex == baseScreenIndex
                routePages[screenIndex] =
                                    when (screen) {
                Screen.Home -> GalleryPage(topBar = homeTopBar) { _ ->

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
,
                    ) {
                        HorizontalPager(
                        state = homePagerState,
                        flingBehavior = PagerDefaults.flingBehavior(homePagerState, snapAnimationSpec = GalleryMotion.navigation()),
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !selectionMode && !albumSelectionMode && !quickSortEnabled
                    ) { page ->
                        CompositionLocalProvider(com.pixel.gallery.ui.viewer.LocalRestoredGridContent provides restoredHomeGrids?.get(page)) {
                        when (page) {
                            0 -> PhotosScreen(
                                retainMovedItems = true,
                                quickSort = quickSortConfig,
                                items = searchedPhotoGridItems,
                                gridContextKey = Triple(photoSearchResult.photoQuery, homeMediaType, photoSortOrder),
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
                                    navigateToStack(navigationStack + Screen.Viewer(
                                        initialId = id,
                                        source = if (isSearchActive) {
                                            Screen.ViewerSource.Search
                                        } else {
                                            Screen.ViewerSource.All
                                        },
                                        searchQuery = photoSearchResult.photoQuery.takeIf { isSearchActive },
                                        mediaType = homeMediaType,
                                    ))
                                },
                                selectedIds = selectedIds,
                                selectionMode = selectionMode,
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
                            1 -> com.pixel.gallery.ui.home.GroupedAlbumsScreen(
                                albums = searchedAlbums,
                                labels = labelSummaries,
                                grouping = albumGrouping,
                                defaultOrder = albumSortOrder,
                                onRememberOrder = albumGroupsViewModel::rememberRows,
                                onMoveRow = { rows, from, to -> albumGroupsViewModel.moveRow(rows, from, to, albumSortOrder) },
                                groupingEnabled = groupingEnabled,
                                groupCovers = resolvedGroupCovers,
                                selectedCollectionKey = if (inlineCoverGroupId != null) activeCoverKey else selectedCollection?.key,
                                coverPickingGroupId = inlineCoverGroupId,
                                onSetLabelCover = { id ->
                                    if (inlineCoverGroupId != null && albumGrouping.labelGroupId(id) == inlineCoverGroupId) {
                                        coverPickerLabel = id
                                    } else labelSummaries.firstOrNull { it.label.id == id }?.let { summary ->
                                        selectedCollection = if (selectedCollection?.key == labelCoverKey(id)) null else CoverTarget(labelCoverKey(id), if (summary.label.isSystem)
                                            context.getString(R.string.labels_favourites) else summary.label.name)
                                    }
                                },
                                onSetGroupCover = { id ->
                                    if (inlineCoverGroupId != null) {
                                        if (id == inlineCoverGroupId) pickingCover = false
                                    } else {
                                    coversViewModel.clearError()
                                    selectedCollection = if (selectedCollection?.key == groupCoverKey(id)) null else CoverTarget(groupCoverKey(id), if (id == com.pixel.gallery.model.LABELS_ALBUM_GROUP)
                                        context.getString(R.string.qs_labels_title) else albumGrouping.groups.firstOrNull { it.id == id }?.name.orEmpty())
                                    }
                                },
                                onToggleGroup = { id ->
                                    if (inlineCoverGroupId != null) {
                                        if (id == inlineCoverGroupId) pickingCover = false
                                    } else albumGroupsViewModel.toggle(id)
                                },
                                onLabel = { id ->
                                    if (inlineCoverGroupId != null && albumGrouping.labelGroupId(id) == inlineCoverGroupId) coverPickerLabel = id
                                    else {
                                        selectedCollection = null
                                        navigateToStack(navigationStack + Screen.Label(id))
                                    }
                                },
                                bottomPadding = contentBottomPadding,
                                gridState = if (normalizedAlbumSearchQuery.isEmpty()) {
                                    albumsGridState
                                } else {
                                    albumSearchGridState
                                },
                                onLabels = { navigateToStack(navigationStack + Screen.Labels) },
                                onTrash = { navigateToStack(navigationStack + Screen.Trash) },
                                onAlbum = { name ->
                                    if (inlineCoverGroupId != null) {
                                        if (albumGrouping.groups.any { it.id == inlineCoverGroupId && name in it.paths }) coverPickerAlbum = name
                                    } else {
                                        selectedCollection = null
                                        navigateToStack(navigationStack + Screen.Photo(name, homeMediaType))
                                    }
                                },
                                selectedLabelIds = selectedLabelAlbumIds,
                                onToggleLabelSelection = toggleLabelAlbum,
                                selectedPaths = selectedAlbumPaths,
                                selectionMode = albumSelectionMode,
                                onToggleSelection = toggleAlbumPath,
                                columns = albumGridColumns,
                                onColumnsChange = { photosViewModel.setAlbumGridColumns(it) },
                                search = normalizedAlbumSearchQuery.isNotEmpty(),
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
                                }

                }
                Screen.Settings -> SettingsScreen(
                    onBack = navigateBack,
                    onNavigateToDisplaySettings = { navigateToStack(navigationStack + Screen.DisplaySettings) },
                    onNavigateToLicenses = { navigateToStack(navigationStack + Screen.Licenses) },
                    onNavigateToPerformanceSettings = { navigateToStack(navigationStack + Screen.PerformanceSettings) },
                    onNavigateToLibrarySettings = { navigateToStack(navigationStack + Screen.LibrarySettings) },
                )
                Screen.PerformanceSettings -> PerformanceSettingsScreen(
                    onBack = navigateBack
                )
                Screen.DisplaySettings -> DisplaySettingsScreen(
                    onBack = navigateBack,
                )
                Screen.LibrarySettings -> LibrarySettingsScreen(onBack = navigateBack,
                    groupingEnabled = groupingEnabled, onGroupingEnabledChange = albumGroupsViewModel::setEnabled,
                    onNavigateToExcludedFolders = { navigateToStack(navigationStack + Screen.ExcludedFolders) })
                Screen.Labels -> LabelsScreen(
                    gridState = labelsGridState,
                    columns = labelsGridColumns,
                    onColumnsChange = { photosViewModel.setPageGridColumns("labels", it) },
                    labels = labelSummaries,
                    onBack = navigateBack,
                    onLabelClick = { navigateToStack(navigationStack + Screen.Label(it)) },
                    onCreateLabel = { labelsViewModel.createLabel(it) },
                    onRenameLabel = { id, name -> labelsViewModel.renameLabel(id, name) },
                    onDeleteLabels = labelsViewModel::deleteLabels,
                    onSetCover = openLabelCover,
                    onAddToGroup = if (!groupingEnabled) null else { ids, clear ->
                        labelGroupSelection = ids; clearLabelGroupSelection = clear; sheetExpandRequest++; showAlbumGroupPicker = true
                    },
                    errorMessage = labelsError,
                    onClearError = labelsViewModel::clearError,
                    isSaving = labelsSaving,
                    sortMode = labelsViewModel.sortMode.collectAsState().value,
                    onSortModeChange = labelsViewModel::setSortMode,
                    isForeground = isActiveRoute && currentScreen == screen,
                )
                is Screen.Label -> {
                    val labelId = screen.labelId
                    val labelColumns by remember(labelId, photosViewModel) {
                        photosViewModel.pageGridColumns("label:$labelId")
                    }.collectAsState(initial = 4)
                    val labelState = remember(labelId) { albumGridStates.getOrPut("label:$labelId") { LazyGridState() } }
                    val routeLabelPhotos = remember(labelId, allPhotos, labelMemberships, vault) {
                        val ids = labelMemberships.filter { it.labelId == labelId }.mapTo(hashSetOf()) { it.contentId }
                        val lockedIds = vault.mapTo(hashSetOf()) { it.contentId }
                        allPhotos.filter { it.contentId in ids && it.contentId !in lockedIds }
                    }
                    // null means grouping is pending; an empty result means the label is empty.
                    // Reset for each input so another label's result cannot appear during loading.
                    val routeGroupedLabelPhotos by key(labelId, routeLabelPhotos, labelColumns, photoSortOrder) {
                        produceState<List<GridItem>?>(null) {
                            value = withContext(Dispatchers.Default) {
                                photosViewModel.groupMedia(routeLabelPhotos, labelColumns, photoSortOrder)
                            }
                        }
                    }
                    LabelPhotosScreen(
                        label = labelEntries.find { it.id == labelId }, items = routeGroupedLabelPhotos,
                        selectedIds = selectedIds, selectionMode = selectionMode, columns = labelColumns, gridState = labelState,
                        onBack = navigateBack,
                        onNavigateToViewer = { id -> navigateToStack(navigationStack + Screen.Viewer(id, Screen.ViewerSource.Label, labelId = labelId)) },
                        onSelectionChange = updateSelection, onToggleSelection = toggleSelection,
                        onColumnsChange = { photosViewModel.setPageGridColumns("label:$labelId", it) },
                        quickSort = quickSortConfig, onToggleQuickSort = toggleQuickSort,
                        onSortPhotos = { showPhotoSortDialog = true },
                        onRemoveFromLabel = { ids ->
                            labelsViewModel.removeMediaFromLabel(labelId, ids) { selectedIds = emptySet() }
                        },
                        onEditLabel = { navigateToStack(navigationStack.dropLast(1) + Screen.Labels) },
                        onSetCover = { openLabelCover(labelId) },
                        isForeground = isActiveRoute && currentScreen == screen,
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
                        navigateToStack(navigationStack + Screen.Viewer(id, Screen.ViewerSource.Favourites))
                    },
                    selectedIds = selectedIds,
                    onSelectionChange = updateSelection,
                    onToggleSelection = toggleSelection,
                    items = groupedFavourites,
                    gridState = favouritesGridState
                )
                Screen.Trash -> TrashScreen(
                    onBack = navigateBack,
                    onSort = { showTrashSortDialog = true },
                    onNavigateToViewer = { id ->
                        ViewerLoadMetrics.entryRequested(
                            context.applicationContext,
                            id,
                            Screen.ViewerSource.Trash.name,
                            trash.size,
                        )
                        navigateToStack(navigationStack + Screen.Viewer(id, Screen.ViewerSource.Trash))
                    },
                    selectedIds = selectedIds,
                    onSelectionChange = updateSelection,
                    onToggleSelection = toggleSelection,
                    items = groupedTrash,
                    gridState = trashGridState
                )
                Screen.HiddenAlbums -> HiddenAlbumsScreen(
                    albums = hiddenTypeAlbums,
                    onBack = navigateBack,
                    onNavigateToAlbum = { path ->
                        navigateToStack(navigationStack + Screen.Photo(path, hiddenMediaType, hiddenAlbum = true))
                    },
                    onSort = { showAlbumSortDialog = true },
                    onDeduplicate = {
                        duplicateViewModel.start(hiddenTypePhotos, DeduplicationScope.WITHIN_ALBUMS,
                            context.getString(R.string.duplicate_scope_each_album))
                    },
                    mediaType = hiddenMediaType,
                    onMediaTypeChange = { type ->
                        if (type != hiddenMediaType) {
                            hiddenMediaType = type
                            selectedAlbumPaths = emptySet()
                            scope.launch { hiddenAlbumsGridState.scrollToItem(0) }
                        }
                    },
                    emptyMessage = when (hiddenMediaType) {
                        MediaTypeFilter.ALL -> null
                        MediaTypeFilter.IMAGES -> stringResource(R.string.media_type_no_image_albums)
                        MediaTypeFilter.VIDEOS -> stringResource(R.string.media_type_no_video_albums)
                    },
                    columns = hiddenAlbumsColumns,
                    onColumnsChange = { photosViewModel.setPageGridColumns("hidden-albums", it) },
                    gridState = hiddenAlbumsGridState,
                    bottomPadding = contentBottomPadding,
                    selectedPaths = selectedAlbumPaths,
                    onToggleAlbumSelection = toggleAlbumPath,
                    isForeground = isActiveRoute && currentScreen == screen,
                )
                Screen.LockedFolder -> LockedFolderScreen(
                    onBack = navigateBack,
                    onAuthenticationCancelled = {
                        if (currentScreen == Screen.LockedFolder) {
                            navigationReveal.settle(1)
                            navigateToStack(navigationStack.dropLast(1))
                        }
                    },
                    bottomPadding = contentBottomPadding,
                    gridState = vaultGridState,
                    onSort = { showPhotoSortDialog = true },
                    isForeground = isActiveRoute && currentScreen == screen,
                    onNavigateToViewer = { id ->
                        ViewerLoadMetrics.entryRequested(
                            context.applicationContext,
                            id,
                            Screen.ViewerSource.Vault.name,
                            vault.size,
                        )
                        navigateToStack(navigationStack + Screen.Viewer(id, Screen.ViewerSource.Vault))
                    },
                    selectedIds = selectedIds,
                    onSelectionChange = updateSelection,
                    onToggleSelection = toggleSelection,
                    items = groupedVault
                )
                Screen.ExcludedFolders -> ExcludedFoldersScreen(onBack = navigateBack)
                Screen.Licenses -> LicensesScreen(onBack = navigateBack)
                is Screen.Photo -> {
                    val albumName = screen.albumName
                    val albumKey = if (screen.hiddenAlbum) "hidden:$albumName" else albumName
                    val albumState = remember(albumKey) {
                        albumGridStates.getOrPut(albumKey) { LazyGridState() }
                    }
                    PhotoScreen(
                        quickSort = quickSortConfig,
                        onToggleQuickSort = toggleQuickSort,
                        albumName = albumName,
                        onSetCover = {
                            coversViewModel.clearError()
                            coverTarget = CoverTarget(albumCoverKey(albumName), java.io.File(albumName).name, screen.hiddenAlbum)
                        },
                        isHiddenAlbum = screen.hiddenAlbum,
                        initialMediaType = screen.initialMediaType,
                        isForeground = isActiveRoute && currentScreen == screen,
                        onBack = navigateBack,
                        onNavigateToViewer = { id, type, query ->
                            ViewerLoadMetrics.entryRequested(
                                context.applicationContext,
                                id,
                                Screen.ViewerSource.Album.name,
                                allPhotos.size,
                                detail = "album=$albumName",
                            )
                            navigateToStack(navigationStack + Screen.Viewer(
                                id, Screen.ViewerSource.Album, albumName,
                                searchQuery = query, mediaType = type, hiddenAlbum = screen.hiddenAlbum,
                            ))
                        },
                        selectedIds = selectedIds,
                        onSelectionChange = updateSelection,
                        onToggleSelection = toggleSelection,
                        onAlbumPhotosChanged = { photos ->
                            albumPhotoLists[albumKey] = photos
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
                is Screen.Viewer -> error("Viewer is prepared separately")
                is Screen.TransferDestination -> error("Transfer is an overlay")
            }
            }
        }
    }

    com.pixel.gallery.ui.components.PopupPresence(albumGroupError && !showAlbumGroupPicker) {
        com.pixel.gallery.ui.components.GalleryDialog(
            onDismissRequest = albumGroupsViewModel::clearError,
            title = { Text(stringResource(R.string.album_group_error)) },
            text = {},
            confirmButton = { TextButton(onClick = albumGroupsViewModel::clearError) { Text(stringResource(R.string.album_group_cancel)) } },
        )
    }
    val mixedAlbumSelection = selectedAlbumPaths.isNotEmpty() && selectedLabelAlbumIds.isNotEmpty()
    LaunchedEffect(mixedAlbumSelection) {
        if (mixedAlbumSelection) { showAlbumGroupPicker = false; selectionPanelHeight = 0.dp }
    }
    val selectionHeader: GalleryHeaderSpec? = run {

            if (selectedCollection != null) {
                val chosen = selectedCollection!!
                val groupMedia = if (chosen.key.startsWith("group:")) groupCoverCandidates[chosen.key.removePrefix("group:")].orEmpty()
                    else {
                        val labelId = chosen.key.removePrefix("label:").toLongOrNull()
                        val ids = labelMemberships.filter { it.labelId == labelId }.mapTo(hashSetOf()) { it.contentId }
                        coverVisibleMedia.filter { it.contentId in ids }
                    }
                AlbumSelectionTopBar(count = 1, onClear = { selectedCollection = null },
                    collectionTitle = chosen.title,
                    onPin = if (chosen.key.startsWith("group:")) null else ({ albumGroupsViewModel.pinRows(orderingRows, setOf(chosen.key)) }),
                    pinned = orderingRows.firstOrNull { it.key == chosen.key }?.let { chosen.key in albumGrouping.orderState(it.orderScope()).pinned } == true,
                    onSort = if (chosen.key.startsWith("group:")) ({ groupSortScope = chosen.key.removePrefix("group:"); showAlbumSortDialog = true }) else null,
                    groupExpanded = chosen.key.removePrefix("group:") !in albumGrouping.collapsedIds,
                    onToggleGroup = if (chosen.key.startsWith("group:")) ({
                        if (pickingCover) pickingCover = false
                        albumGroupsViewModel.toggle(chosen.key.removePrefix("group:"))
                    }) else null,
                    renameDescription = stringResource(R.string.collection_settings),
                    onRename = { coversViewModel.clearError(); coverTarget = selectedCollection },
                    onAddToGroup = chosen.key.takeIf { groupingEnabled && canGroupAlbumSelection(selectedAlbumPaths.size, 1) && it.startsWith("label:") && it != labelCoverKey(com.pixel.gallery.data.local.entity.FAVOURITES_LABEL_ID) }?.removePrefix("label:")?.toLongOrNull()?.let { labelId ->
                        { labelGroupSelection = setOf(labelId); clearLabelGroupSelection = { selectedCollection = null }; sheetExpandRequest++; showAlbumGroupPicker = true }
                    },
                    findDuplicatesLabel = stringResource(R.string.duplicate_find_group),
                    canFindDuplicates = groupMedia.size > 1,
                    onFindDuplicates = { duplicateViewModel.start(groupMedia, DeduplicationScope.ALL_FILES, chosen.title) },
                    extraMenu = {
                        val group = albumGrouping.groups.firstOrNull { groupCoverKey(it.id) == chosen.key }
                        if (group != null) com.pixel.gallery.ui.components.GalleryMenuRow(
                            stringResource(R.string.album_group_dissolve), androidx.compose.material.icons.Icons.Outlined.FolderOff,
                            onClick = { dissolvingCollection = group })
                        if (chosen.key == groupCoverKey(com.pixel.gallery.model.LABELS_ALBUM_GROUP))
                            com.pixel.gallery.ui.components.GalleryMenuRow(stringResource(R.string.album_group_manage), androidx.compose.material.icons.Icons.Outlined.Label,
                                onClick = { selectedCollection = null; navigateToStack(navigationStack + Screen.Labels) })
                    })
            } else if (albumSelectionMode) {
                AlbumSelectionTopBar(
                    count = selectedAlbumPaths.size + selectedLabelAlbumIds.size,
                    onPin = if (currentScreen == Screen.Home) ({ albumGroupsViewModel.pinRows(orderingRows,
                        selectedAlbumPaths.map { "album:$it" }.toSet() + selectedLabelAlbumIds.map { "label:$it" }) }) else null,
                    pinned = orderingRows.filter { it.album?.path in selectedAlbumPaths || it.label?.label?.id in selectedLabelAlbumIds }
                        .let { chosen -> chosen.isNotEmpty() && chosen.all { it.key in albumGrouping.orderState(it.orderScope()).pinned } },
                    onAddToGroup = if (groupingEnabled && currentScreen == Screen.Home && canGroupAlbumSelection(selectedAlbumPaths.size, selectedLabelAlbumIds.size)) ({
                        labelGroupSelection = selectedLabelAlbumIds.takeIf { it.isNotEmpty() }?.toSet()
                        clearLabelGroupSelection = if (selectedLabelAlbumIds.isNotEmpty()) ({ selectedLabelAlbumIds = emptySet() }) else null
                        sheetExpandRequest++; showAlbumGroupPicker = true
                    }) else null,
                    onClear = { selectedAlbumPaths = emptySet(); selectedLabelAlbumIds = emptySet() },
                    renameDescription = stringResource(R.string.collection_settings),
                    onRename = {
                        selectedLabelAlbumIds.singleOrNull()?.let(openLabelCover)
                        selectedAlbumPaths.singleOrNull()?.let { path ->
                            coversViewModel.clearError()
                            coverTarget = CoverTarget(albumCoverKey(path), java.io.File(path).name, currentScreen == Screen.HiddenAlbums)
                        }
                    },
                    onHide = if (selectedLabelAlbumIds.isEmpty()) ({
                        selectedAlbumPaths.forEach(photosViewModel::addHiddenFolder)
                        selectedAlbumPaths = emptySet()
                    }) else null,
                    onUnhide = if (currentScreen == Screen.HiddenAlbums) ({
                        photosViewModel.removeHiddenFolders(selectedAlbumPaths)
                        selectedAlbumPaths = emptySet()
                    }) else null,
                    onExclude = if (selectedLabelAlbumIds.isEmpty()) ({
                        selectedAlbumPaths.forEach(photosViewModel::addExcludedFolder)
                        selectedAlbumPaths = emptySet()
                    }) else null,
                    canFindDuplicates = selectedAlbumEntries.size > 1,
                    onFindDuplicates = {
                        duplicateViewModel.start(
                            selectedAlbumEntries,
                            DeduplicationScope.ALL_FILES,
                            context.getString(R.string.duplicate_scope_selected_albums),
                        )
                    },
                )
            } else if (selectionMode) {
                if (baseScreen == Screen.Trash || baseScreen == Screen.LockedFolder) {
                    val currentMediaIds = if (baseScreen == Screen.Trash) {
                        trash.mapTo(linkedSetOf()) { it.contentId }
                    } else {
                        vault.mapTo(linkedSetOf()) { it.contentId }
                    }
                    val allSelected = currentMediaIds.isNotEmpty() && currentMediaIds.all { it in selectedIds }
                    val collectionActions = buildList {
                        add(
                            MediaCollectionTopBarAction(
                                icon = Icons.Filled.CheckCircle,
                                label = stringResource(if (allSelected) R.string.deselect_all else R.string.select_all),
                                enabled = currentMediaIds.isNotEmpty(),
                                onClick = {
                                    updateSelection(if (allSelected) emptySet() else currentMediaIds)
                                },
                            ),
                        )
                        if (baseScreen == Screen.Trash) {
                            add(
                                MediaCollectionTopBarAction(
                                    icon = Icons.Outlined.RestoreFromTrash,
                                    label = "Restore",
                                    onClick = {
                                        photosViewModel.restoreMediaBulk(selectedTrashEntries.map { it.uri })
                                        selectedIds = emptySet()
                                    },
                                ),
                            )
                            add(
                                MediaCollectionTopBarAction(
                                    icon = Icons.Default.Delete,
                                    label = "Delete permanently",
                                    onClick = {
                                        isDeletePermanentlyConfirm = true
                                        showDeleteConfirmDialog = true
                                    },
                                ),
                            )
                        } else {
                            add(
                                MediaCollectionTopBarAction(
                                    icon = Icons.Default.Share,
                                    label = "Share",
                                    enabled = selectedVaultEntries.isNotEmpty(),
                                    onClick = { shareMedia(context, selectedVaultEntries) },
                                ),
                            )
                            add(
                                MediaCollectionTopBarAction(
                                    icon = Icons.Outlined.LockOpen,
                                    label = "Move out of Locked Folder",
                                    enabled = !isVaultRestoreRunning && selectedVaultEntries.isNotEmpty(),
                                    showProgress = isVaultRestoreRunning,
                                    onClick = {
                                        val idsToRestore = selectedVaultEntries.map { it.contentId }
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
                                            messages.show(message)
                                        }
                                    },
                                ),
                            )
                            add(
                                MediaCollectionTopBarAction(
                                    icon = Icons.Default.Delete,
                                    label = "Delete permanently",
                                    enabled = !isVaultDeleteRunning && selectedVaultEntries.isNotEmpty(),
                                    showProgress = isVaultDeleteRunning,
                                    onClick = {
                                        isDeletePermanentlyConfirm = true
                                        showDeleteConfirmDialog = true
                                    },
                                ),
                            )
                        }
                    }
                    MediaCollectionSelectionTopBar(
                        count = selectedIds.size,
                        onClear = {
                            selectedIds = emptySet()
                            quickSortEnabled = false
                            showQuickTargets = false
                            showLabelEditor = false
                            labelEditorFromGrid = false
                            gridTransferSession = null
                        },
                        actions = collectionActions,
                        overflowExpanded = showSelectionMenu,
                        onOverflowExpandedChange = { showSelectionMenu = it },
                    ) {
                        if (baseScreen == Screen.Trash) {
                            GalleryMenuRow("Sort Bin", Icons.AutoMirrored.Filled.Sort, accented = true, onClick = {
                                showSelectionMenu = false
                                showTrashSortDialog = true
                            })
                        } else {
                            GalleryMenuRow("Sort Locked Files", Icons.AutoMirrored.Filled.Sort, accented = true, onClick = {
                                showSelectionMenu = false
                                showPhotoSortDialog = true
                            })
                        }
                    }
                } else {
                    GalleryHeader(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = headerColor),
                        title = { com.pixel.gallery.ui.components.SelectedCountBadge(selectedIds.size) },
                        navigationIcon = {
                            IconButton(onClick = {
                            selectedIds = emptySet()
                            quickSortEnabled = false
                            showQuickTargets = false
                            showLabelEditor = false
                            labelEditorFromGrid = false
                            gridTransferSession = null
                        }) {
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
                            if (baseScreen is Screen.Photo) {
                                val albumKey = if (baseScreen.hiddenAlbum) "hidden:${baseScreen.albumName}" else baseScreen.albumName
                                val currentMediaIds = albumPhotoLists[albumKey]
                                    .orEmpty()
                                    .map { it.contentId }
                                    .filterNot { it in quickQueue.reservedIds }
                                    .toSet()
                                val isAlbumFullySelected = currentMediaIds.isNotEmpty() &&
                                    currentMediaIds.all { it in selectedIds }
                                IconButton(
                                    enabled = currentMediaIds.isNotEmpty(),
                                    onClick = {
                                        updateSelection(if (isAlbumFullySelected) emptySet() else currentMediaIds)
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
                            if (baseScreen is Screen.Label) {
                                IconButton(
                                    enabled = selectedIds.isNotEmpty() && !labelsSaving,
                                    onClick = {
                                        labelsViewModel.removeMediaFromLabel(baseScreen.labelId, selectedIds) {
                                            selectedIds = emptySet()
                                        }
                                    },
                                ) {
                                    Icon(Icons.Outlined.LabelOff, contentDescription = "Remove from label")
                                }
                                GalleryOverflowButton(
                                    expanded = showSelectionMenu,
                                    onExpandedChange = { showSelectionMenu = it },
                                ) {
                                    if (!quickSortEnabled) GalleryMenuRow(
                                        stringResource(R.string.qs_start), Icons.AutoMirrored.Outlined.DriveFileMove, accented = true,
                                        onClick = { showSelectionMenu = false; toggleQuickSort() },
                                    )
                                    GalleryMenuRow("Sort Photos", Icons.AutoMirrored.Filled.Sort, accented = true, onClick = {
                                        showSelectionMenu = false
                                        showPhotoSortDialog = true
                                    })
                                }
                            } else {
                                IconButton(enabled = selectedEntries.isNotEmpty(), onClick = {
                                    shareMedia(context, selectedEntries)
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = "Share")
                                }

                                IconButton(enabled = selectedEntries.isNotEmpty(), onClick = {
                                    photosViewModel.clearTransferState()
                                    showLabelEditor = false
                                    quickSortEnabled = false
                                    showQuickTargets = false
                                    selectionPanelHeight = 0.dp
                                    sheetExpandRequest++
                                    if (gridTransferSession == null) gridTransferSession = java.util.UUID.randomUUID().toString()
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
                                GalleryOverflowButton(
                                    expanded = showSelectionMenu,
                                    onExpandedChange = { showSelectionMenu = it },
                                ) {
                                    if (!quickSortEnabled && (baseScreen == Screen.Home || baseScreen is Screen.Photo)) GalleryMenuRow(
                                        stringResource(R.string.qs_start), Icons.AutoMirrored.Outlined.DriveFileMove, accented = true,
                                        onClick = { showSelectionMenu = false; toggleQuickSort() },
                                    )
                                    GalleryMenuRow(
                                        stringResource(R.string.label_edit),
                                        Icons.Outlined.Label,
                                        accented = true,
                                        onClick = { showSelectionMenu = false; openLabelEditor(selectedIds) },
                                    )
                                    GalleryMenuRow("Move to locked folder", Icons.Outlined.Lock, onClick = {
                                        showSelectionMenu = false
                                        selectedEntries.forEach(photosViewModel::moveToVault)
                                        selectedIds = emptySet()
                                    })
                                }
                            }
                        }
                    }
                    )
                }
            } else null
            }

    val viewerPage: GalleryPageContent? = if (retainedViewer != null) {
        key(remember(retainedViewer) { retainedViewer.stableStateKey() }) {
                val viewer = retainedViewer
                val cachedAlbumPhotos = viewer.albumName?.let { name ->
                    albumPhotoLists[if (viewer.hiddenAlbum) "hidden:$name" else name]
                }
                val livePhotosForViewer = remember(
                    viewer,
                    viewerPhotos,
                    viewerFavourites,
                    viewerTrash,
                    viewerVault,
                    hiddenPhotos,
                    cachedAlbumPhotos,
                    labelMemberships,
                ) {
                    val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
                    var listSource = "DIRECT"
                    val result = when (viewer.source) {
                        // The browse index is rebuilt asynchronously after an activity
                        // returns to the foreground. It publishes an empty snapshot
                        // before the stable repository list is available, which would
                        // unmount ViewerScreen and cause a visible reload. The viewer
                        // already has the ordered repository snapshot, so use it
                        // directly and only apply the media/search filters here.
                        Screen.ViewerSource.All -> if (viewer.mediaType == MediaTypeFilter.ALL) {
                            viewerPhotos
                        } else {
                            viewerPhotos.filter(viewer.mediaType::matches)
                        }
                        Screen.ViewerSource.Search -> viewerPhotos.filter {
                            viewer.mediaType.matches(it) && it.matchesFileSearch(viewer.searchQuery.orEmpty())
                        }
                        Screen.ViewerSource.Favourites -> viewerFavourites
                        Screen.ViewerSource.Label -> {
                            // Settings may cover this retained viewer; its source
                            // remains its own label, not the foreground route.
                            val ids = labelMemberships.filter { it.labelId == viewer.labelId }
                                .mapTo(hashSetOf()) { it.contentId }
                            val lockedIds = viewerVault.mapTo(hashSetOf()) { it.contentId }
                            viewerPhotos.filter { it.contentId in ids && it.contentId !in lockedIds }
                        }
                        Screen.ViewerSource.Trash -> viewerTrash
                        Screen.ViewerSource.Vault -> viewerVault
                        Screen.ViewerSource.Album -> {
                            // Album flows also publish an empty initial value while
                            // their source is being restored. Treat that value as
                            // unresolved and derive the album from the stable media
                            // snapshot instead of tearing down the viewer.
                            cachedAlbumPhotos?.takeIf { it.isNotEmpty() } ?: run {
                                listSource = "FALLBACK_SCAN"
                                (if (viewer.hiddenAlbum) hiddenPhotos else viewerPhotos).filter {
                                    val lastSlash = it.path.lastIndexOf('/')
                                    if (lastSlash <= 0) false
                                    else {
                                        val parentPath = it.path.substring(0, lastSlash)
                                        val prevSlash = parentPath.lastIndexOf('/')
                                        val parentName = if (prevSlash >= 0) parentPath.substring(prevSlash + 1) else parentPath
                                        val inAlbum = if (viewer.hiddenAlbum) it.path.isInsideMediaFolder(viewer.albumName.orEmpty())
                                            else if (viewer.albumName?.contains('/') == true) parentPath == viewer.albumName else parentName == viewer.albumName
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
                        isForeground = visibleViewer != null,
                        onCurrentMediaChanged = { mediaId ->
                            if (showLabelEditor && !labelEditorFromGrid &&
                                editingLabelIds != setOfNotNull(mediaId)) {
                                editingLabelIds = setOfNotNull(mediaId) - quickQueue.reservedIds
                                labelsViewModel.clearError()
                            }
                            val picker = navigationStack.lastOrNull() as? Screen.TransferDestination
                            if (picker?.origin == Screen.TransferOrigin.Viewer &&
                                picker.entryIds.singleOrNull() != mediaId) {
                                if (!photosViewModel.transferUiState.value.isRunning) {
                                    photosViewModel.clearTransferState()
                                }
                                navigateToStack(navigationStack.dropLast(1) + picker.copy(
                                    entryIds = setOfNotNull(mediaId).toLongArray(),
                                ))
                            }
                        },
                        onEditLabels = openLabelEditor,
                        labelsForMedia = { labelIdsByMedia[it]?.mapNotNull(labelNamesById::get).orEmpty() },
                        labelIdsForMedia = { labelIdsByMedia[it].orEmpty() },
                        onOpenLabel = { labelId ->
                            navigateToStack(navigationStack.dropLast(1) + Screen.Label(labelId))
                        },
                        allowLabels = viewer.source !in setOf(Screen.ViewerSource.Trash, Screen.ViewerSource.External),
                        reservedIds = quickQueue.reservedIds,
                        initialId = viewer.initialId,
                        photos = photosForViewer,
                        transformStateStore = viewerTransformStateStore,
                        onBack = {
                            val viewerIndex = navigationStack.indexOf(viewer)
                            if (viewerIndex > 0) {
                                if (showLabelEditor && !labelEditorFromGrid) {
                                    showLabelEditor = false
                                    editingLabelIds = emptySet()
                                }
                                navigateToStack(navigationStack.take(viewerIndex))
                            }
                        },
                        allowTransfer = viewer.source !in setOf(
                            Screen.ViewerSource.Trash,
                            Screen.ViewerSource.Vault,
                            Screen.ViewerSource.External
                        ),
                        onRequestTransfer = { media ->
                            sheetExpandRequest++
                            photosViewModel.clearTransferState()
                            navigateToStack(navigationStack.dropLastWhile { it is Screen.TransferDestination } + Screen.TransferDestination(
                                longArrayOf(media.contentId),
                                Screen.TransferOrigin.Viewer
                            ))
                        },
                        allowUnlockFromVault = viewer.source == Screen.ViewerSource.Vault,
                        onUnlockFromVault = { media ->
                            photosViewModel.restoreFromVault(media.contentId) { restored ->
                                if (restored) navigateToStack(navigationStack.dropLast(1))
                            }
                        },
                    )
                } else {
                    GalleryPage(modifier = Modifier.background(Color.Black),
                        topBar = { GalleryHeader(title = {}, visible = false) }) { _ -> }

                }
            }
        } else null

    val reviewPage = DuplicateReviewDialog(
        state = duplicateState,
        onDismiss = duplicateViewModel::dismiss,
        onToggle = duplicateViewModel::toggle,
        onConfirm = duplicateViewModel::confirm,
    )
    val rootNavigationTab = when {
        Screen.LockedFolder in navigationStack -> GalleryNavigationTab.Lock
        Screen.HiddenAlbums in navigationStack -> GalleryNavigationTab.Hidden
        // Selection starts with the page request/gesture, not after its animation finishes.
        else -> if (homePagerState.targetPage == 0) GalleryNavigationTab.Photos else GalleryNavigationTab.Albums
    }
    val homePagerScrolling = homePagerState.isScrollInProgress
    SideEffect {
        com.pixel.gallery.utils.GalleryInteractionProbe.navigation(
            rootNavigationTab.ordinal, homePagerState.settledPage,
            homePagerState.targetPage, homePagerScrolling,
        )
    }
    val closeCollectionSettings = { if (!coverSaving && !collectionEditSaving) { coverTarget = null; pickingCover = false; coversViewModel.clearError() } }
    BackHandler(enabled = selectedCollection != null && coverTarget == null) { selectedCollection = null }
    BackHandler(enabled = coverTarget != null) {
        if (pickingCover && (coverPickerAlbum != null || coverPickerLabel != null)) {
            coverPickerAlbum = null; coverPickerLabel = null
        } else if (pickingCover) pickingCover = false else closeCollectionSettings()
    }
    val collectionPage = coverTarget?.let { target ->
        val chooseCover: (Long) -> Unit = { id ->
            coverCandidates.firstOrNull { it.contentId == id }?.let {
                coverDraft = it.uri; pickingCover = false
                coverPickerAlbum = null; coverPickerLabel = null
            }
        }
        if (pickingCover) {
            val groupId = target.key.takeIf { it.startsWith("group:") }?.removePrefix("group:")
            val selectedPath = coverPickerAlbum
            val selectedLabel = coverPickerLabel
            when {
                // Keep the actual home grid mounted: same rows, density, order and scroll state.
                groupId != null && selectedPath == null && selectedLabel == null -> null
                target.key.startsWith("album:") || selectedPath != null -> PhotoScreen(
                    albumName = selectedPath ?: target.key.removePrefix("album:"), isHiddenAlbum = target.hidden,
                    onBack = { if (selectedPath != null) coverPickerAlbum = null else pickingCover = false },
                    onNavigateToViewer = { id, _, _ -> chooseCover(id) },
                    onToggleSelection = chooseCover, selectionMode = false,
                    coverPicking = true, columnsPageOverride = "cover-picker:${target.key}:${selectedPath.orEmpty()}",
                    viewModel = photosViewModel,
                )
                else -> {
                    val labelIds = selectedLabel?.let { id ->
                        labelMemberships.filter { it.labelId == id }.mapTo(hashSetOf()) { it.contentId }
                    }
                    com.pixel.gallery.ui.home.CollectionCoverPhotoPage(
                        title = selectedLabel?.let { id ->
                            labelSummaries.firstOrNull { it.label.id == id }?.let {
                                if (it.label.isSystem) context.getString(R.string.favourites) else it.label.name
                            }
                        } ?: target.title,
                        media = if (labelIds == null) coverCandidates else coverCandidates.filter { it.contentId in labelIds },
                        columns = coverColumns,
                        onColumnsChange = { photosViewModel.setPageGridColumns(coverDensityKey, it) },
                        photosViewModel = photosViewModel,
                        onBack = { if (selectedLabel != null) coverPickerLabel = null else pickingCover = false },
                        onChoose = chooseCover,
                    )
                }
            }
        } else null
    }

    val showRootNavigation = (currentScreen == Screen.Home || currentScreen == Screen.LockedFolder || currentScreen == Screen.HiddenAlbums) &&
        !quickSortEnabled && !albumSelectionMode && !selectionMode && reviewPage == null && coverTarget == null && selectedCollection == null
    LaunchedEffect(currentScreen, showRootNavigation) {
        if (!showRootNavigation) navigationReveal.reset(rootNavigationTab.side)
        else if (rootNavigationTab.side != 0 && navigationReveal.anchor != rootNavigationTab.side) {
            navigationReveal.settle(rootNavigationTab.side)
        }
        if (showRootNavigation) scrollBehavior.state.offset = 0f
    }
    val density = LocalDensity.current
    val routeHeaderScrollBehavior = topBarScrollBehavior
    // The shared header follows the visible page, never the previous route's offset.
    val activeHeaderGrid = when (val route = baseScreen) {
        Screen.Home -> if (homeVisiblePage == 0) {
            if (normalizedPhotoSearchQuery.isEmpty()) recentsGridState else photoSearchGridState
        } else {
            if (normalizedAlbumSearchQuery.isEmpty()) albumsGridState else albumSearchGridState
        }
        Screen.Trash -> trashGridState
        Screen.Favourites -> favouritesGridState
        Screen.HiddenAlbums -> hiddenAlbumsGridState
        Screen.LockedFolder -> vaultGridState
        Screen.Labels -> labelsGridState
        is Screen.Label -> albumGridStates["label:${route.labelId}"]
        is Screen.Photo -> albumGridStates[if (route.hiddenAlbum) "hidden:${route.albumName}" else route.albumName]
        else -> null
    }
    val gridAtTop by remember(activeHeaderGrid) {
        derivedStateOf {
            activeHeaderGrid?.let {
                !it.canScrollBackward || (it.firstVisibleItemIndex == 0 && it.firstVisibleItemScrollOffset == 0)
            } == true
        }
    }
    val detailToolbarLocked = gridAtTop || coverTarget != null || (visibleViewer != null && reviewPage == null)
    LaunchedEffect(detailToolbarLocked) {
        if (detailToolbarLocked) {
            // Viewer controls must remain reachable while the media surface
            // consumes vertical gestures for paging and dismissal.
            topBarScrollBehavior.state.heightOffset = 0f
            topBarScrollBehavior.state.contentOffset = 0f
        }
    }
    val headerHeightOffsetPx = if (detailToolbarLocked) {
        0f
    } else {
        with(density) {
            routeHeaderScrollBehavior.state.heightOffset.coerceIn(-64.dp.toPx(), 0f)
        }
    }
    val baseRouteTopInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() +
        with(density) {
            (64.dp.toPx() + headerHeightOffsetPx).toDp()
        }

    CompositionLocalProvider(LocalGallerySettingsAction provides {
        if (currentScreen != Screen.Settings) navigateToStack(navigationStack + Screen.Settings)
    }) {
        UnifiedGalleryContainer.instance.Shell(
            containerColor = appShellColor,
            modifier = Modifier,
            // Every route drives this one mounted toolbar through the same scroll state.
            scrollBehavior = routeHeaderScrollBehavior.takeUnless { detailToolbarLocked },
        snackbarHost = {
            GalleryMessageHost()
        },
        header = collectionPage?.header ?: reviewPage?.header ?: viewerPage?.takeIf { visibleViewer != null }?.header ?: selectionHeader ?: routePages.getValue(baseScreenIndex).header,
        underlayHeader = (selectionHeader ?: routePages.getValue(baseScreenIndex).header).takeIf {
            visibleViewer != null && reviewPage == null
        },
        underlayScrollBehavior = routeHeaderScrollBehavior.takeUnless { detailToolbarLocked },
        underlayVisible = { viewerTransitions.revealing },
        underlayAlpha = {
            1f - (viewerTransitions.active?.gesture?.visual?.backdropAlpha ?: 1f)
        },
            content = { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()

        ) {
            // Screen content management
            // Keep every screen in the active navigation stack composed. Retaining each
            // route in place preserves its remembered UI and grid state; only the active
            // layer is visible and exposed to accessibility.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = baseRouteTopInset)
                    .nestedScroll(scrollBehavior)
                    .then(if (detailToolbarLocked) Modifier else Modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection))
                    .drawWithContent {
                        // Hide the route decorations together with its content.
                        if ((visibleViewer == null || viewerTransitions.revealing) && reviewPage == null) drawContent()
                    }
                    .then(if (visibleViewer == null) Modifier.galleryContentEdge() else Modifier)
                    .pointerInput(navigationReveal, currentScreen) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // Observe only: the original click, long press and scroll still receive this event.
                            if (currentScreen == Screen.Home && navigationReveal.expanded) navigationReveal.settle(0)
                        }
                    }
            ) {
                navigationStack.forEachIndexed { screenIndex, screen ->
                    if (screen !is Screen.Viewer && screen !is Screen.TransferDestination) {
                        key(screenIndex, remember(screen) { screen.stableStateKey() }) {
                            val isActiveRoute = screenIndex == baseScreenIndex
                            RetainedGalleryLayer(
                                visible = isActiveRoute && (visibleViewer == null || viewerTransitions.revealing) && reviewPage == null && collectionPage == null,
                                interactive = isActiveRoute && visibleViewer == null && reviewPage == null && collectionPage == null,
                            ) {
                                CompositionLocalProvider(
                                    LocalViewerGridScope provides screen,
                                    com.pixel.gallery.ui.quicksort.LocalMediaSelectionMode provides selectionMode,
                                    com.pixel.gallery.ui.quicksort.LocalSelectionPanelInset provides
                                        (if (showLabelEditor || gridTransferSession != null) selectionPanelHeight else 0.dp),
                                    com.pixel.gallery.ui.quicksort.LocalMoveGridPresentation provides gridTransferSession?.let {
                                        com.pixel.gallery.ui.quicksort.MoveGridPresentation(it, movedBatchPaths,
                                            quickQueue.heldItems, moveVisibility.relocatedEntries)
                                    },
                                ) {
                                    routePages.getValue(screenIndex).content()
                                }
                            }
                        }
                    }
                }
            }

            // The same viewer model remains mounted beneath Move/Copy.
            viewerPage?.let { page ->
                RetainedGalleryLayer(visible = visibleViewer != null && reviewPage == null) { page.content() }
            }


            val transferScreen = currentScreen as? Screen.TransferDestination
            com.pixel.gallery.ui.components.PopupPresence(
                (transferScreen to (transferScreen?.entryIds?.toSet() ?: selectedIds)).takeIf {
                    transferScreen != null || gridTransferSession != null
                }
            ) { (transferScreen, transferIds) ->
                val transferableEntries = remember(transferIds, allPhotos, hiddenPhotos, favourites) {
                    if (transferIds.isEmpty()) emptyList() else {
                        // Only the selected recipients need a temporary lookup.
                        val byId = sequenceOf(allPhotos, hiddenPhotos, favourites).flatMap { it.asSequence() }
                            .filter { it.contentId in transferIds }.associateBy { it.contentId }
                        transferIds.toList().mapNotNull(byId::get)
                    }
                }
                TransferDestinationScreen(
                    expandRequest = sheetExpandRequest,
                    entries = transferableEntries,
                    showSourceName = transferScreen?.origin == Screen.TransferOrigin.Viewer,
                    moveSessionId = gridTransferSession,
                    onPanelHeightChange = { selectionPanelHeight = it },
                    onTransferStarted = { ordinaryTransferIds = it },
                    onMoveAccepted = { ids -> selectedIds = selectedIds - ids },
                    onBack = {
                        photosViewModel.clearTransferState()
                        if (transferScreen == null) gridTransferSession = null
                        else navigateToStack(navigationStack.dropLast(1))
                    },
                    columns = moveSheetColumns,
                    onColumnsChange = photosViewModel::setMoveSheetColumns,
                    onFinished = { summary ->
                        val action = if (summary.mode == TransferMode.MOVE) "Moved" else "Copied"
                        val message = buildString {
                            append("$action ${summary.succeeded} item")
                            if (summary.succeeded != 1) append('s')
                            if (summary.failed > 0) append("; ${summary.failed} failed")
                            if (summary.skipped > 0) append("; ${summary.skipped} skipped")
                        }
                        if (summary.failed == 0) messages.show(message)
                        if (transferScreen == null) {
                            selectedIds = selectedIds - (ordinaryTransferIds - summary.failures.map { it.entry.contentId }.toSet())
                        }
                        photosViewModel.clearTransferState()
                    }
                )
            }



            if (showRootNavigation) {
                RevealGalleryNavigation(
                    selected = rootNavigationTab,
                    state = navigationReveal,
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 16.dp),
                    onSelect = { tab ->
                        showMenu = false
                        when (tab) {
                            GalleryNavigationTab.Lock, GalleryNavigationTab.Hidden -> {
                                if (tab != rootNavigationTab) {
                                    navigationReveal.settle(tab.side)
                                    navigateToStack(listOf(Screen.Home, if (tab == GalleryNavigationTab.Lock) Screen.LockedFolder else Screen.HiddenAlbums))
                                }
                            }
                            GalleryNavigationTab.Photos, GalleryNavigationTab.Albums -> {
                                navigationReveal.settle(0)
                                val page = if (tab == GalleryNavigationTab.Photos) 0 else 1
                                if (currentScreen == Screen.Home) {
                                    scope.launch { homePagerState.animateScrollToPage(page, animationSpec = GalleryMotion.navigation()) }
                                } else {
                                    scope.launch {
                                        // Set the retained home page before exposing it; no intermediate wrong tab.
                                        homePagerState.scrollToPage(page)
                                        navigateToStack(listOf(Screen.Home))
                                    }
                                }
                            }
                        }
                    },
                )
            }
            reviewPage?.let { page ->
                Box(Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
                    page.content()
                }
            }
            collectionPage?.let { page ->
                Box(Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()).galleryContentEdge()) {
                    page.content()
                }
            }
            }

    com.pixel.gallery.ui.components.PopupPresence(coverTarget?.takeUnless { pickingCover }) { target ->
        val manual = resolveCollectionCover(coverDraft, coverCandidates)
        val group = albumGrouping.groups.firstOrNull { groupCoverKey(it.id) == target.key }
        val label = labelSummaries.firstOrNull { labelCoverKey(it.label.id) == target.key }?.label
        val canRename = target.key.startsWith("album:") || group != null || (label != null && !label.isSystem)
        com.pixel.gallery.ui.home.CollectionSettingsDialog(
            target, collectionNameDraft, { collectionNameDraft = it; collectionEditError = false }, canRename,
            manual ?: autoCover, manual == null, coverSaving || collectionEditSaving, coverError || collectionEditError,
            labelGroup = target.key == groupCoverKey(com.pixel.gallery.model.LABELS_ALBUM_GROUP) || group?.isLabelGroup == true,
            onBack = closeCollectionSettings, onChoose = { pickingCover = true }, onAutomatic = { coverDraft = null },
            onSave = { illustration ->
                collectionEditSaving = true
                scope.launch {
                    var key = target.key
                    val name = collectionNameDraft.trim()
                    val result = if (!canRename || name == target.title) Result.success(Unit) else when {
                        group != null -> albumGroupsViewModel.renameAndWait(group.id, name)
                        label != null -> labelsViewModel.renameAndWait(label.id, name)
                        else -> photosViewModel.renameAlbum(target.key.removePrefix("album:"), name).also {
                            if (it.isSuccess) key = albumCoverKey(java.io.File(java.io.File(target.key.removePrefix("album:")).parentFile, name).path)
                        }
                    }
                    collectionEditSaving = false
                    val completeSave: () -> Unit = {
                        if (illustration != null && target.key.startsWith("group:")) {
                            context.getSharedPreferences("group_illustrations", 0).edit()
                                .putInt(target.key.removePrefix("group:"), illustration).apply()
                        }
                        selectedCollection = selectedCollection?.copy(key = key, title = name)
                        if (key != target.key) selectedAlbumPaths = emptySet()
                        coverTarget = null; pickingCover = false; coversViewModel.clearError()
                    }
                    if (result.isFailure) collectionEditError = true
                    else if (target.key.startsWith("group:")) completeSave()
                    else coversViewModel.save(key, manual?.uri, completeSave)
                }
            },
        )
    }

    com.pixel.gallery.ui.components.PopupPresence(showAlbumGroupPicker && !mixedAlbumSelection) {
        val liveLabelSelection = labelGroupSelection?.intersect(labelSummaries.filterNot { it.label.isSystem }.mapTo(hashSetOf()) { it.label.id })
        com.pixel.gallery.ui.home.AlbumGroupPickerSheet(
                    expandRequest = sheetExpandRequest,
            groups = albumGrouping.groups.filter { it.isLabelGroup == (labelGroupSelection != null) }.map {
                if (it.isLabelGroup) it.copy(paths = it.labelIds.orEmpty().filter { id -> labelSummaries.any { summary -> summary.label.id == id && !summary.label.isSystem } }.map(Long::toString)) else it
            },
            labelMode = labelGroupSelection != null,
            nameValidationGroups = albumGrouping.groups,
            groupCovers = resolvedGroupCovers,
            albums = albums,
            selectedPaths = liveLabelSelection?.mapTo(linkedSetOf(), Long::toString) ?: selectedAlbumPaths,
            columns = if (labelGroupSelection != null) labelGroupPickerColumns else albumGroupPickerColumns,
            onColumnsChange = { photosViewModel.setPageGridColumns(if (labelGroupSelection != null) "label-groups:picker" else "groups:picker", it) },
            isSaving = albumGroupSaving,
            errorMessage = if (albumGroupError) stringResource(R.string.album_group_error) else null,
            onPanelHeightChange = { selectionPanelHeight = it },
            onDismiss = {
                if (!albumGroupSaving) { showAlbumGroupPicker = false; selectionPanelHeight = 0.dp; albumGroupsViewModel.clearError() }
            },
            onAssign = { id ->
                val labels = liveLabelSelection
                if (labels != null) albumGroupsViewModel.assignLabels(labels, id)
                else albumGroupsViewModel.assign(selectedAlbumPaths.toSet(), id)
            },
            onCreate = { name, art, onSaved ->
                val id = java.util.UUID.randomUUID().toString()
                val labels = liveLabelSelection
                val saved = {
                    context.getSharedPreferences("group_illustrations", 0).edit().putInt(id, art).apply()
                    if (labels != null) {
                        labelGroupSelection = emptySet(); clearLabelGroupSelection?.invoke()
                    } else selectedAlbumPaths = emptySet()
                    onSaved()
                }
                if (labels != null) albumGroupsViewModel.createLabels(name, labels, saved, id)
                else albumGroupsViewModel.create(name, selectedAlbumPaths.toSet(), saved, id)
            },
        )
    }
    com.pixel.gallery.ui.components.PopupPresence(
        (if (labelEditorFromGrid) selectedIds else editingLabelIds).takeIf { showLabelEditor }
    ) { labelBatchIds ->
        LabelEditorSheet(
                    expandRequest = sheetExpandRequest,
            labels = editorLabelEntries, labelSummaries = labelSummaries, memberships = labelMemberships, selectedIds = labelBatchIds, recentLabelIds = recentLabelIds,
            sourceName = if (labelEditorFromGrid) null else labelBatchIds.singleOrNull()?.let { id ->
                allPhotos.firstOrNull { it.contentId == id }?.path?.let { java.io.File(it).name }
            },
            columns = labelSheetColumns,
            onColumnsChange = photosViewModel::setLabelSheetColumns,
            onDismiss = {
                showLabelEditor = false
                editingLabelIds = emptySet()
                labelsViewModel.clearError()
            },
            onPanelHeightChange = { selectionPanelHeight = it },
            onApply = { edits, onSaved ->
                // Capture the recipients now; later grid selection belongs to another operation.
                labelsViewModel.applyEdits(labelBatchIds.toSet(), edits, onSaved)
            },
            onReorderLabels = labelsViewModel::reorderLabels,
            isSaving = labelsSaving, errorMessage = labelsError,
        )
    }
    com.pixel.gallery.ui.components.PopupPresence(showQuickTargets && quickSortEnabled && quickSortOwner == quickSortScope) {
        QuickSortTargetsSheet(
                    expandRequest = sheetExpandRequest,
            albumUses = quickQueue.albumUses,
            availableTargets = transferTargets.filter { it.documentUri == null },
            selectedTargets = quickPreferences.targets,
            dockTop = quickPreferences.dockTop,
            onDockTopChange = photosViewModel::setQuickSortDockTop,
            onApply = { photosViewModel.setQuickSortTargets(it) },
            onPanelHeightChange = { quickTargetsPanelHeight = it },
            onDismiss = {
                showQuickTargets = false
                quickSortEnabled = false
            },
            onTargetsChange = photosViewModel::setQuickSortTargets,
            onCreateAlbum = { parent, name, onResult -> photosViewModel.createTransferFolder(parent, name, onResult) },
            columns = quickSortSheetColumns,
            onColumnsChange = photosViewModel::setQuickSortSheetColumns,
        )
    }

    com.pixel.gallery.ui.components.PopupPresence(editingGroupName) { group ->
        com.pixel.gallery.ui.home.AlbumGroupNameDialog(albumGrouping.groups, group, { editingGroupName = null }) { name ->
            albumGroupsViewModel.rename(group.id, name)
            coverTarget = coverTarget?.copy(title = name); selectedCollection = selectedCollection?.copy(title = name)
            editingGroupName = null
        }
    }
    com.pixel.gallery.ui.components.PopupPresence(dissolvingCollection) { group ->
        com.pixel.gallery.ui.home.AlbumGroupDissolveDialog(group.name, { dissolvingCollection = null }) {
            albumGroupsViewModel.dissolve(group.id); dissolvingCollection = null; coverTarget = null; selectedCollection = null
        }
    }
    com.pixel.gallery.ui.components.PopupPresence(editingLabelName) { target ->
        var name by remember(target.key) { mutableStateOf(target.title) }
        val id = target.key.removePrefix("label:").toLong()
        val valid = com.pixel.gallery.model.isValidLabelName(name) && labelSummaries.none {
            it.label.id != id && it.label.normalizedName == com.pixel.gallery.model.normalizeLabelName(name)
        }
        com.pixel.gallery.ui.components.GalleryDialog(
            onDismissRequest = { editingLabelName = null }, title = { Text(stringResource(R.string.labels_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.labels_name)) }) },
            confirmButton = { TextButton(enabled = valid, onClick = {
                labelsViewModel.renameLabel(id, name.trim()); coverTarget = coverTarget?.copy(title = name.trim())
                selectedCollection = selectedCollection?.copy(title = name.trim()); editingLabelName = null
            }) { Text(stringResource(R.string.labels_save)) } },
            dismissButton = { TextButton(onClick = { editingLabelName = null }) { Text(stringResource(R.string.labels_cancel)) } },
        )
    }
    com.pixel.gallery.ui.components.PopupPresence(renamingAlbum) { album ->
        AlbumRenameDialog(
            album = album,
            onDismiss = { renamingAlbum = null },
            onRename = { path, name ->
                photosViewModel.renameAlbum(path, name).also { result ->
                    if (result.isSuccess) {
                        selectedAlbumPaths = emptySet()
                        if (coverTarget?.key == albumCoverKey(path)) {
                            val renamedPath = java.io.File(java.io.File(path).parentFile, name.trim()).path
                            coverTarget = coverTarget?.copy(key = albumCoverKey(renamedPath), title = name.trim())
                        }
                    }
                }
            },
        )
    }



    // Custom sort dialog overlays
        val photoCriteria = remember {
            listOf(
                SortCriterion("DATE", "Date", "Oldest first", "Newest first"),
                SortCriterion("NAME", "Name", "A to Z", "Z to A"),
                SortCriterion("SIZE", "Size", "Smallest first", "Largest first")
            )
        }

        val trashCriteria = remember {
            listOf(
                SortCriterion("FILE_DATE", "File date", "Oldest first", "Newest first"),
                SortCriterion("DELETED_DATE", "Deleted", "Oldest first", "Newest first"),
                SortCriterion("NAME", "Name", "A to Z", "Z to A"),
                SortCriterion("SIZE", "Size", "Smallest first", "Largest first"),
            )
        }

        val currentTrashCriterion = when (trashSortOrder) {
            TrashSortOrder.FILE_DATE_DESC, TrashSortOrder.FILE_DATE_ASC -> "FILE_DATE"
            TrashSortOrder.DELETED_DATE_DESC, TrashSortOrder.DELETED_DATE_ASC -> "DELETED_DATE"
            TrashSortOrder.NAME_DESC, TrashSortOrder.NAME_ASC -> "NAME"
            TrashSortOrder.SIZE_DESC, TrashSortOrder.SIZE_ASC -> "SIZE"
        }
        val currentTrashDirection = when (trashSortOrder) {
            TrashSortOrder.FILE_DATE_DESC, TrashSortOrder.DELETED_DATE_DESC,
            TrashSortOrder.NAME_DESC, TrashSortOrder.SIZE_DESC -> "DESC"
            TrashSortOrder.FILE_DATE_ASC, TrashSortOrder.DELETED_DATE_ASC,
            TrashSortOrder.NAME_ASC, TrashSortOrder.SIZE_ASC -> "ASC"
        }
        SortDialog(
            visible = showTrashSortDialog,
            onDismissRequest = { showTrashSortDialog = false },
            title = "Sort Bin By",
            criteria = trashCriteria,
            initialCriterion = currentTrashCriterion,
            initialDirection = currentTrashDirection,
            onConfirm = { criterion, direction ->
                photosViewModel.setTrashSortOrder(TrashSortOrder.valueOf("${criterion}_${direction}"))
                showTrashSortDialog = false
            },
        )

        run {
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
                title = if (currentScreen == Screen.LockedFolder) "Sort Locked Files By" else "Sort Photos By",
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

        run {
            AlbumSortDialog(
                visible = showAlbumSortDialog,
                onDismissRequest = { showAlbumSortDialog = false; groupSortScope = null },
                title = if (groupSortScope == null) "Sort Albums By" else "Sort Group Albums By",
                initialOrder = groupSortScope?.let { albumGrouping.orderState(it).rule } ?: albumSortOrder,
                onConfirm = { order ->
                    if (groupSortScope != null) albumGroupsViewModel.setOrder(groupSortScope!!, order, orderingRows)
                    else {
                        if (order == AlbumSortOrder.CUSTOM) albumGroupsViewModel.startCustom(orderingRows)
                        photosViewModel.setAlbumSortOrder(order)
                    }
                    showAlbumSortDialog = false
                    groupSortScope = null
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
                    when (currentScreen) {
                        Screen.LockedFolder -> {
                            isVaultDeleteRunning = true
                            photosViewModel.deleteFromVaultBulk(selectedVaultEntries.map { it.contentId }) { result ->
                                isVaultDeleteRunning = false
                                selectedIds = result.failedIds.toSet()
                                val message = buildString {
                                    append("Deleted ${result.deletedIds.size} item")
                                    if (result.deletedIds.size != 1) append('s')
                                    if (result.failedIds.isNotEmpty()) append("; ${result.failedIds.size} failed")
                                }
                                messages.show(message)
                            }
                        }
                        Screen.Trash -> photosViewModel.deleteMediaBulk(selectedTrashEntries.map { it.uri })
                        else -> photosViewModel.deleteMediaBulk(selectedEntries.map { it.uri })
                    }
                } else {
                    photosViewModel.moveToTrashBulk(selectedEntries.map { it.uri })
                }
                if (currentScreen != Screen.LockedFolder) selectedIds = emptySet()
            }
        )
        }
        )
    }

    // Render alerts above the album chooser when the activity is visible.
    val quickConflict = quickQueue.needsConflictDecision
    com.pixel.gallery.ui.components.PopupPresence(quickConflict?.takeIf { lifecycleState == Lifecycle.State.RESUMED }) { quickConflict ->
        QuickSortConflictDialog(quickConflict,
            onResolve = { quickSortViewModel.resolveConflict(quickConflict.id, it) },
            onCancel = { quickSortViewModel.cancel(quickConflict.id); quickSortViewModel.resume() })
    }
    com.pixel.gallery.ui.components.PopupPresence(quickWarning?.takeIf { lifecycleState == Lifecycle.State.RESUMED && quickConflict == null }) { message ->
            QuickSortWarningDialog(message) { quickWarning = null }
    }
    com.pixel.gallery.ui.components.PopupPresence(quickFailure?.takeIf { lifecycleState == Lifecycle.State.RESUMED && quickConflict == null && quickWarning == null }) { failure ->
            QuickSortFailureDialog(failure) { quickSortViewModel.dismissFailure(failure) }
    }
}
