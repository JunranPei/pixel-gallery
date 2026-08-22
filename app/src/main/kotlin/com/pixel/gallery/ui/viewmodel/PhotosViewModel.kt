package com.pixel.gallery.ui.viewmodel

import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.utils.CustomShortcut
import com.pixel.gallery.services.MetadataService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.pixel.gallery.model.Album
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.ConflictPolicy
import com.pixel.gallery.model.TransferMode
import com.pixel.gallery.model.TransferProgress
import com.pixel.gallery.model.TransferSummary
import com.pixel.gallery.model.buildTransferDestinations
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

    val allPhotos: StateFlow<List<MediaEntry>> = repository.allEntries

    val hiddenFolders: StateFlow<Set<String>> = settingsRepository.hiddenFolders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val photoSortOrder: StateFlow<PhotoSortOrder> = settingsRepository.photoSortOrder
        .map { runCatching { PhotoSortOrder.valueOf(it) }.getOrDefault(PhotoSortOrder.DATE_DESC) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotoSortOrder.DATE_DESC)

    val albumSortOrder: StateFlow<AlbumSortOrder> = settingsRepository.albumSortOrder
        .map { runCatching { AlbumSortOrder.valueOf(it) }.getOrDefault(AlbumSortOrder.NAME_ASC) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlbumSortOrder.NAME_ASC)

    val photos: StateFlow<List<MediaEntry>> = resumedState.flatMapLatest { resumed ->
        if (!resumed) {
            emptyFlow()
        } else {
            combine(allPhotos, hiddenFolders, photoSortOrder) { all, hidden, sort ->
                val filtered = all.filter { entry ->
                    !hidden.any { entry.path.startsWith(it) }
                }
                sortMedia(filtered, sort)
            }
        }
    }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Keeps ordering and time headers on the same effective timestamp. */
    fun sortMedia(entries: List<MediaEntry>, sort: PhotoSortOrder): List<MediaEntry> = when (sort) {
        PhotoSortOrder.DATE_DESC -> entries.sortedWith(MEDIA_DATE_DESCENDING)
        PhotoSortOrder.DATE_ASC -> entries.sortedWith(MEDIA_DATE_ASCENDING)
        PhotoSortOrder.NAME_ASC -> entries.sortedWith { e1, e2 -> CASE_INSENSITIVE_NATURAL_ORDER.compare(java.io.File(e1.path).name, java.io.File(e2.path).name) }
        PhotoSortOrder.NAME_DESC -> entries.sortedWith { e1, e2 -> CASE_INSENSITIVE_NATURAL_ORDER.compare(java.io.File(e2.path).name, java.io.File(e1.path).name) }
        PhotoSortOrder.SIZE_DESC -> entries.sortedByDescending { it.sizeBytes }
        PhotoSortOrder.SIZE_ASC -> entries.sortedBy { it.sizeBytes }
    }

    val transferDestinations: StateFlow<List<TransferDestination>> = photos
        .map(::buildTransferDestinations)
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        if (entries.isEmpty() || _transferUiState.value.isRunning) return
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
                if (summary.completedAny) refresh()
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
        val items = mutableListOf<GridItem>()
        var lastHeader = ""
        val format = if (columns >= 6) "MMMM yyyy" else "MMMM d, yyyy"
        val sdf = java.text.SimpleDateFormat(format, java.util.Locale.US)
        
        sortedEntries.forEach { entry ->
            val timestamp = entry.chronologicalTimestamp()
            val date = java.util.Date(timestamp)
            val header = sdf.format(date)
            if (header != lastHeader) {
                items.add(GridItem.Header(header, timestamp))
                lastHeader = header
            }
            items.add(GridItem.Photo(entry))
        }
        return items
    }

    val gridColumns: StateFlow<Int> = settingsRepository.gridColumns
        .stateIn(viewModelScope, SharingStarted.Eagerly, 3)

    val albumGridColumns: StateFlow<Int> = settingsRepository.albumGridColumns
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2)

    val groupedPhotos: StateFlow<List<GridItem>> = combine(photos, gridColumns, photoSortOrder) { media, cols, sort ->
        groupMedia(media, cols, sort)
    }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    val groupedTrashedMedia: StateFlow<List<GridItem>> = combine(trashedMedia, gridColumns, photoSortOrder) { media, cols, sort ->
        groupMedia(media, cols, sort)
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

    val materialYou: StateFlow<Boolean> = settingsRepository.materialYou
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

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

    val albums: StateFlow<List<Album>> = combine(
        photos,
        albumSortOrder
    ) { photosList, sort ->
        val grouped = photosList.groupBy { 
            getParentFolderName(it.path)
        }.map { (name, entries) ->
            val firstEntry = entries.first()
            val parentPath = if (firstEntry.path.lastIndexOf('/') > 0) firstEntry.path.substring(0, firstEntry.path.lastIndexOf('/')) else ""
            val lastModified = entries.maxOfOrNull { it.chronologicalTimestamp() } ?: 0L
            Album(name, parentPath, firstEntry.uri, entries.size, lastModified)
        }
        when (sort) {
            AlbumSortOrder.NAME_ASC -> grouped.sortedWith { a1, a2 -> CASE_INSENSITIVE_NATURAL_ORDER.compare(a1.name, a2.name) }
            AlbumSortOrder.NAME_DESC -> grouped.sortedWith { a1, a2 -> CASE_INSENSITIVE_NATURAL_ORDER.compare(a2.name, a1.name) }
            AlbumSortOrder.COUNT_DESC -> grouped.sortedByDescending { it.itemCount }
            AlbumSortOrder.COUNT_ASC -> grouped.sortedBy { it.itemCount }
            AlbumSortOrder.DATE_DESC -> grouped.sortedByDescending { it.lastModified }
            AlbumSortOrder.DATE_ASC -> grouped.sortedBy { it.lastModified }
        }
    }
    .flowOn(kotlinx.coroutines.Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hiddenAlbums: StateFlow<List<Album>> = combine(
        allPhotos,
        hiddenFolders,
        albumSortOrder
    ) { all, hidden, sort ->
        val grouped = all.filter { entry ->
            hidden.any { entry.path.startsWith(it) }
        }.groupBy { 
            getParentFolderName(it.path)
        }.map { (name, entries) ->
            val firstEntry = entries.first()
            val parentPath = if (firstEntry.path.lastIndexOf('/') > 0) firstEntry.path.substring(0, firstEntry.path.lastIndexOf('/')) else ""
            val lastModified = entries.maxOfOrNull { it.chronologicalTimestamp() } ?: 0L
            Album(name, parentPath, firstEntry.uri, entries.size, lastModified)
        }
        when (sort) {
            AlbumSortOrder.NAME_ASC -> grouped.sortedWith { a1, a2 -> CASE_INSENSITIVE_NATURAL_ORDER.compare(a1.name, a2.name) }
            AlbumSortOrder.NAME_DESC -> grouped.sortedWith { a1, a2 -> CASE_INSENSITIVE_NATURAL_ORDER.compare(a2.name, a1.name) }
            AlbumSortOrder.COUNT_DESC -> grouped.sortedByDescending { it.itemCount }
            AlbumSortOrder.COUNT_ASC -> grouped.sortedBy { it.itemCount }
            AlbumSortOrder.DATE_DESC -> grouped.sortedByDescending { it.lastModified }
            AlbumSortOrder.DATE_ASC -> grouped.sortedBy { it.lastModified }
        }
    }
    .flowOn(kotlinx.coroutines.Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var contentObserver: android.database.ContentObserver? = null

    fun setResumed(resumed: Boolean) {
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
    fun toggleFavourite(id: Long, isCurrentlyFavourite: Boolean) {
        viewModelScope.launch {
            if (isCurrentlyFavourite) {
                repository.removeFavourite(id)
            } else {
                repository.addFavourite(id)
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

    fun restoreFromVault(id: Long) {
        viewModelScope.launch {
            if (repository.restoreFromVault(id)) {
                refresh()
            }
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

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        viewModelScope.launch {
            settingsRepository.setAlbumSortOrder(order.name)
        }
    }

    val autoCloneEnabled: StateFlow<Boolean> = settingsRepository.autoCloneEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAutoCloneEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoCloneEnabled(enabled)
        }
    }

    val customShortcuts: StateFlow<List<CustomShortcut>> = settingsRepository.customShortcuts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addCustomShortcut(shortcut: CustomShortcut) {
        viewModelScope.launch { settingsRepository.addCustomShortcut(shortcut) }
    }

    fun removeCustomShortcut(id: String) {
        viewModelScope.launch { settingsRepository.removeCustomShortcut(id) }
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

enum class AlbumSortOrder {
    NAME_ASC,
    NAME_DESC,
    COUNT_DESC,
    COUNT_ASC,
    DATE_DESC,
    DATE_ASC
}

private val CASE_INSENSITIVE_NATURAL_ORDER = Comparator<String> { s1, s2 ->
    val len1 = s1.length
    val len2 = s2.length
    val minLen = minOf(len1, len2)
    for (i in 0 until minLen) {
        val c1 = s1[i]
        val c2 = s2[i]
        if (c1 != c2) {
            val u1 = c1.uppercaseChar()
            val u2 = c2.uppercaseChar()
            if (u1 != u2) {
                return@Comparator u1.compareTo(u2)
            } else {
                return@Comparator if (c1.isUpperCase()) -1 else 1
            }
        }
    }
    len1.compareTo(len2)
}

private fun MediaEntry.chronologicalTimestamp(): Long {
    val addedMillis = dateAddedSecs.takeIf { it > 0L }?.times(1000L) ?: 0L
    val scannedTimestamp = bestTimestamp.takeIf {
        it > 0L && (addedMillis == 0L || it != addedMillis)
    }
    return sourceDateTakenMillis?.takeIf { it > 0L }
        ?: scannedTimestamp
        ?: dateModifiedMillis.takeIf { it > 0L }
        ?: addedMillis
}

private val MEDIA_DATE_DESCENDING =
    compareByDescending<MediaEntry> { it.chronologicalTimestamp() }
        .thenByDescending { it.dateModifiedMillis }
        .thenByDescending { it.contentId }

private val MEDIA_DATE_ASCENDING =
    compareBy<MediaEntry> { it.chronologicalTimestamp() }
        .thenBy { it.dateModifiedMillis }
        .thenBy { it.contentId }
