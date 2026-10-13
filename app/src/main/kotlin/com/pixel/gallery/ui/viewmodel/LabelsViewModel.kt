package com.pixel.gallery.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.DuplicateLabelNameException
import com.pixel.gallery.data.repository.InvalidLabelNameException
import com.pixel.gallery.data.repository.LabelRepository
import com.pixel.gallery.data.repository.LabelUnavailableException
import com.pixel.gallery.data.repository.LabelMediaUnavailableException
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.LabelEditRequest
import com.pixel.gallery.model.buildLabelSummaries
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LabelsViewModel @Inject constructor(
    private val repository: LabelRepository,
    private val mediaRepository: MediaRepository,
    settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val labelEntries = repository.labels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val memberships = repository.memberships.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val visibleMedia = combine(
        mediaRepository.allEntries,
        settingsRepository.hiddenFolders,
        settingsRepository.excludedFolders
    ) { entries, hidden, excluded ->
        val invisible = hidden + excluded
        entries.filter { media ->
            !media.path.contains("/PixelGallery-QuickSortUndo/") &&
            // Match PhotosViewModel / MediaRepository visibility exactly so
            // label counts never include files missing from the normal grid.
            !media.isTrashed && invisible.none { media.path.startsWith(it) }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val labels = combine(labelEntries, memberships, visibleMedia) { entries, links, media ->
        buildLabelSummaries(entries, links, media)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun mediaForLabel(labelId: Long): Flow<List<MediaEntry>> =
        combine(visibleMedia, memberships) { media, links ->
            val ids = links.asSequence().filter { it.labelId == labelId }.map { it.contentId }.toHashSet()
            media.filter { it.contentId in ids }
        }.flowOn(Dispatchers.Default)

    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _editorSaveGeneration = MutableStateFlow(0L)
    val editorSaveGeneration = _editorSaveGeneration.asStateFlow()

    fun clearError() { _errorMessage.value = null }

    fun createLabel(name: String) = mutate { repository.createLabel(name) }
    fun renameLabel(id: Long, name: String) = mutate { repository.renameLabel(id, name) }
    fun deleteLabel(id: Long) = mutate { repository.deleteLabel(id) }

    fun applyEdits(selectedIds: Set<Long>, request: LabelEditRequest, onSaved: () -> Unit) = mutate {
        mediaRepository.withValidLabelMedia(selectedIds) {
            repository.applyEdits(selectedIds, request)
        }
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
