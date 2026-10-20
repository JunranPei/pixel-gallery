package com.pixel.gallery.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixel.gallery.R
import com.pixel.gallery.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LabelBackupOperation { EXPORT, SCAN, IMPORT }
data class LabelBackupUiState(
    val operation: LabelBackupOperation? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val preview: LabelImportPreview? = null,
    val exportResult: LabelExportResult? = null,
    val importResult: LabelImportResult? = null,
    val error: String? = null,
)

@HiltViewModel
class LabelBackupViewModel @Inject constructor(
    private val repository: LabelBackupRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(LabelBackupUiState())
    val state = _state.asStateFlow()

    fun export(uri: Uri) = run(LabelBackupOperation.EXPORT) {
        val result = repository.export(uri, ::progress)
        _state.update { it.copy(exportResult = result) }
    }

    fun preview(uri: Uri) = run(LabelBackupOperation.SCAN) {
        val result = repository.preview(uri, ::progress)
        _state.update { it.copy(preview = result) }
    }

    fun apply() {
        val preview = _state.value.preview ?: return
        run(LabelBackupOperation.IMPORT) {
            val result = repository.apply(preview)
            _state.update { it.copy(importResult = result) }
        }
    }

    fun clearPreview() { if (_state.value.operation == null) _state.update { it.copy(preview = null, importResult = null) } }
    fun dismissMessage() { _state.update { it.copy(error = null, exportResult = null) } }

    private fun progress(completed: Int, total: Int) { _state.update { it.copy(completed = completed, total = total) } }

    private fun run(operation: LabelBackupOperation, block: suspend () -> Unit) {
        if (_state.value.operation != null) return
        _state.update {
            if (operation == LabelBackupOperation.IMPORT) it.copy(operation = operation, completed = 0, total = 0, error = null)
            else LabelBackupUiState(operation = operation)
        }
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = when {
                    error is InvalidLabelBackupException -> R.string.label_backup_invalid
                    error is ChangedLabelMediaException || error is LabelMediaUnavailableException -> R.string.label_backup_changed
                    operation == LabelBackupOperation.EXPORT -> R.string.label_backup_export_failed
                    else -> R.string.label_backup_import_failed
                }
                // A failed apply requires a fresh scan, even if the file names still look the same.
                _state.update { it.copy(error = context.getString(message),
                    preview = if (operation == LabelBackupOperation.IMPORT) null else it.preview) }
            } finally {
                _state.update { it.copy(operation = null) }
            }
        }
    }
}
