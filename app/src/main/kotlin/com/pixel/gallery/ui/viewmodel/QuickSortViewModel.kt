package com.pixel.gallery.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.QuickSortQueueRepository
import com.pixel.gallery.model.QuickSortEnqueueResult
import com.pixel.gallery.model.TransferDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Dispatchers
import com.pixel.gallery.model.sameQuickSortUiState
import com.pixel.gallery.model.prepareForUi
import com.pixel.gallery.model.QuickSortPermissionRequest
import javax.inject.Inject

@HiltViewModel
@OptIn(kotlinx.coroutines.FlowPreview::class)
class QuickSortViewModel @Inject constructor(private val repository: QuickSortQueueRepository) : ViewModel() {
    val state = repository.state
        .distinctUntilChanged(::sameQuickSortUiState)
        .sample(150L)
        .map { it.prepareForUi() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.state.value)
    val failure = repository.failure
    fun dismissFailure(failure: com.pixel.gallery.model.QuickSortMoveFailure) = repository.dismissFailure(failure)
    private val _permissionRequest = MutableStateFlow<QuickSortPermissionRequest?>(null)
    val permissionRequest = _permissionRequest.asStateFlow()
    private var requestingPermission = false
    private var cleared = false
    fun enqueue(entries: List<MediaEntry>, destination: TransferDestination, onResult: (QuickSortEnqueueResult) -> Unit, sessionId: String? = null, conflictPolicy: com.pixel.gallery.model.ConflictPolicy? = null) {
        viewModelScope.launch {
            val result = repository.enqueue(entries, destination, sessionId, conflictPolicy)
            if (result is QuickSortEnqueueResult.Accepted) repository.startFromForeground()
            onResult(result)
        }
    }
    fun requestNextPermission() {
        _permissionRequest.value?.let { existing ->
            if (repository.state.value.needsPermission?.permissionToken != existing.token) {
                repository.releasePermissionFromOwner(existing.token)
                _permissionRequest.value = null
            }
        }
        if (requestingPermission || _permissionRequest.value != null || cleared) return
        requestingPermission = true
        viewModelScope.launch {
            try {
                // A lifecycle effect may disappear while IO claims the token.
                // Keep that claim in the retained ViewModel before returning.
                withContext(NonCancellable) {
                    val request = repository.nextPermission()
                    if (cleared || (request != null && repository.state.value.needsPermission?.permissionToken != request.token)) {
                        request?.let { repository.releasePermissionFromOwner(it.token) }
                    }
                    else _permissionRequest.value = request
                }
            } finally { requestingPermission = false }
        }
    }
    fun authorize(jobId: String, token: String, granted: Boolean) {
        if (_permissionRequest.value?.token == token) _permissionRequest.value = null
        viewModelScope.launch {
            repository.authorize(jobId, token, granted)
            repository.startFromForeground()
        }
    }
    fun cancel(jobId: String) { viewModelScope.launch { repository.cancel(jobId) } }
    fun finishSession(sessionId: String) { repository.finishSessionFromOwner(sessionId) }
    fun undo(jobId: String, sessionId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = repository.requestUndo(jobId, sessionId)
            if (error == null) repository.startFromForeground()
            onResult(error)
        }
    }
    fun resolveConflict(jobId: String, policy: com.pixel.gallery.model.ConflictPolicy) {
        viewModelScope.launch {
            repository.resolveConflict(jobId, policy)
            repository.startFromForeground()
        }
    }
    fun resume() = repository.startFromForeground()

    override fun onCleared() {
        cleared = true
        _permissionRequest.value?.let { repository.releasePermissionFromOwner(it.token) }
        super.onCleared()
    }
}
