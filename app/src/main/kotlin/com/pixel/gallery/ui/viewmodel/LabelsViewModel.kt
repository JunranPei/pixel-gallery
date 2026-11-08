package com.pixel.gallery.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.DuplicateLabelNameException
import com.pixel.gallery.data.repository.InvalidLabelNameException
import com.pixel.gallery.data.repository.LabelRepository
import com.pixel.gallery.data.repository.LabelUnavailableException
import com.pixel.gallery.data.repository.LabelMediaUnavailableException
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.LabelEditRequest
import com.pixel.gallery.model.AlbumSortOrder
import com.pixel.gallery.model.isInsideMediaFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LabelsViewModel @Inject constructor(
    private val repository: LabelRepository,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
    collections: SharedLabelCollections,
) : ViewModel() {
    private val shared = collections.data
    val labelEntries = shared.labelEntries
    val memberships = shared.memberships
    val labelIdsByMedia = shared.labelIdsByMedia
    val sortMode = shared.sortMode
    val recentLabelIds = settingsRepository.recentLabelIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val labels = shared.labels
    internal val loadedLabels = shared.loadedLabels

    fun mediaForLabel(labelId: Long): Flow<List<MediaEntry>> =
        shared.mediaForLabel(labelId)

    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _editorSaveGeneration = MutableStateFlow(0L)
    val editorSaveGeneration = _editorSaveGeneration.asStateFlow()

    fun clearError() { _errorMessage.value = null }

    fun createLabel(name: String) = mutate { repository.createLabel(name) }
    suspend fun renameAndWait(id: Long, name: String): Result<Unit> = runCatching { repository.renameLabel(id, name) }
    fun renameLabel(id: Long, name: String) = mutate { repository.renameLabel(id, name) }
    fun deleteLabel(id: Long) = mutate { repository.deleteLabel(id) }
    fun deleteLabels(ids: Set<Long>) = mutate { repository.deleteLabels(ids) }
    fun setSortMode(mode: AlbumSortOrder) = viewModelScope.launch { settingsRepository.setLabelSortOrder(mode.name) }
    fun moveLabel(id: Long, delta: Int) = mutate {
        val ordered = labelEntries.value.filterNot { it.isSystem }.map { it.id }.toMutableList()
        val index = ordered.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in ordered.indices) return@mutate
        ordered[index] = ordered[target]
        ordered[target] = id
        repository.reorderLabels(ordered)
    }
    fun reorderLabels(orderedIds: List<Long>) = mutate { repository.reorderLabels(orderedIds) }
    fun removeMediaFromLabel(labelId: Long, contentIds: Set<Long>, onSaved: () -> Unit = {}) = mutate {
        if (contentIds.isNotEmpty()) repository.applyEdits(contentIds, LabelEditRequest(changes = mapOf(labelId to false)))
        onSaved()
    }

    fun applyEdits(selectedIds: Set<Long>, request: LabelEditRequest, onSaved: () -> Unit) = mutate {
        val touched = mediaRepository.withValidLabelMedia(selectedIds) {
            repository.applyEdits(selectedIds, request)
        }
        settingsRepository.recordRecentLabelIds(touched)
        _editorSaveGeneration.value += 1L
        onSaved()
    }

    private fun mutate(block: suspend () -> Unit) {
        if (_isSaving.value) return
        _isSaving.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _errorMessage.value = context.getString(when (error) {
                    is InvalidLabelNameException -> R.string.labels_invalid_name
                    is DuplicateLabelNameException -> R.string.labels_duplicate_name
                    is LabelUnavailableException -> R.string.labels_unavailable
                    is LabelMediaUnavailableException -> R.string.labels_media_unavailable
                    else -> R.string.labels_save_failed
                })
            } finally {
                _isSaving.value = false
            }
        }
    }
}

internal fun filterVisibleLabelMedia(
    entries: List<MediaEntry>,
    invisibleFolders: Set<String>,
    lockedIds: Set<Long>,
): List<MediaEntry> = entries.filter { media ->
    !media.path.contains("/PixelGallery-QuickSortUndo/") &&
        (lockedIds.isEmpty() || media.contentId !in lockedIds) &&
        !media.isTrashed &&
        invisibleFolders.none { media.path.isInsideMediaFolder(it) }
}
