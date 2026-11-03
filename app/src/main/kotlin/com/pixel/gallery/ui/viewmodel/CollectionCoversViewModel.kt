package com.pixel.gallery.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionCoversViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val covers = settings.collectionCovers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _error = MutableStateFlow(false)
    val error = _error.asStateFlow()
    fun clearError() { _error.value = false }
    fun save(key: String, uri: String?, onSaved: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        _error.value = false
        viewModelScope.launch {
            try { settings.setCollectionCover(key, uri); onSaved() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = true }
            finally { _saving.value = false }
        }
    }
}
