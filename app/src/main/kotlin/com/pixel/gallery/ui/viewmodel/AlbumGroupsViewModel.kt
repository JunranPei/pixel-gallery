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
    fun clearError() { _error.value = false }
    fun toggle(id: String) = update { it.copy(collapsedIds = if (id in it.collapsedIds) it.collapsedIds - id else it.collapsedIds + id) }
    fun assign(paths: Set<String>, id: String?) = update { it.assign(paths, id) }
    fun create(name: String, paths: Set<String>) = update { state ->
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 60 && state.groups.none { it.name.equals(cleaned, true) })
        val id = UUID.randomUUID().toString()
        state.copy(groups = state.groups + AlbumGroup(id, cleaned)).assign(paths, id)
    }
    fun rename(id: String, name: String) = update { state ->
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 60 && state.groups.none { it.id != id && it.name.equals(cleaned, true) })
        state.copy(groups = state.groups.map { if (it.id == id) it.copy(name = cleaned) else it })
    }
    fun dissolve(id: String) = update { it.copy(groups = it.groups.filterNot { g -> g.id == id }, collapsedIds = it.collapsedIds - id) }
}
