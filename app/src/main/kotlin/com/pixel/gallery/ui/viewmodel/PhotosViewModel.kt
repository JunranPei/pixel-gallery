package com.pixel.gallery.ui.viewmodel

import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.services.MetadataService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.pixel.gallery.model.Album
import com.pixel.gallery.model.AlbumSortOrder
import com.pixel.gallery.model.AlbumSorter
import com.pixel.gallery.model.compareNaturalNames
import com.pixel.gallery.model.chronologicalTimestamp
import com.pixel.gallery.model.isInsideMediaFolder
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.ConflictPolicy
import com.pixel.gallery.model.TransferMode
import com.pixel.gallery.model.TransferProgress
import com.pixel.gallery.model.TransferSummary
import com.pixel.gallery.model.buildTransferDestinations
import com.pixel.gallery.model.ThemeMode
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val metadataService: MetadataService
) : ViewModel() {

    data class TransferUiState(
        val isRunning: Boolean = false,
        val progress: TransferProgress? = null,
        val summary: TransferSummary? = null,
        val error: String? = null
    )

    private data class PendingTransfer(
        val entries: List<MediaEntry>,
        val destination: TransferDestination,
        val mode: TransferMode,
        val conflictPolicy: ConflictPolicy
    )

    sealed class GridItem {
        data class Header(val title: String, val timestamp: Long) : GridItem()
        data class Photo(val entry: MediaEntry) : GridItem()
    }

    data class ExternalMedia(val uri: String, val mimeType: String)

    private val _externalMedia = MutableStateFlow<ExternalMedia?>(null)
    val externalMedia: StateFlow<ExternalMedia?> = _externalMedia

    fun setExternalMediaUri(uri: String?, mimeType: String? = null) {
        if (uri != null) {
            _externalMedia.value = ExternalMedia(uri, mimeType ?: "image/*")
        } else {
            _externalMedia.value = null
        }
    }

    fun clearExternalMediaUri() {
        _externalMedia.value = null
    }

    private val resumedState = MutableStateFlow(false)
    private val browseTimeZone = MutableStateFlow(java.util.TimeZone.getDefault().id)

    val allPhotos: StateFlow<List<MediaEntry>> = repository.allEntries

    // Wait for saved visibility before publishing media; an empty placeholder
    // could briefly expose hidden files while preferences are loading.
    private val hiddenFolders: Flow<Set<String>> = settingsRepository.hiddenFolders

    val photoSortOrder: StateFlow<PhotoSortOrder> = settingsRepository.photoSortOrder
        .map { runCatching { PhotoSortOrder.valueOf(it) }.getOrDefault(PhotoSortOrder.DATE_DESC) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotoSortOrder.DATE_DESC)

    val trashSortOrder: StateFlow<TrashSortOrder> = settingsRepository.trashSortOrder
        .map { runCatching { TrashSortOrder.valueOf(it) }.getOrDefault(TrashSortOrder.FILE_DATE_DESC) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrashSortOrder.FILE_DATE_DESC)

    val albumSortOrder: StateFlow<AlbumSortOrder> = settingsRepository.albumSortOrder
        .map { runCatching { AlbumSortOrder.valueOf(it) }.getOrDefault(AlbumSortOrder.NAME_ASC) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlbumSortOrder.NAME_ASC)

    private data class OrderedMedia(val entries: List<MediaEntry>, val sort: PhotoSortOrder)

    private val orderedMedia = resumedState.flatMapLatest { resumed ->
        if (!resumed) emptyFlow() else combine(allPhotos, hiddenFolders, photoSortOrder) { all, hidden, sort ->
            val visible = all.filter { entry -> hidden.none { entry.path.isInsideMediaFolder(it) } }
            OrderedMedia(sortMedia(visible, sort), sort)
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, OrderedMedia(emptyList(), PhotoSortOrder.DATE_DESC))

    val photos: StateFlow<List<MediaEntry>> = orderedMedia.map { it.entries }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Hidden browsing uses the original records without exposing them to normal
    // library, label, favourite or transfer-destination lists.
    val hiddenPhotos: StateFlow<List<MediaEntry>> = combine(allPhotos, hiddenFolders) { entries, hidden ->
        entries.filter { entry -> hidden.any { entry.path.isInsideMediaFolder(it) } }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Keeps ordering and time headers on the same effective timestamp. */
    fun sortMedia(entries: List<MediaEntry>, sort: PhotoSortOrder): List<MediaEntry> =
        sortMediaEntries(entries, sort)

    val transferDestinations: StateFlow<List<TransferDestination>> = photos
        .map(::buildTransferDestinations)
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val quickSortPreferences = settingsRepository.quickSortPreferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.pixel.gallery.model.QuickSortPreferences())

    fun setQuickSortTargets(targets: List<TransferDestination>) {
        viewModelScope.launch { settingsRepository.setQuickSortTargets(targets) }
    }

    fun setQuickSortDockTop(top: Boolean) {
        viewModelScope.launch { settingsRepository.setQuickSortDockTop(top) }
    }

    fun setQuickSortAlbumRows(rows: Int) {
        viewModelScope.launch { settingsRepository.setQuickSortAlbumRows(rows) }
    }

    private val _transferUiState = MutableStateFlow(TransferUiState())
    val transferUiState: StateFlow<TransferUiState> = _transferUiState.asStateFlow()
    private var pendingTransfer: PendingTransfer? = null

    fun requestTransfer(
        entries: List<MediaEntry>,
        destination: TransferDestination,
        mode: TransferMode,
        conflictPolicy: ConflictPolicy,
        onPermissionRequired: (IntentSenderRequest) -> Unit
    ) {
        if (entries.isEmpty() || _transferUiState.value.isRunning || pendingTransfer != null) return
        _transferUiState.value = TransferUiState()
        val request = runCatching {
            if (mode == TransferMode.MOVE) repository.createTransferWriteRequest(entries) else null
        }.getOrElse { error ->
            _transferUiState.value = TransferUiState(
                error = error.message ?: "Could not request storage permission"
            )
            return
        }
        if (request != null) {
            pendingTransfer = PendingTransfer(entries, destination, mode, conflictPolicy)
            onPermissionRequired(request)
        } else {
            executeTransfer(PendingTransfer(entries, destination, mode, conflictPolicy))
        }
    }

    fun onTransferPermissionResult(
        granted: Boolean,
        onPermissionRequired: (IntentSenderRequest) -> Unit
    ) {
        val request = pendingTransfer
        if (!granted || request == null) {
            pendingTransfer = null
            _transferUiState.value = TransferUiState(error = "Storage permission was not granted")
            return
        }

        // Android 10 grants recoverable write access one item at a time. Recheck
        // the batch and request the next item before starting any mutations.
        if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q) {
            val nextPermission = runCatching {
                repository.createTransferWriteRequest(request.entries)
            }.getOrElse { error ->
                pendingTransfer = null
                _transferUiState.value = TransferUiState(
                    error = error.message ?: "Could not request storage permission"
                )
                return
            }
            if (nextPermission != null) {
                onPermissionRequired(nextPermission)
                return
            }
        }

        pendingTransfer = null
        executeTransfer(request)
    }

    private fun executeTransfer(request: PendingTransfer) {
        _transferUiState.value = TransferUiState(isRunning = true)
        viewModelScope.launch {
            try {
                val summary = repository.transferMedia(
                    entries = request.entries,
                    destination = request.destination,
                    mode = request.mode,
                    conflictPolicy = request.conflictPolicy
                ) { progress ->
                    _transferUiState.value = TransferUiState(isRunning = true, progress = progress)
                }
                _transferUiState.value = TransferUiState(summary = summary)
            } catch (error: Exception) {
                _transferUiState.value = TransferUiState(error = error.message ?: "Transfer failed")
            }
        }
    }

    fun createTransferFolder(
        parent: TransferDestination,
        name: String,
        onResult: (Result<TransferDestination>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(repository.createTransferFolder(parent, name))
        }
    }

    fun clearTransferState() {
        pendingTransfer = null
        _transferUiState.value = TransferUiState()
    }

    fun groupMedia(entries: List<MediaEntry>, columns: Int = 3, sortOrder: PhotoSortOrder = PhotoSortOrder.DATE_DESC): List<GridItem> {
        // Album, favourites, trash and vault all pass through here. Sorting here
        // prevents date headers from being generated against a different order.
        val sortedEntries = sortMedia(entries, sortOrder)
        if (sortOrder != PhotoSortOrder.DATE_DESC && sortOrder != PhotoSortOrder.DATE_ASC) {
            return sortedEntries.map { GridItem.Photo(it) }
        }
        return groupOrderedMedia(sortedEntries, columns, sortOrder)
    }

    val gridColumns: StateFlow<Int> = settingsRepository.gridColumns
        .stateIn(viewModelScope, SharingStarted.Eagerly, 3)

    val albumGridColumns: StateFlow<Int> = settingsRepository.albumGridColumns
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2)

    internal val browsingIndex: StateFlow<MediaBrowseIndex> = combine(
        orderedMedia, gridColumns.map { it >= 6 }.distinctUntilChanged(), browseTimeZone,
    ) { media, monthly, zone -> Triple(media, monthly, zone) }.mapLatest { (media, monthly, zone) ->
        withContext(Dispatchers.Default) {
            val taskContext = coroutineContext
            MediaBrowseIndex.build(media.entries, if (monthly) 6 else 3, media.sort,
                timeZone = java.util.TimeZone.getTimeZone(zone), checkCancelled = { taskContext.ensureActive() })
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MediaBrowseIndex.Empty)

    val groupedPhotos: StateFlow<List<GridItem>> = browsingIndex.map {
        it.library[com.pixel.gallery.model.MediaTypeFilter.ALL].media.gridItems
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val hiddenBrowsingIndex: StateFlow<MediaBrowseIndex> = combine(
        hiddenPhotos, photoSortOrder, gridColumns.map { it >= 6 }.distinctUntilChanged(), browseTimeZone,
    ) { entries, sort, monthly, zone ->
        Triple(OrderedMedia(entries, sort), monthly, zone)
    }.mapLatest { (media, monthly, zone) ->
        withContext(Dispatchers.Default) {
            val taskContext = coroutineContext
            MediaBrowseIndex.build(sortMedia(media.entries, media.sort), if (monthly) 6 else 3, media.sort,
                timeZone = java.util.TimeZone.getTimeZone(zone), checkCancelled = { taskContext.ensureActive() })
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MediaBrowseIndex.Empty)

    val favourites: StateFlow<List<MediaEntry>> = resumedState.flatMapLatest { resumed ->
        if (resumed) repository.favourites else emptyFlow()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groupedFavourites: StateFlow<List<GridItem>> = combine(favourites, gridColumns, photoSortOrder) { media, cols, sort ->
        groupMedia(media, cols, sort)
    }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val trashedMedia: StateFlow<List<MediaEntry>> = resumedState.flatMapLatest { resumed ->
        if (resumed) repository.trash else emptyFlow()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groupedTrashedMedia: StateFlow<List<GridItem>> = combine(trashedMedia, gridColumns, trashSortOrder) { media, cols, sort ->
        groupTrashMedia(media, cols, sort)
    }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val vaultEntries: StateFlow<List<MediaEntry>> = resumedState.flatMapLatest { resumed ->
        if (resumed) repository.vaultEntries else emptyFlow()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groupedVaultEntries: StateFlow<List<GridItem>> = combine(vaultEntries, gridColumns, photoSortOrder) { media, cols, sort ->
        groupMedia(media, cols, sort)
    }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val startupAtAlbums: StateFlow<Boolean> = settingsRepository.startupAtAlbums
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showFullAlbumNames: StateFlow<Boolean> = settingsRepository.showFullAlbumNames
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val materialYou: StateFlow<Boolean> = settingsRepository.materialYou
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val handedness = settingsRepository.handedness
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.pixel.gallery.model.Handedness.RIGHT)

    fun setHandedness(value: com.pixel.gallery.model.Handedness) {
        viewModelScope.launch { settingsRepository.setHandedness(value) }
    }

    val glideThreadCount: StateFlow<Int> = settingsRepository.glideThreadCount
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2)
 
    val glideCacheSize: StateFlow<Int> = settingsRepository.glideCacheSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 250)

    val glidePersistentCacheSize: StateFlow<Int> = settingsRepository.glidePersistentCacheSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 250)

    val glidePersistentGridCacheSize: StateFlow<Int> = settingsRepository.glidePersistentGridCacheSize
        .stateIn(viewModelScope, SharingStarted.Eagerly, 250)

    val excludedFolders: StateFlow<Set<String>> = settingsRepository.excludedFolders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())


    fun getParentFolderName(path: String): String {
        if (path.isEmpty()) return "Unknown"
        val lastSlash = path.lastIndexOf('/')
        if (lastSlash <= 0) return "Unknown"
        val parentPath = path.substring(0, lastSlash)
        val secondLastSlash = parentPath.lastIndexOf('/')
        return if (secondLastSlash >= 0) {
            parentPath.substring(secondLastSlash + 1)
        } else {
            parentPath
        }
    }

    val albums: StateFlow<List<Album>> = combine(browsingIndex, albumSortOrder) { index, sort ->
        index.library[com.pixel.gallery.model.MediaTypeFilter.ALL].albums(sort)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hiddenAlbums: StateFlow<List<Album>> = combine(
        allPhotos,
        settingsRepository.hiddenFolderRoots,
        albumSortOrder
    ) { all, hidden, sort ->
        // Saved roots must remain manageable even when empty or not indexed.
        // Keep the exact saved path so Unhide removes the matching setting.
        val grouped = hidden.map { root ->
            val entries = all.filter { it.path.isInsideMediaFolder(root) }
            Album(
                name = java.io.File(root).name.ifBlank { root },
                path = root,
                coverUri = entries.firstOrNull()?.uri.orEmpty(),
                itemCount = entries.size,
                lastModified = entries.maxOfOrNull { it.chronologicalTimestamp() } ?: 0L,
            )
        }
        AlbumSorter.shared.sortAlbums(grouped, sort)
    }
    .flowOn(kotlinx.coroutines.Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var contentObserver: android.database.ContentObserver? = null

    fun setResumed(resumed: Boolean) {
        if (resumed) browseTimeZone.value = java.util.TimeZone.getDefault().id
        resumedState.value = resumed
        if (resumed) {
            refresh()
        }
    }

    init {
        registerContentObserver()
    }

    private fun registerContentObserver() {
        contentObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                if (resumedState.value && !repository.isMediaMutationInProgress()) {
                    // MediaStore commonly emits several callbacks for one committed file.
                    // A short trailing delay coalesces them without polling.
                    refresh(delayMillis = 120L)
                }
            }
        }
        val resolver = repository.getContentResolver()
        resolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, contentObserver!!)
        resolver.registerContentObserver(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, contentObserver!!)
    }

    override fun onCleared() {
        super.onCleared()
        contentObserver?.let {
            repository.getContentResolver().unregisterContentObserver(it)
        }
    }

    private var syncJob: kotlinx.coroutines.Job? = null
    private var refreshAgain = false
    private var queuedRefreshDelayMillis = 0L

    fun refresh(delayMillis: Long = 0) {
        val requestedDelay = delayMillis.coerceAtLeast(0L)
        if (syncJob?.isActive == true) {
            refreshAgain = true
            queuedRefreshDelayMillis = maxOf(queuedRefreshDelayMillis, requestedDelay)
            return
        }
        syncJob = viewModelScope.launch {
            var nextDelay = requestedDelay
            do {
                refreshAgain = false
                if (nextDelay > 0L) kotlinx.coroutines.delay(nextDelay)
                // A second task can resume while another one is still moving files.
                // Wait for that atomic batch instead of scanning its intermediate state.
                runCatching { repository.syncWhenMediaMutationsIdle() }
                    .onFailure { error ->
                        android.util.Log.e("PhotosViewModel", "MediaStore refresh failed", error)
                    }
                nextDelay = queuedRefreshDelayMillis
                queuedRefreshDelayMillis = 0L
            } while (refreshAgain)
        }
    }

    // --- Actions ---
    suspend fun renameAlbum(path: String, name: String): Result<Unit> = try {
        repository.renameAlbum(path, name)
        refresh()
        Result.success(Unit)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun toggleFavourite(id: Long, isCurrentlyFavourite: Boolean, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                if (isCurrentlyFavourite) repository.removeFavourite(id) else repository.addFavourite(id)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error.message ?: "Could not update favourite")
            }
        }
    }

    fun isFavourite(id: Long): Flow<Boolean> = repository.isFavourite(id)

    fun moveToTrash(id: Long, uri: String, path: String) {
        viewModelScope.launch {
            runCatching { repository.trashMedia(id, uri, path) }
                .onSuccess { if (it) refresh() }
                .onFailure { android.util.Log.e("PhotosViewModel", "Could not trash media", it) }
        }
    }

    fun moveToTrashBulk(uris: List<String>) {
        viewModelScope.launch {
            runCatching { repository.trashMediaBulk(uris) }
                .onSuccess { if (it) refresh() }
                .onFailure { android.util.Log.e("PhotosViewModel", "Could not trash media", it) }
        }
    }

    fun restoreMedia(id: Long, uri: String) {
        viewModelScope.launch {
            runCatching { repository.restoreMedia(id, uri) }
                .onSuccess { if (it) refresh() }
                .onFailure { android.util.Log.e("PhotosViewModel", "Could not restore media", it) }
        }
    }

    fun restoreMediaBulk(uris: List<String>) {
        viewModelScope.launch {
            runCatching { repository.restoreMediaBulk(uris) }
                .onSuccess { if (it) refresh() }
                .onFailure { android.util.Log.e("PhotosViewModel", "Could not restore media", it) }
        }
    }

    fun moveToVault(entry: MediaEntry) {
        viewModelScope.launch {
            if (repository.moveToVault(entry)) {
                refresh()
            }
        }
    }

    fun restoreFromVault(id: Long, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val restored = runCatching { repository.restoreFromVault(id) }
                .onFailure { android.util.Log.e("PhotosViewModel", "Could not restore locked media", it) }
                .getOrDefault(false)
            if (restored) {
                refresh()
            }
            onComplete(restored)
        }
    }

    fun restoreFromVaultBulk(
        ids: List<Long>,
        onComplete: (MediaRepository.VaultRestoreResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = repository.restoreFromVaultBulk(ids)
            if (result.restoredIds.isNotEmpty()) {
                refresh()
            }
            onComplete(result)
        }
    }

    fun deleteFromVaultBulk(
        ids: List<Long>,
        onComplete: (MediaRepository.VaultDeleteResult) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = repository.deleteVaultBulk(ids)
            if (result.deletedIds.isNotEmpty()) refresh()
            onComplete(result)
        }
    }

    fun deleteMediaBulk(uris: List<String>) {
        viewModelScope.launch {
            runCatching { repository.deleteMediaBulk(uris) }
                .onSuccess { if (it) refresh() }
                .onFailure { android.util.Log.e("PhotosViewModel", "Could not delete media", it) }
        }
    }

    // --- Metadata ---
    fun getMediaMetadata(path: String) = metadataService.getMetadata(path)
    fun getCoordinates(path: String) = metadataService.getCoordinates(path)
    fun inspectViewerPhoto(path: String) = metadataService.inspectViewerPhoto(path)
    fun extractMotionVideo(path: String) = metadataService.extractMotionVideo(path)


    // --- Settings Actions ---
    fun setShowFullAlbumNames(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowFullAlbumNames(value) }
    }

    fun setStartupAtAlbums(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.setStartupAtAlbums(value)
        }
    }

    fun setMaterialYou(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.setMaterialYou(value)
        }
    }

    fun setThemeMode(value: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(value)
        }
    }

    fun setGlideThreadCount(value: Int) {
        viewModelScope.launch {
            settingsRepository.setGlideThreadCount(value)
        }
    }
 
    fun setGlideCacheSize(value: Int) {
        viewModelScope.launch {
            settingsRepository.setGlideCacheSize(value)
        }
    }

    fun setGlidePersistentCacheSize(value: Int) {
        viewModelScope.launch {
            settingsRepository.setGlidePersistentCacheSize(value)
        }
    }

    fun setGlidePersistentGridCacheSize(value: Int) {
        viewModelScope.launch {
            settingsRepository.setGlidePersistentGridCacheSize(value)
        }
    }

    fun clearAllCaches(context: android.content.Context, onComplete: () -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.bumptech.glide.Glide.get(context).clearDiskCache()
            } catch (e: Exception) {
                // ignore
            }
            try {
                val dirs = listOf(
                    "small_grid_thumbnails",
                    "large_grid_thumbnails",
                    "small_viewer_thumbnails",
                    "large_viewer_thumbnails",
                    "persistent_grid_thumbnails",
                    "persistent_viewer_thumbnails",
                    "persistent_thumbnails",
                    "persistent_thumbnails_v2",
                    "persistent_thumbnails_v3",
                    "ssiv_tile_cache"
                )
                for (dirName in dirs) {
                    val dir = java.io.File(context.cacheDir, dirName)
                    if (dir.exists()) {
                        dir.deleteRecursively()
                    }
                }
                com.pixel.gallery.ui.viewer.decoders.resetSsivTileCacheBudget(context)
            } catch (e: Exception) {
                // ignore
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    com.bumptech.glide.Glide.get(context).clearMemory()
                } catch (e: Exception) {
                    // ignore
                }
                onComplete()
            }
        }
    }

    fun addExcludedFolder(path: String) {
        viewModelScope.launch {
            settingsRepository.addExcludedFolder(path)
        }
    }

    fun removeExcludedFolder(path: String) {
        viewModelScope.launch {
            settingsRepository.removeExcludedFolder(path)
        }
    }

    fun addHiddenFolder(path: String) {
        viewModelScope.launch {
            settingsRepository.addHiddenFolder(path)
        }
    }

    fun removeHiddenFolder(path: String) {
        viewModelScope.launch {
            settingsRepository.removeHiddenFolder(path)
        }
    }

    fun removeHiddenFolders(paths: Set<String>) {
        viewModelScope.launch { settingsRepository.removeHiddenFolders(paths) }
    }

    fun setGridColumns(value: Int) {
        viewModelScope.launch {
            settingsRepository.setGridColumns(value)
        }
    }

    fun setAlbumGridColumns(value: Int) {
        viewModelScope.launch {
            settingsRepository.setAlbumGridColumns(value)
        }
    }

    fun setPhotoSortOrder(order: PhotoSortOrder) {
        viewModelScope.launch {
            settingsRepository.setPhotoSortOrder(order.name)
        }
    }

    fun setTrashSortOrder(order: TrashSortOrder) {
        viewModelScope.launch {
            settingsRepository.setTrashSortOrder(order.name)
        }
    }

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        viewModelScope.launch {
            settingsRepository.setAlbumSortOrder(order.name)
        }
    }

}

