package com.pixel.gallery.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AlbumGroupsViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val grouping = settings.albumGrouping.stateIn(viewModelScope, SharingStarted.Eagerly, AlbumGrouping())
    private val _error = MutableStateFlow(false)
    val error = _error.asStateFlow()
    private fun update(change: (AlbumGrouping) -> AlbumGrouping) {
        viewModelScope.launch {
            runCatching { settings.updateAlbumGrouping(change) }.onFailure { _error.value = true }
        }
    }
    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()
    private fun save(onSaved: () -> Unit, change: (AlbumGrouping) -> AlbumGrouping) {
        if (_isSaving.value) return
        _isSaving.value = true
        _error.value = false
        viewModelScope.launch {
            try {
                settings.updateAlbumGrouping(change)
                onSaved()
            } catch (_: Exception) {
                _error.value = true
            } finally {
                _isSaving.value = false
            }
        }
    }
    fun clearError() { _error.value = false }
    fun toggle(id: String) = update { it.copy(collapsedIds = if (id in it.collapsedIds) it.collapsedIds - id else it.collapsedIds + id) }
    fun assign(paths: Set<String>, id: String?, onSaved: () -> Unit = {}) = save(onSaved) { it.assign(paths, id) }
    fun create(name: String, paths: Set<String>, onSaved: () -> Unit = {}, id: String = UUID.randomUUID().toString()) = save(onSaved) { state ->
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 60 && state.groups.none { it.name.equals(cleaned, true) })
        state.copy(groups = state.groups + AlbumGroup(id, cleaned, colorIndex = state.nextColorIndex())).assign(paths, id)
    }
    fun assignLabels(ids: Set<Long>, id: String?, onSaved: () -> Unit = {}) = save(onSaved) { it.assignLabels(ids, id) }
    fun createLabels(name: String, ids: Set<Long>, onSaved: () -> Unit = {}, id: String = UUID.randomUUID().toString()) = save(onSaved) { state ->
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 60 && state.groups.none { it.name.equals(cleaned, true) })
        state.copy(groups = state.groups + AlbumGroup(id, cleaned, colorIndex = state.nextColorIndex(), labelIds = emptyList())).assignLabels(ids, id)
    }
    fun rename(id: String, name: String) = update { state ->
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 60 && state.groups.none { it.id != id && it.name.equals(cleaned, true) })
        state.copy(groups = state.groups.map { if (it.id == id) it.copy(name = cleaned) else it })
    }
    suspend fun renameAndWait(id: String, name: String): Result<Unit> = runCatching {
        settings.updateAlbumGrouping { state ->
            val cleaned = name.trim()
            require(cleaned.isNotEmpty() && cleaned.length <= 60 && state.groups.none { it.id != id && it.name.equals(cleaned, true) })
            state.copy(groups = state.groups.map { if (it.id == id) it.copy(name = cleaned) else it })
        }
    }
    fun dissolve(id: String) = update { it.copy(groups = it.groups.filterNot { g -> g.id == id }, collapsedIds = it.collapsedIds - id) }
}
