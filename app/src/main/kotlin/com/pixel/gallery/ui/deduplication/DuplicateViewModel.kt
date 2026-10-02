package com.pixel.gallery.ui.deduplication

import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.DuplicateRepository
import com.pixel.gallery.model.DeduplicationScope
import com.pixel.gallery.model.DuplicateScanProgress
import com.pixel.gallery.model.DuplicateScanResult
import com.pixel.gallery.model.DuplicateGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DuplicatePhase { IDLE, SCANNING, REVIEW, VALIDATING, AWAITING_PERMISSION, VERIFYING, COMPLETE, ERROR }

data class DuplicateUiState(
    val title: String = "",
    val phase: DuplicatePhase = DuplicatePhase.IDLE,
    val progress: DuplicateScanProgress? = null,
    val result: DuplicateScanResult? = null,
    val selectedIds: Set<Long> = emptySet(),
    val removedCount: Int = 0,
    val skippedCount: Int = 0,
    val cancelled: Boolean = false,
    val error: String? = null,
    val canTrash: Boolean = true,
)

@HiltViewModel
class DuplicateViewModel @Inject constructor(private val repository: DuplicateRepository) : ViewModel() {
    private val _state = MutableStateFlow(DuplicateUiState(canTrash = repository.canTrash))
    val state = _state.asStateFlow()
    private val _trashRequest = MutableStateFlow<IntentSenderRequest?>(null)
    val trashRequest = _trashRequest.asStateFlow()
    private val _originalAccessRequest = MutableStateFlow(false)
    val originalAccessRequest = _originalAccessRequest.asStateFlow()
    private var pendingScan: Pair<List<MediaEntry>, DeduplicationScope>? = null
    private var job: Job? = null
    private var remainingIds = emptyList<Long>()
    private var pendingEntries = emptyList<MediaEntry>()
    private var cleanupResult: DuplicateScanResult? = null

    fun start(entries: List<MediaEntry>, scope: DeduplicationScope, title: String) {
        if (_state.value.phase != DuplicatePhase.IDLE) return
        _state.value = DuplicateUiState(title, DuplicatePhase.SCANNING, canTrash = repository.canTrash)
        if (repository.needsOriginalAccess) {
            pendingScan = entries.toList() to scope
            _originalAccessRequest.value = true
            return
        }
        scan(entries, scope)
    }

    fun originalRequestLaunched() { _originalAccessRequest.value = false }

    fun onOriginalAccessResult(granted: Boolean, deniedMessage: String) {
        _originalAccessRequest.value = false
        val request = pendingScan ?: return
        pendingScan = null
        if (granted) scan(request.first, request.second)
        else fail(IllegalStateException(deniedMessage))
    }

    private fun scan(entries: List<MediaEntry>, scope: DeduplicationScope) {
        job = viewModelScope.launch {
            try {
                val result = repository.scan(entries.toList(), scope) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                _state.update { it.copy(
                    phase = DuplicatePhase.REVIEW,
                    result = result,
                    selectedIds = emptySet(),
                    skippedCount = result.skippedCount,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun toggle(id: Long) {
        val current = _state.value
        if (current.phase != DuplicatePhase.REVIEW) return
        val group = current.result?.groups?.firstOrNull { group -> group.entries.any { it.contentId == id } } ?: return
        if (id !in current.selectedIds && group.entries.count { it.contentId !in current.selectedIds } <= 1) return
        _state.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
    }

    fun confirm() {
        val current = _state.value
        if (current.phase != DuplicatePhase.REVIEW || !current.canTrash || current.selectedIds.isEmpty()) return
        val result = current.result ?: return
        // Freeze the user's retained copy for every permission batch. Later batches must not
        // use an already-trashed selected copy as their keeper.
        cleanupResult = result.copy(groups = result.groups.mapNotNull { group ->
            val copies = group.entries.filter { it.contentId in current.selectedIds }
            val keeper = group.entries.firstOrNull { it.contentId !in current.selectedIds }
            if (copies.isEmpty() || keeper == null) null else DuplicateGroup(keeper, copies, group.fingerprint)
        })
        remainingIds = current.selectedIds.toList()
        prepareNextBatch()
    }

    private fun prepareNextBatch() {
        _state.update { it.copy(phase = DuplicatePhase.VALIDATING, progress = null) }
        job = viewModelScope.launch {
            try {
                while (remainingIds.isNotEmpty()) {
                    // Stay below the platform request limit. Revalidate every batch immediately
                    // before requesting deletion; never queue unchecked copies for later use.
                    val batchIds = remainingIds.take(1000).toSet()
                    remainingIds = remainingIds.drop(batchIds.size)
                    val validation = repository.validate(requireNotNull(cleanupResult), batchIds) { progress ->
                        _state.update { it.copy(progress = progress) }
                    }
                    pendingEntries = validation.validEntries
                    _state.update { it.copy(skippedCount = it.skippedCount + batchIds.size - pendingEntries.size) }
                    if (pendingEntries.isEmpty()) continue
                    if (!repository.needsWriteAccess) {
                        val removed = repository.trashAfterPermission(
                            requireNotNull(cleanupResult), pendingEntries.map { it.contentId }.toSet(),
                        ) { progress -> _state.update { it.copy(progress = progress) } }
                        _state.update { it.copy(removedCount = it.removedCount + removed, skippedCount = it.skippedCount + pendingEntries.size - removed) }
                        pendingEntries = emptyList()
                        continue
                    }
                    val request = repository.createWriteRequest(pendingEntries)
                    _state.update { it.copy(phase = DuplicatePhase.AWAITING_PERMISSION, progress = null) }
                    _trashRequest.value = request
                    return@launch
                }
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun requestLaunched() { _trashRequest.value = null }

    fun requestFailed(error: Exception) {
        _trashRequest.value = null
        fail(error)
    }

    fun onTrashResult(granted: Boolean) {
        if (_state.value.phase != DuplicatePhase.AWAITING_PERMISSION) return
        _trashRequest.value = null
        if (!granted) {
            pendingEntries = emptyList()
            finish(cancelled = true)
            return
        }
        _state.update { it.copy(phase = DuplicatePhase.VERIFYING) }
        job = viewModelScope.launch {
            try {
                val removed = repository.trashAfterPermission(
                    requireNotNull(cleanupResult), pendingEntries.map { it.contentId }.toSet(),
                ) { progress -> _state.update { it.copy(progress = progress) } }
                _state.update { it.copy(
                    removedCount = it.removedCount + removed,
                    skippedCount = it.skippedCount + pendingEntries.size - removed,
                ) }
                pendingEntries = emptyList()
                prepareNextBatch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun dismiss() {
        val current = _state.value
        if (current.phase == DuplicatePhase.AWAITING_PERMISSION || current.phase == DuplicatePhase.VERIFYING) return
        job?.cancel()
        pendingScan = null
        _originalAccessRequest.value = false
        _trashRequest.value = null
        remainingIds = emptyList()
        pendingEntries = emptyList()
        cleanupResult = null
        if (current.phase == DuplicatePhase.VALIDATING) {
            finish(cancelled = true)
        } else {
            _state.value = DuplicateUiState(canTrash = repository.canTrash)
        }
    }

    private fun finish(cancelled: Boolean = false) {
        remainingIds = emptyList()
        _state.update { it.copy(phase = DuplicatePhase.COMPLETE, progress = null, cancelled = cancelled) }
    }

    private fun fail(error: Exception) {
        remainingIds = emptyList()
        _state.update { it.copy(phase = DuplicatePhase.ERROR, error = error.message ?: "Could not process duplicates") }
    }
}