enum class PhotoSortOrder {
    DATE_DESC,
    DATE_ASC,
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC,
    SIZE_ASC
}

enum class TrashSortOrder {
    FILE_DATE_DESC,
    FILE_DATE_ASC,
    DELETED_DATE_DESC,
    DELETED_DATE_ASC,
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC,
    SIZE_ASC,
}

private val MEDIA_DATE_DESCENDING =
    compareByDescending<MediaEntry> { it.chronologicalTimestamp() }
        .thenByDescending { it.dateModifiedMillis }
        .thenByDescending { it.contentId }

private val MEDIA_DATE_ASCENDING =
    compareBy<MediaEntry> { it.chronologicalTimestamp() }
        .thenBy { it.dateModifiedMillis }
        .thenBy { it.contentId }

/** Build album metadata exclusively from the entries currently being browsed. */
internal fun buildMediaAlbums(photosList: List<MediaEntry>, sort: AlbumSortOrder): List<Album> {
    val grouped = photosList.groupBy { java.io.File(it.path).parent.orEmpty() }
        .map { (parentPath, entries) ->
        val firstEntry = entries.first()
        val name = java.io.File(parentPath).name.ifBlank { "Unknown" }
        val lastModified = entries.maxOfOrNull { it.chronologicalTimestamp() } ?: 0L
        Album(name, parentPath, firstEntry.uri, entries.size, lastModified)
    }
    return sortMediaAlbums(grouped, sort)
}

