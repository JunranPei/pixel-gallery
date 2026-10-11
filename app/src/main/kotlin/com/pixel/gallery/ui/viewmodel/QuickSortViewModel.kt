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
import com.pixel.gallery.model.QuickSortPermissionRequest
import javax.inject.Inject

@HiltViewModel
class QuickSortViewModel @Inject constructor(private val repository: QuickSortQueueRepository) : ViewModel() {
    val state = repository.state
    private val _permissionRequest = MutableStateFlow<QuickSortPermissionRequest?>(null)
    val permissionRequest = _permissionRequest.asStateFlow()
    private var requestingPermission = false
    private var cleared = false
    fun enqueue(entries: List<MediaEntry>, destination: TransferDestination, onResult: (QuickSortEnqueueResult) -> Unit) {
        viewModelScope.launch {
            val result = repository.enqueue(entries, destination)
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
    fun retry(jobId: String, onResult: (QuickSortEnqueueResult) -> Unit) {
        viewModelScope.launch {
            val result = repository.retry(jobId)
            if (result is QuickSortEnqueueResult.Accepted) repository.startFromForeground()
            onResult(result)
        }
    }
    fun resume() = repository.startFromForeground()

    override fun onCleared() {
        cleared = true
        _permissionRequest.value?.let { repository.releasePermissionFromOwner(it.token) }
        super.onCleared()
    }
}
