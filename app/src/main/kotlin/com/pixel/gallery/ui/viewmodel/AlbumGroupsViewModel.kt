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
    val enabled = settings.groupingEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setEnabled(value: Boolean) { viewModelScope.launch { settings.setGroupingEnabled(value) } }
    private val expansion = PendingGroupExpansion()
    private val _grouping = MutableStateFlow(AlbumGrouping())
    val grouping = _grouping.asStateFlow()
    private val _groupingLoaded = MutableStateFlow(false)
    val groupingLoaded = _groupingLoaded.asStateFlow()
    init {
        viewModelScope.launch { settings.albumGrouping.collect {
            _grouping.value = expansion.apply(it)
            _groupingLoaded.value = true
        } }
    }
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
    fun rememberRows(rows: List<GroupedAlbumRow>) = update { it.rememberOrder(rows) }
    fun setOrder(scope: String, rule: AlbumSortOrder, rows: List<GroupedAlbumRow>) = update { state ->
        val prepared = if (rule == AlbumSortOrder.CUSTOM) state.startCustom(scope, rows) else state
        prepared.withOrder(scope) { it.copy(rule = rule) }
    }
    fun startCustom(rows: List<GroupedAlbumRow>) = update { state ->
        rows.map { it.orderScope() }.distinct().fold(state) { result, scope -> result.startCustom(scope, rows) }
    }
    fun pinRows(rows: List<GroupedAlbumRow>, keys: Set<String>) = update { state ->
        val chosen = rows.filter { it.key in keys }
        val remove = chosen.isNotEmpty() && chosen.all { it.key in state.orderState(it.orderScope()).pinned }
        var result = state
        chosen.groupBy { it.orderScope() }.forEach { (scope, entries) ->
            result = result.withOrder(scope) { saved -> saved.copy(pinned = if (remove) saved.pinned - entries.map { it.key }.toSet() else (saved.pinned + entries.map { it.key }).distinct()) }
        }
        result
    }
    fun moveRow(rows: List<GroupedAlbumRow>, from: String, to: String, default: AlbumSortOrder) = update { state ->
        val a = rows.firstOrNull { it.key == from } ?: return@update state
        if (a.group != null) return@update state.moveGroup(rows, from, to)
        val b = rows.firstOrNull { it.key == to } ?: return@update state
        val scope = a.orderScope()
        val saved = state.orderState(scope)
        if (scope != b.orderScope() || (from in saved.pinned) != (to in saved.pinned)) return@update state
        val pin = from in saved.pinned
        if (!pin && (saved.rule ?: if (scope.startsWith("groups:")) AlbumSortOrder.CUSTOM else default) != AlbumSortOrder.CUSTOM) return@update state
        val order = (if (pin) saved.pinned else saved.custom).toMutableList()
        if (from !in order || to !in order) return@update state
        val destination = order.indexOf(to)
        order.remove(from); order.add(destination.coerceAtMost(order.size), from)
        state.withOrder(scope) { if (pin) it.copy(pinned = order) else it.copy(custom = order, customStarted = true) }
    }
    fun clearError() { _error.value = false }
    fun toggle(id: String) {
        val request = expansion.begin(id, grouping.value)
        _grouping.value = expansion.apply(grouping.value)
        viewModelScope.launch {
            try {
                settings.updateAlbumGrouping { state ->
                    state.copy(collapsedIds = if (request.collapsed) state.collapsedIds + id else state.collapsedIds - id)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = true
            } finally {
                expansion.finish(request)
            }
            _grouping.value = expansion.apply(settings.albumGrouping.first())
        }
    }
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