internal fun sortMediaAlbums(grouped: List<Album>, sort: AlbumSortOrder): List<Album> =
    AlbumSorter.shared.sortAlbums(grouped, sort)

internal class MediaSorter private constructor() {
    fun sortMedia(entries: List<MediaEntry>, sort: PhotoSortOrder): List<MediaEntry> = when (sort) {
        PhotoSortOrder.DATE_DESC -> entries.sortedWith(MEDIA_DATE_DESCENDING)
        PhotoSortOrder.DATE_ASC -> entries.sortedWith(MEDIA_DATE_ASCENDING)
        PhotoSortOrder.NAME_ASC -> entries.sortedWith { a, b -> compareNaturalNames(a.sortName(), b.sortName()) }
        PhotoSortOrder.NAME_DESC -> entries.sortedWith { a, b -> compareNaturalNames(b.sortName(), a.sortName()) }
        PhotoSortOrder.SIZE_DESC -> entries.sortedWith(compareByDescending<MediaEntry> { it.sizeBytes }.thenByDescending { it.contentId })
        PhotoSortOrder.SIZE_ASC -> entries.sortedWith(compareBy<MediaEntry> { it.sizeBytes }.thenBy { it.contentId })
    }

    fun sortTrash(entries: List<MediaEntry>, sort: TrashSortOrder): List<MediaEntry> = when (sort) {
        TrashSortOrder.FILE_DATE_DESC -> sortMedia(entries, PhotoSortOrder.DATE_DESC)
        TrashSortOrder.FILE_DATE_ASC -> sortMedia(entries, PhotoSortOrder.DATE_ASC)
        TrashSortOrder.DELETED_DATE_DESC -> entries.sortedWith(
            compareBy<MediaEntry> { it.trashedAtMillis <= 0L }
                .thenByDescending { it.trashedAtMillis }
                .thenByDescending { it.contentId },
        )
        TrashSortOrder.DELETED_DATE_ASC -> entries.sortedWith(
            compareBy<MediaEntry> { it.trashedAtMillis <= 0L }
                .thenBy { it.trashedAtMillis }
                .thenBy { it.contentId },
        )
        TrashSortOrder.NAME_ASC -> sortMedia(entries, PhotoSortOrder.NAME_ASC)
        TrashSortOrder.NAME_DESC -> sortMedia(entries, PhotoSortOrder.NAME_DESC)
        TrashSortOrder.SIZE_DESC -> sortMedia(entries, PhotoSortOrder.SIZE_DESC)
        TrashSortOrder.SIZE_ASC -> sortMedia(entries, PhotoSortOrder.SIZE_ASC)
    }

