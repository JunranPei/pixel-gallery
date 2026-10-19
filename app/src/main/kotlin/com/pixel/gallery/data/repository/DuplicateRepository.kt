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
import com.pixel.gallery.model.IncrementalDeduplication
import com.pixel.gallery.model.DuplicateFileState
import com.pixel.gallery.data.local.PersistentDuplicateIndex
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import javax.inject.Inject

/** Uses the same content URI for verification and the system recycle-bin request. */
class DuplicateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
) {
    private val resolver get() = context.contentResolver
    private val contentIndex by lazy { PersistentDuplicateIndex(context) }
    private val scanMutex = Mutex()
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
        onCachedResult: (DuplicateScanResult) -> Unit = {},
        onProgress: (DuplicateScanProgress) -> Unit,
    ): DuplicateScanResult = withContext(Dispatchers.IO) {
        // A transfer completes its MediaStore mutation before the gallery's
        // observer-driven Room snapshot necessarily reaches this screen. Read
        // the provider again so a just-created keep-both copy participates in
        // the scan instead of scanning the pre-transfer list supplied by UI.
        scanMutex.withLock {
            // Publish the previous snapshot before provider synchronisation or file-state checks.
            // It is explicitly read-only until the new scan finishes.
            val engine = indexedScanner()
            engine.cached(entries, scope)?.let(onCachedResult)
            val currentEntries = refreshedScanEntries(entries, scope)
            engine.scan(currentEntries, scope, onProgress)
        }
    }

    private fun indexedScanner(): IncrementalDeduplication {
        // Provider versions guard against rebuilt libraries and recycled content IDs. Per-file
        // inode/ctime/mtime nanoseconds also catch direct writes that retain size and old dates.
        val versions = mutableMapOf<String, String>()
        return IncrementalDeduplication(contentIndex, stateOf = { entry ->
            try {
                val stat = Os.stat(entry.path)
                if (!OsConstants.S_ISREG(stat.st_mode)) null else {
                    val uri = Uri.parse(entry.uri)
                    val volume = if (Build.VERSION.SDK_INT >= 29) MediaStore.getVolumeName(uri) else "external"
                    val epoch = versions.getOrPut(volume) {
                        try {
                            if (Build.VERSION.SDK_INT >= 30) MediaStore.getVersion(context, volume)
                            else if (Build.VERSION.SDK_INT >= 29) MediaStore.getVersion(context) else "legacy"
                        } catch (_: Exception) { "unversioned-provider" }
                    }
                    // API 26 exposes only second-precision times: filter by actual size there,
                    // but deliberately do not persist/reuse content hashes across scans.
                    // Some removable filesystems also expose only coarse ctime. Treat an
                    // absent subsecond component conservatively instead of trusting old hashes.
                    val signature = if (Build.VERSION.SDK_INT >= 27 && stat.st_ctim.tv_nsec != 0L) {
                        "prefix64k-v1|$epoch|${stat.st_dev}|${stat.st_ino}|${stat.st_size}|" +
                            "${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}|${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}"
                    } else null
                    DuplicateFileState(stat.st_size, signature)
                }
            } catch (_: Exception) { null }
        }, open = { entry ->
            if (!isCurrentActiveEntry(entry)) throw IOException("File changed or is no longer available")
            val uri = Uri.parse(entry.uri)
            resolver.openInputStream(if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri)
        })
    }

    private suspend fun refreshedScanEntries(
        seed: List<MediaEntry>,
        scope: DeduplicationScope,
    ): List<MediaEntry> {
        // The all-files page already supplies the repository's complete active
        // snapshot. Refreshing it here would also pull unrelated MediaStore rows
        // into isolated scans (and needlessly hash the whole device). Album
        // scans are the transfer-sensitive path and are refreshed below.
        if (scope == DeduplicationScope.ALL_FILES) return seed
        mediaRepository.syncWhenMediaMutationsIdle(force = true)
        val current = mediaRepository.currentActiveEntries()
        val folders = seed.mapNotNull { folderOf(it.path) }.toSet()
        return current.filter { folderOf(it.path) in folders }
    }

    private fun folderOf(path: String): String? =
        File(path).parent?.replace('\\', '/')?.trimEnd('/')?.ifBlank { null }

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
    ): Int = trashAfterPermissionDetailed(result, selectedIds, onProgress).size

    /**
     * Performs the final revalidation and moves only entries that still match the snapshot to the
     * recycle bin. Returning the actual IDs is important for the review screen: a failed conditional
     * update must remain visible so the user can inspect it and choose another cleanup batch.
     */
    suspend fun trashAfterPermissionDetailed(
        result: DuplicateScanResult,
        selectedIds: Set<Long>,
        onProgress: (DuplicateScanProgress) -> Unit,
    ): Set<Long> = mediaRepository.withMediaMutation {
        withContext(Dispatchers.IO) {
            check(canTrash)
            val removedIds = linkedSetOf<Long>()
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
                            "${MediaStore.MediaColumns.DATE_MODIFIED} = ? AND " +
                            "${MediaStore.MediaColumns.IS_TRASHED} = 0"
                        val selectionArgs = arrayOf(
                            entry.path,
                            (entry.dateModifiedMillis / 1000L).toString(),
                        )
                        resolver.update(Uri.parse(entry.uri), values, selection, selectionArgs) == 1 &&
                            query(entry, arrayOf(MediaStore.MediaColumns.IS_TRASHED)) { it.getInt(0) == 1 }
                    }.getOrDefault(false)
                    if (trashed) removedIds += entry.contentId
                }
            }
            removedIds
        }
    }

    private fun isCurrentActiveEntry(entry: MediaEntry): Boolean {
        // Do not reject a just-copied file because MediaStore's SIZE column is
        // still stale. The scanner hashes the stream and records its actual byte
        // count; the final mutation then uses that verified snapshot size.
        val projection = mutableListOf(MediaStore.MediaColumns.DATA)
        if (canTrash) projection += MediaStore.MediaColumns.IS_TRASHED
        return query(entry, projection.toTypedArray()) { cursor ->
            cursor.getString(0) == entry.path && (!canTrash || cursor.getInt(1) == 0)
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
