package com.pixel.gallery.data.repository

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.DeduplicationScope
import com.pixel.gallery.model.DuplicateScanProgress
import com.pixel.gallery.model.DuplicateScanResult
import com.pixel.gallery.model.DuplicateValidationResult
import com.pixel.gallery.model.MediaDeduplication
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** Uses the same content URI for verification and the system recycle-bin request. */
class DuplicateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
) {
    private val resolver get() = context.contentResolver
    val canTrash: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val needsWriteAccess: Boolean get() = canTrash && !Environment.isExternalStorageManager()
    val needsOriginalAccess: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private val scanner = MediaDeduplication { entry ->
        // A keeper in the bin, moved, replaced, or no longer accessible cannot protect a copy.
        if (!isCurrentActiveEntry(entry)) throw IOException("File changed or is no longer available")
        val uri = Uri.parse(entry.uri)
        resolver.openInputStream(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri)
    }

    suspend fun scan(
        entries: List<MediaEntry>,
        scope: DeduplicationScope,
        onProgress: (DuplicateScanProgress) -> Unit,
    ): DuplicateScanResult = withContext(Dispatchers.IO) {
        scanner.scan(entries, scope, onProgress)
    }

    suspend fun validate(
        result: DuplicateScanResult,
        selectedIds: Set<Long>,
        onProgress: (DuplicateScanProgress) -> Unit,
    ): DuplicateValidationResult = withContext(Dispatchers.IO) {
        scanner.validate(result, selectedIds, onProgress)
    }

    suspend fun createWriteRequest(entries: List<MediaEntry>): IntentSenderRequest = withContext(Dispatchers.IO) {
        check(canTrash) { "Recycle Bin requires Android 11 or later" }
        require(entries.isNotEmpty() && entries.size <= 1000)
        // Request access only: the content must be checked again after the permission prompt.
        val intent = MediaStore.createWriteRequest(resolver, entries.map { Uri.parse(it.uri) })
        IntentSenderRequest.Builder(intent.intentSender).build()
    }

    suspend fun trashAfterPermission(
        result: DuplicateScanResult,
        selectedIds: Set<Long>,
        onProgress: (DuplicateScanProgress) -> Unit,
    ): Int = mediaRepository.withMediaMutation {
        withContext(Dispatchers.IO) {
            check(canTrash)
            var removed = 0
            // Serialize with gallery moves/restores and validate each group immediately before
            // mutation. No deletion is delegated to a system prompt with a stale fingerprint.
            for (group in result.groups) {
                val ids = group.entries.map { it.contentId }.filter { it in selectedIds }.toSet()
                if (ids.isEmpty()) continue
                val validated = scanner.validate(result.copy(groups = listOf(group)), ids, onProgress)
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
                for (entry in validated.validEntries) {
                    val trashed = runCatching {
                        // Match the snapshot used during validation. A concurrent writer or
                        // mover then produces zero rows instead of trashing a changed file.
                        val selection = "${MediaStore.MediaColumns.DATA} = ? AND " +
                            "${MediaStore.MediaColumns.SIZE} = ? AND " +
                            "${MediaStore.MediaColumns.DATE_MODIFIED} = ? AND " +
                            "${MediaStore.MediaColumns.IS_TRASHED} = 0"
                        val selectionArgs = arrayOf(
                            entry.path,
                            entry.sizeBytes.toString(),
                            (entry.dateModifiedMillis / 1000L).toString(),
                        )
                        resolver.update(Uri.parse(entry.uri), values, selection, selectionArgs) == 1 &&
                            query(entry, arrayOf(MediaStore.MediaColumns.IS_TRASHED)) { it.getInt(0) == 1 }
                    }.getOrDefault(false)
                    if (trashed) removed++
                }
            }
            removed
        }
    }

    private fun isCurrentActiveEntry(entry: MediaEntry): Boolean {
        val projection = mutableListOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE)
        if (canTrash) projection += MediaStore.MediaColumns.IS_TRASHED
        return query(entry, projection.toTypedArray()) { cursor ->
            cursor.getString(0) == entry.path && cursor.getLong(1) == entry.sizeBytes &&
                (!canTrash || cursor.getInt(2) == 0)
        }
    }

    private fun query(entry: MediaEntry, projection: Array<String>, check: (android.database.Cursor) -> Boolean): Boolean {
        val args = Bundle().apply {
            if (canTrash) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return resolver.query(Uri.parse(entry.uri), projection, args, null)?.use { cursor ->
            cursor.moveToFirst() && check(cursor)
        } ?: false
    }
}