    private fun MediaEntry.sortName(): String {
        val name = java.io.File(vaultOriginalPath ?: path).name
        return if (isTrashed && vaultOriginalPath == null) name.replaceFirst(TRASH_FILENAME_PREFIX, "") else name
    }

    companion object {
        private val TRASH_FILENAME_PREFIX = Regex("^\\.trashed-[0-9]+-")
        val shared = MediaSorter()
    }
}

internal fun sortMediaEntries(entries: List<MediaEntry>, sort: PhotoSortOrder): List<MediaEntry> =
    MediaSorter.shared.sortMedia(entries, sort)

internal fun sortTrashEntries(entries: List<MediaEntry>, sort: TrashSortOrder): List<MediaEntry> =
    MediaSorter.shared.sortTrash(entries, sort)

private fun groupTrashMedia(entries: List<MediaEntry>, columns: Int, sort: TrashSortOrder): List<PhotosViewModel.GridItem> {
    val ordered = sortTrashEntries(entries, sort)
    val dateSort = when (sort) {
        TrashSortOrder.FILE_DATE_ASC, TrashSortOrder.DELETED_DATE_ASC -> PhotoSortOrder.DATE_ASC
        TrashSortOrder.FILE_DATE_DESC, TrashSortOrder.DELETED_DATE_DESC -> PhotoSortOrder.DATE_DESC
        else -> null
    }
    return if (dateSort == null) ordered.map { PhotosViewModel.GridItem.Photo(it) }
    else groupOrderedMedia(
        ordered,
        columns,
        dateSort,
        timestampOf = if (sort == TrashSortOrder.DELETED_DATE_ASC || sort == TrashSortOrder.DELETED_DATE_DESC) {
            { it.trashedAtMillis }
        } else {
            { it.chronologicalTimestamp() }
        },
    )
}
