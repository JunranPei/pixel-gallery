package com.pixel.gallery.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.services.MetadataService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.pixel.gallery.model.Album
import javax.inject.Inject

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val metadataService: MetadataService
) : ViewModel() {

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

    val allPhotos: StateFlow<List<MediaEntry>> = repository.allEntries
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val hiddenFolders: StateFlow<Set<String>> = settingsRepository.hiddenFolders
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    val photoSortOrder: StateFlow<PhotoSortOrder> = settingsRepository.photoSortOrder
        .map { runCatching { PhotoSortOrder.valueOf(it) }.getOrDefault(PhotoSortOrder.DATE_DESC) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotoSortOrder.DATE_DESC)

    val albumSortOrder: StateFlow<AlbumSortOrder> = settingsRepository.albumSortOrder
        .map { runCatching { AlbumSortOrder.valueOf(it) }.getOrDefault(AlbumSortOrder.NAME_ASC) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AlbumSortOrder.NAME_ASC)

    val photos: StateFlow<List<MediaEntry>> = combine(
        allPhotos,
        hiddenFolders,
        photoSortOrder
    ) { all, hidden, sort ->
        val filtered = all.filter { entry ->
            !hidden.any { entry.path.startsWith(it) }
        }
        when (sort) {
            PhotoSortOrder.DATE_DESC -> filtered.sortedByDescending { it.bestTimestamp }
            PhotoSortOrder.DATE_ASC -> filtered.sortedBy { it.bestTimestamp }
            PhotoSortOrder.NAME_ASC -> filtered.sortedBy { java.io.File(it.path).name }
            PhotoSortOrder.NAME_DESC -> filtered.sortedByDescending { java.io.File(it.path).name }
            PhotoSortOrder.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
            PhotoSortOrder.SIZE_ASC -> filtered.sortedBy { it.sizeBytes }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun groupMedia(entries: List<MediaEntry>, columns: Int = 3, sortOrder: PhotoSortOrder = PhotoSortOrder.DATE_DESC): List<GridItem> {
        if (sortOrder != PhotoSortOrder.DATE_DESC && sortOrder != PhotoSortOrder.DATE_ASC) {
            return entries.map { GridItem.Photo(it) }
        }
        val items = mutableListOf<GridItem>()
        var lastHeader = ""
        val format = if (columns >= 6) "MMMM yyyy" else "MMMM d, yyyy"
        val sdf = java.text.SimpleDateFormat(format, java.util.Locale.getDefault())
        
        entries.forEach { entry ->
            val timestamp = entry.bestTimestamp
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
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val favourites: StateFlow<List<MediaEntry>> = repository.favourites
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val groupedFavourites: StateFlow<List<GridItem>> = combine(favourites, gridColumns, photoSortOrder) { media, cols, sort ->
        groupMedia(media, cols, sort)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val trashedMedia: StateFlow<List<MediaEntry>> = repository.trash
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())
        .map { it.toList() } // Compatibility
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val groupedTrashedMedia: StateFlow<List<GridItem>> = combine(trashedMedia, gridColumns, photoSortOrder) { media, cols, sort ->
        groupMedia(media, cols, sort)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val vaultEntries: StateFlow<List<MediaEntry>> = repository.vaultEntries
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val groupedVaultEntries: StateFlow<List<GridItem>> = combine(vaultEntries, gridColumns, photoSortOrder) { media, cols, sort ->
        groupMedia(media, cols, sort)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    val excludedFolders: StateFlow<Set<String>> = settingsRepository.excludedFolders
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())


    val albums: StateFlow<List<Album>> = combine(
        photos,
        albumSortOrder
    ) { photosList, sort ->
        val grouped = photosList.groupBy { 
            val file = java.io.File(it.path)
            file.parentFile?.name ?: "Unknown"
        }.map { (name, entries) ->
            val firstEntry = entries.first()
            val parentPath = java.io.File(firstEntry.path).parent ?: ""
            val lastModified = entries.maxOfOrNull { it.bestTimestamp } ?: 0L
            Album(name, parentPath, firstEntry.uri, entries.size, lastModified)
        }
        when (sort) {
            AlbumSortOrder.NAME_ASC -> grouped.sortedBy { it.name }
            AlbumSortOrder.NAME_DESC -> grouped.sortedByDescending { it.name }
            AlbumSortOrder.COUNT_DESC -> grouped.sortedByDescending { it.itemCount }
            AlbumSortOrder.COUNT_ASC -> grouped.sortedBy { it.itemCount }
            AlbumSortOrder.DATE_DESC -> grouped.sortedByDescending { it.lastModified }
            AlbumSortOrder.DATE_ASC -> grouped.sortedBy { it.lastModified }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val hiddenAlbums: StateFlow<List<Album>> = combine(
        allPhotos,
        hiddenFolders,
        albumSortOrder
    ) { all, hidden, sort ->
        val grouped = all.filter { entry ->
            hidden.any { entry.path.startsWith(it) }
        }.groupBy { 
            val file = java.io.File(it.path)
            file.parentFile?.name ?: "Unknown"
        }.map { (name, entries) ->
            val firstEntry = entries.first()
            val parentPath = java.io.File(firstEntry.path).parent ?: ""
            val lastModified = entries.maxOfOrNull { it.bestTimestamp } ?: 0L
            Album(name, parentPath, firstEntry.uri, entries.size, lastModified)
        }
        when (sort) {
            AlbumSortOrder.NAME_ASC -> grouped.sortedBy { it.name }
            AlbumSortOrder.NAME_DESC -> grouped.sortedByDescending { it.name }
            AlbumSortOrder.COUNT_DESC -> grouped.sortedByDescending { it.itemCount }
            AlbumSortOrder.COUNT_ASC -> grouped.sortedBy { it.itemCount }
            AlbumSortOrder.DATE_DESC -> grouped.sortedByDescending { it.lastModified }
            AlbumSortOrder.DATE_ASC -> grouped.sortedBy { it.lastModified }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var contentObserver: android.database.ContentObserver? = null

    init {
        refresh(delayMillis = 1000)
        registerContentObserver()
        observeGlideThreadCount()
    }

    private fun registerContentObserver() {
        contentObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                refresh()
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

    fun refresh(delayMillis: Long = 0) {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            if (delayMillis > 0) {
                kotlinx.coroutines.delay(delayMillis)
            }
            repository.syncWithMediaStore()
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
            repository.trashMedia(id, uri, path)
        }
    }

    fun moveToTrashBulk(uris: List<String>) {
        viewModelScope.launch {
            if (repository.trashMediaBulk(uris)) {
                refresh()
            }
        }
    }

    fun restoreMedia(id: Long, uri: String) {
        viewModelScope.launch {
            repository.restoreMedia(id, uri)
        }
    }

    fun restoreMediaBulk(uris: List<String>) {
        viewModelScope.launch {
            if (repository.restoreMediaBulk(uris)) {
                refresh()
            }
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

    fun deleteMediaBulk(uris: List<String>) {
        viewModelScope.launch {
            if (repository.deleteMediaBulk(uris)) {
                refresh()
            }
        }
    }

    // --- Metadata ---
    fun getMediaMetadata(path: String) = metadataService.getMetadata(path)
    fun getCoordinates(path: String) = metadataService.getCoordinates(path)
    fun extractMotionVideo(path: String) = metadataService.extractMotionVideo(path)
    fun isUltraHdr(path: String) = metadataService.isUltraHdr(path)


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

    val customShortcuts: StateFlow<List<com.pixel.gallery.utils.CustomShortcut>> = settingsRepository.customShortcuts
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addCustomShortcut(shortcut: com.pixel.gallery.utils.CustomShortcut) {
        viewModelScope.launch {
            settingsRepository.addCustomShortcut(shortcut)
        }
    }

    fun removeCustomShortcut(id: String) {
        viewModelScope.launch {
            settingsRepository.removeCustomShortcut(id)
        }
    }

    private fun observeGlideThreadCount() {
        viewModelScope.launch {
            settingsRepository.glideThreadCount.collect { threads ->
                try {
                    com.pixel.gallery.glide.AvesAppGlideModule.updateThreadCount(threads)
                } catch (e: Exception) {
                    android.util.Log.e("PhotosViewModel", "Failed to update Glide thread count", e)
                }
            }
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

enum class AlbumSortOrder {
    NAME_ASC,
    NAME_DESC,
    COUNT_DESC,
    COUNT_ASC,
    DATE_DESC,
    DATE_ASC
}
