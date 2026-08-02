package com.pixel.gallery.data.repository

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.exifinterface.media.ExifInterface
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.local.dao.MediaDao
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.local.entity.VaultEntry
import com.google.gson.Gson
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.ConflictPolicy
import com.pixel.gallery.model.TransferItemFailure
import com.pixel.gallery.model.TransferMode
import com.pixel.gallery.model.TransferProgress
import com.pixel.gallery.model.TransferSummary
import com.pixel.gallery.model.getAvailableTransferName
import com.pixel.gallery.utils.StorageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val settingsRepository: SettingsRepository
) {
    private val gson = Gson()
    private var lastSyncedGeneration = 0L
    private val repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val allPaths = mediaDao.getAllMediaPaths()
                val missingIds = allPaths.filter { entry ->
                    !java.io.File(entry.path).exists()
                }.map { entry -> entry.contentId }

                if (missingIds.isNotEmpty()) {
                    mediaDao.deleteByIds(missingIds)
                    android.util.Log.d("MediaRepository", "Startup physical check: Deleted ${missingIds.size} missing physical file entries from Room.")
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaRepository", "Failed to run startup physical check", e)
            }
        }
    }

    val allEntries: StateFlow<List<MediaEntry>> = combine(
        mediaDao.getAllEntries(),
        settingsRepository.excludedFolders
    ) { entries, excluded ->
        entries.filter { entry ->
            !excluded.any { entry.path.startsWith(it) }
        }
    }.stateIn(
        scope = repositoryScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    val favourites: StateFlow<List<MediaEntry>> = combine(
        mediaDao.getFavourites(),
        settingsRepository.excludedFolders
    ) { entries, excluded ->
        entries.filter { entry ->
            !excluded.any { entry.path.startsWith(it) }
        }
    }.stateIn(
        scope = repositoryScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    val trash: StateFlow<List<MediaEntry>> = mediaDao.getTrash().stateIn(
        scope = repositoryScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    ) 


    fun isFavourite(id: Long): Flow<Boolean> = mediaDao.isFavourite(id)

    suspend fun addFavourite(id: Long) = withContext(Dispatchers.IO) {
        mediaDao.addFavourite(com.pixel.gallery.data.local.entity.FavouriteEntry(id))
    }

    suspend fun removeFavourite(id: Long) = withContext(Dispatchers.IO) {
        mediaDao.removeFavourite(id)
    }

    // --- Trash ---
    suspend fun trashMedia(id: Long, uriString: String, path: String) = withContext(Dispatchers.IO) {
        trashMediaBulk(listOf(uriString))
    }

    suspend fun trashMediaBulk(uriStrings: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = uriStrings.map { Uri.parse(it) }
            
            if (Environment.isExternalStorageManager()) {
                // If we have All Files Access, we can skip the system dialog by updating the column directly
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, 1)
                }
                uris.forEach { uri ->
                    context.contentResolver.update(uri, values, null, null)
                }
                true // Handled internally
            } else {
                val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
                MainActivity.launchIntentSender(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                false // Waiting for system activity result
            }
        } else {
            false
        }
    }

    suspend fun restoreMedia(id: Long, uriString: String) = withContext(Dispatchers.IO) {
        restoreMediaBulk(listOf(uriString))
    }

    suspend fun restoreMediaBulk(uriStrings: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = uriStrings.map { Uri.parse(it) }
            
            if (Environment.isExternalStorageManager()) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, 0)
                }
                uris.forEach { uri ->
                    context.contentResolver.update(uri, values, null, null)
                }
                true // Handled internally
            } else {
                val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, false)
                MainActivity.launchIntentSender(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                false // Waiting for system activity result
            }
        } else {
            false
        }
    }

    suspend fun deleteMediaBulk(uriStrings: List<String>): Boolean = withContext(Dispatchers.IO) {
        val uris = uriStrings.map { Uri.parse(it) }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                uris.forEach { uri ->
                    context.contentResolver.delete(uri, null, null)
                }
                true
            } else {
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                MainActivity.launchIntentSender(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                false
            }
        } else {
            uris.forEach { uri ->
                context.contentResolver.delete(uri, null, null)
            }
            true
        }
    }

    // --- Vault ---
    suspend fun moveToVault(entry: MediaEntry): Boolean = withContext(Dispatchers.IO) {
        val vaultDir = java.io.File(context.getExternalFilesDir(null), "vault")
        if (!vaultDir.exists()) vaultDir.mkdirs()
        
        val originalFile = java.io.File(entry.path)
        val vaultFile = java.io.File(vaultDir, entry.contentId.toString())
        val originalLastModified = originalFile.lastModified()
        
        // Use copy + delete if rename fails (e.g. cross-volume)
        val moved = if (originalFile.renameTo(vaultFile)) {
            true
        } else {
            try {
                originalFile.copyTo(vaultFile, overwrite = true)
                vaultFile.setLastModified(originalLastModified)
                originalFile.delete()
                true
            } catch (e: Exception) {
                false
            }
        }

        if (moved) {
            val vaultEntry = VaultEntry(
                id = entry.contentId,
                vaultPath = vaultFile.absolutePath,
                originalPath = entry.path,
                entryJson = gson.toJson(entry)
            )
            mediaDao.insertVaultEntry(vaultEntry)
            
            // Delete from MediaStore
            context.contentResolver.delete(android.net.Uri.parse(entry.uri), null, null)
            
            // Manually remove from local DB so it disappears instantly
            mediaDao.deleteByIds(listOf(entry.contentId))
            true
        } else {
            false
        }
    }

    suspend fun restoreFromVault(id: Long): Boolean = withContext(Dispatchers.IO) {
        val vaultEntry = mediaDao.getVaultEntry(id) ?: return@withContext false
        val vaultFile = java.io.File(vaultEntry.vaultPath)
        val originalFile = java.io.File(vaultEntry.originalPath)
        val vaultLastModified = vaultFile.lastModified()
        
        val restored = if (vaultFile.renameTo(originalFile)) {
            true
        } else {
            try {
                vaultFile.copyTo(originalFile, overwrite = true)
                originalFile.setLastModified(vaultLastModified)
                vaultFile.delete()
                true
            } catch (e: Exception) {
                false
            }
        }

        if (restored) {
            val originalEntry = gson.fromJson(vaultEntry.entryJson, MediaEntry::class.java)
            mediaDao.deleteVaultEntry(id)
            
            // Rescan the file to add it back to MediaStore
            android.media.MediaScannerConnection.scanFile(context, arrayOf(originalFile.absolutePath), null) { _, uri ->
                if (uri != null) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DATE_ADDED, originalEntry.dateAddedSecs)
                        put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, originalEntry.dateModifiedMillis / 1000)
                        originalEntry.sourceDateTakenMillis?.let { put("datetaken", it) }
                    }
                    try {
                        context.contentResolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            true
        } else {
            false
        }
    }

    val vaultEntries: StateFlow<List<MediaEntry>> = mediaDao.getVaultEntries().map { list ->
        list.map { 
            val entry = gson.fromJson(it.entryJson, MediaEntry::class.java)
            // Update entry to point to vault path for correct rendering
            entry.copy(
                path = it.vaultPath,
                uri = Uri.fromFile(java.io.File(it.vaultPath)).toString()
            )
        }.sortedByDescending { it.bestTimestamp }
    }.stateIn(
        scope = repositoryScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    fun getContentResolver() = context.contentResolver

    fun createTransferWriteRequest(entries: List<MediaEntry>): IntentSenderRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return null
        }
        val uris = entries.mapNotNull { runCatching { Uri.parse(it.uri) }.getOrNull() }
        if (uris.isEmpty()) return null

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            for (uri in uris) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "rw")?.close()
                } catch (error: RecoverableSecurityException) {
                    return IntentSenderRequest.Builder(
                        error.userAction.actionIntent.intentSender
                    ).build()
                } catch (_: SecurityException) {
                    // Let the transfer report a concrete per-item failure if the
                    // provider does not expose a recoverable permission request.
                }
            }
            return null
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        } else {
            null
        }
    }

    suspend fun createTransferFolder(parentPath: String, name: String): Result<TransferDestination> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanName = name.trim()
                require(cleanName.isNotEmpty()) { "Folder name cannot be empty" }
                require(!cleanName.contains('/') && !cleanName.contains('\\')) { "Folder name cannot contain path separators" }

                val parent = File(parentPath)
                val folder = File(parent, cleanName)
                require(!folder.exists()) { "A folder with this name already exists" }
                if (!folder.mkdirs() && !folder.isDirectory) {
                    throw IOException("Could not create folder")
                }
                TransferDestination(
                    stableKey = folder.canonicalPath,
                    displayName = folder.name,
                    path = folder.canonicalPath
                )
            }
        }

    suspend fun transferMedia(
        entries: List<MediaEntry>,
        destination: TransferDestination,
        mode: TransferMode,
        conflictPolicy: ConflictPolicy,
        onProgress: (TransferProgress) -> Unit
    ): TransferSummary = withContext(Dispatchers.IO) {
        val destinationDir = File(destination.path)
        if ((!destinationDir.exists() && !destinationDir.mkdirs()) || !destinationDir.isDirectory) {
            return@withContext TransferSummary(
                mode = mode,
                succeeded = 0,
                skipped = 0,
                failures = entries.map { TransferItemFailure(it, "Destination folder is unavailable") }
            )
        }

        var succeeded = 0
        var skipped = 0
        val failures = mutableListOf<TransferItemFailure>()

        entries.forEachIndexed { index, entry ->
            val sourceFile = File(entry.path)
            onProgress(
                TransferProgress(
                    mode = mode,
                    completed = index,
                    total = entries.size,
                    currentName = sourceFile.name
                )
            )

            if (!sourceFile.exists()) {
                failures += TransferItemFailure(entry, "Source file no longer exists")
                return@forEachIndexed
            }

            if (
                mode == TransferMode.MOVE &&
                sourceFile.parentFile?.canonicalPath == destinationDir.canonicalPath
            ) {
                skipped++
                return@forEachIndexed
            }

            try {
                val directTarget = File(destinationDir, sourceFile.name)
                if (directTarget.exists() && conflictPolicy == ConflictPolicy.SKIP) {
                    skipped++
                    return@forEachIndexed
                }
                if (directTarget.exists() && conflictPolicy == ConflictPolicy.REPLACE) {
                    if (directTarget.canonicalPath == sourceFile.canonicalPath) {
                        skipped++
                        return@forEachIndexed
                    }
                    replaceEntry(entry, sourceFile, directTarget, mode)
                    succeeded++
                    return@forEachIndexed
                }

                val targetFile = if (conflictPolicy == ConflictPolicy.KEEP_BOTH) {
                    getAvailableTarget(destinationDir, sourceFile.name)
                } else {
                    directTarget
                }
                when (mode) {
                    TransferMode.COPY -> copyEntry(entry, sourceFile, targetFile)
                    TransferMode.MOVE -> moveEntry(entry, sourceFile, targetFile)
                }
                succeeded++
            } catch (error: Exception) {
                failures += TransferItemFailure(entry, error.message ?: error.javaClass.simpleName)
            }
        }

        onProgress(
            TransferProgress(
                mode = mode,
                completed = entries.size,
                total = entries.size,
                currentName = ""
            )
        )

        if (succeeded > 0) {
            // MediaStore generation changes after a successful transfer. Querying here
            // also updates paths whose DATE_MODIFIED value did not change.
            syncWithMediaStore()
        }

        TransferSummary(mode, succeeded, skipped, failures)
    }

    private suspend fun replaceEntry(
        entry: MediaEntry,
        sourceFile: File,
        targetFile: File,
        mode: TransferMode
    ) {
        val hasDirectWriteAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        if (!hasDirectWriteAccess) {
            throw IOException("Replacing existing files requires all-files access")
        }

        val destinationDir = targetFile.parentFile ?: throw IOException("Invalid destination")
        val transferToken = System.nanoTime().toString()
        val temporary = File(destinationDir, ".pixel-transfer-$transferToken.tmp")
        val backup = File(destinationDir, ".pixel-backup-$transferToken.tmp")
        var targetBackedUp = false
        var targetCommitted = false

        try {
            sourceFile.copyTo(temporary, overwrite = false)
            if (sourceFile.length() > 0L && temporary.length() != sourceFile.length()) {
                throw IOException("Destination verification failed")
            }

            if (!targetFile.renameTo(backup)) {
                throw IOException("Could not prepare the existing destination for replacement")
            }
            targetBackedUp = true

            if (!temporary.renameTo(targetFile)) {
                throw IOException("Could not commit replacement")
            }
            targetCommitted = true
            targetFile.setLastModified(entry.dateModifiedMillis)
            backup.delete()

            val targetUri = scanFile(targetFile, entry.sourceMimeType)
            if (mode == TransferMode.MOVE) {
                val wasFavourite = mediaDao.isFavourite(entry.contentId).first()
                val removed = runCatching {
                    context.contentResolver.delete(Uri.parse(entry.uri), null, null) > 0 || sourceFile.delete()
                }.getOrDefault(false)
                if (!removed && sourceFile.exists()) {
                    throw IOException("Replaced the destination, but could not remove the source")
                }
                if (wasFavourite) {
                    targetUri?.lastPathSegment?.toLongOrNull()?.let { newId ->
                        mediaDao.addFavourite(com.pixel.gallery.data.local.entity.FavouriteEntry(newId))
                        mediaDao.removeFavourite(entry.contentId)
                    }
                }
            }
        } catch (error: Exception) {
            if (targetBackedUp && !targetCommitted && !targetFile.exists() && backup.exists()) {
                backup.renameTo(targetFile)
            }
            throw error
        } finally {
            if (temporary.exists()) temporary.delete()
            if (targetCommitted && backup.exists()) backup.delete()
        }
    }

    private suspend fun moveEntry(entry: MediaEntry, sourceFile: File, targetFile: File) {
        val sourceVolume = StorageUtils.getVolumePath(context, sourceFile.absolutePath)
        val targetVolume = StorageUtils.getVolumePath(context, targetFile.absolutePath)
        val sameVolume = sourceVolume != null && sourceVolume == targetVolume

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sameVolume) {
            val relativePath = StorageUtils.PathSegments(context, targetFile.absolutePath).relativeDir
                ?: throw IOException("Could not resolve destination path")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath.replace(File.separatorChar, '/'))
                if (targetFile.name != sourceFile.name) {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, targetFile.name)
                }
            }
            val updated = context.contentResolver.update(Uri.parse(entry.uri), values, null, null)
            if (updated > 0) return
        }

        if (sameVolume && sourceFile.renameTo(targetFile)) {
            targetFile.setLastModified(entry.dateModifiedMillis)
            context.contentResolver.delete(Uri.parse(entry.uri), null, null)
            scanFile(targetFile, entry.sourceMimeType)
            return
        }

        val wasFavourite = mediaDao.isFavourite(entry.contentId).first()
        val copiedUri = copyEntry(entry, sourceFile, targetFile)

        val deleted = runCatching {
            context.contentResolver.delete(Uri.parse(entry.uri), null, null) > 0 || sourceFile.delete()
        }.getOrDefault(false)
        if (!deleted && sourceFile.exists()) {
            // The destination is a valid copy, but this item did not complete as a move.
            throw IOException("Copied the item, but could not remove the source")
        }

        if (wasFavourite) {
            copiedUri?.lastPathSegment?.toLongOrNull()?.let { newId ->
                mediaDao.addFavourite(com.pixel.gallery.data.local.entity.FavouriteEntry(newId))
                mediaDao.removeFavourite(entry.contentId)
            }
        }
    }

    private suspend fun copyEntry(entry: MediaEntry, sourceFile: File, targetFile: File): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return copyEntryWithMediaStore(entry, sourceFile, targetFile)
            }.onFailure {
                android.util.Log.w("MediaRepository", "MediaStore copy failed, trying file fallback", it)
            }
        }

        sourceFile.copyTo(targetFile, overwrite = false)
        targetFile.setLastModified(entry.dateModifiedMillis)
        return scanFile(targetFile, entry.sourceMimeType)
    }

    private fun copyEntryWithMediaStore(entry: MediaEntry, sourceFile: File, targetFile: File): Uri {
        val relativePath = StorageUtils.PathSegments(context, targetFile.absolutePath).relativeDir
            ?: throw IOException("Could not resolve destination path")
        val volumeName = getMediaStoreVolumeName(targetFile.absolutePath)
        val collection = if (entry.sourceMimeType.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(volumeName)
        } else {
            MediaStore.Images.Media.getContentUri(volumeName)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, targetFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.sourceMimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath.replace(File.separatorChar, '/'))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            entry.sourceDateTakenMillis?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }

        val resolver = context.contentResolver
        val targetUri = resolver.insert(collection, values)
            ?: throw IOException("Could not create destination media")
        try {
            val input = resolver.openInputStream(Uri.parse(entry.uri)) ?: FileInputStream(sourceFile)
            input.use { source ->
                resolver.openOutputStream(targetUri, "w")?.use { target ->
                    source.copyTo(target)
                } ?: throw IOException("Could not open destination")
            }
            resolver.update(
                targetUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            return targetUri
        } catch (error: Exception) {
            runCatching { resolver.delete(targetUri, null, null) }
            throw error
        }
    }

    private fun getMediaStoreVolumeName(path: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return MediaStore.VOLUME_EXTERNAL
        val targetVolume = StorageUtils.getVolumePath(context, path)
        val primaryVolume = StorageUtils.getPrimaryVolumePath(context)
        return if (targetVolume == null || targetVolume == primaryVolume) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            File(targetVolume.trimEnd(File.separatorChar)).name
        }
    }

    private fun getAvailableTarget(directory: File, originalName: String): File {
        val availableName = getAvailableTransferName(originalName) { name ->
            File(directory, name).exists()
        }
        return File(directory, availableName)
    }

    private suspend fun scanFile(file: File, mimeType: String): Uri? =
        suspendCancellableCoroutine { continuation ->
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { _, uri ->
                if (continuation.isActive) continuation.resume(uri)
            }
        }

    suspend fun syncWithMediaStore() = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver


        
        // Optimize: Use Generation API (API 30+) to skip scan if nothing changed in MediaStore
        var currentGeneration = 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                currentGeneration = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
                val lastSynced = settingsRepository.lastSyncedGeneration.first()
                if (lastSynced > 0L && currentGeneration == lastSynced) {
                    android.util.Log.d("MediaRepository", "MediaStore generation unchanged ($currentGeneration). Skipping sync.")
                    return@withContext
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaRepository", "Failed to check MediaStore generation", e)
            }
        }

        val knownEntries = mediaDao.getKnownEntries().associateBy { it.contentId }
        val newEntries = mutableListOf<MediaEntry>()
        val currentIds = mutableSetOf<Long>()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DURATION,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.DATE_TAKEN else MediaStore.MediaColumns.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.MediaColumns.IS_TRASHED else MediaStore.MediaColumns.DATA // Just a dummy for old versions
        )

        // Query Images
        queryMediaStore(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, knownEntries, newEntries, currentIds, false)
        queryMediaStore(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, knownEntries, newEntries, currentIds, true)
        
        // Query Videos
        queryMediaStore(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, knownEntries, newEntries, currentIds, false)
        queryMediaStore(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, knownEntries, newEntries, currentIds, true)

        if (newEntries.isNotEmpty()) {
            mediaDao.insertAll(newEntries)
        }

        // Handle deletions
        val obsoleteIds = knownEntries.keys.filter { it !in currentIds }
        if (obsoleteIds.isNotEmpty()) {
            mediaDao.deleteByIds(obsoleteIds)
        }

        // Save generation after successful sync
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && currentGeneration > 0L) {
            try {
                settingsRepository.setLastSyncedGeneration(currentGeneration)
            } catch (e: Exception) {
                android.util.Log.e("MediaRepository", "Failed to save synced generation", e)
            }
        }
    }

    private fun queryMediaStore(
        resolver: ContentResolver,
        uri: android.net.Uri,
        projection: Array<String>,
        knownEntries: Map<Long, com.pixel.gallery.data.local.dao.KnownEntry>,
        newEntries: MutableList<MediaEntry>,
        currentIds: MutableSet<Long>,
        queryTrashed: Boolean
    ) {
        val queryArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bundle().apply {
                putInt(
                    MediaStore.QUERY_ARG_MATCH_TRASHED,
                    if (queryTrashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE
                )
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, null)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, null)
            }
        } else {
            null
        }

        val selection = if (queryTrashed && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Trash not supported natively below API 30
            return 
        } else {
            null
        }

        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && queryArgs != null) {
            resolver.query(uri, projection, queryArgs, null)
        } else {
            resolver.query(uri, projection, selection, null, null)
        }

        cursor?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val rotationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val takenColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                currentIds.add(id)
                val modified = cursor.getLong(modifiedColumn) * 1000
                val path = cursor.getString(dataColumn) ?: ""
                val mimeType = cursor.getString(mimeColumn) ?: "image/jpeg"

                // Also update if trashing status changed
                val knownEntry = knownEntries[id]
                if (
                    knownEntry?.dateModifiedMillis != modified ||
                    knownEntry.path != path ||
                    knownEntry.isTrashed != queryTrashed
                ) {
                    val mediaStoreTaken = if (takenColumn != -1) cursor.getLong(takenColumn) else 0L
                    
                    val addedMillis = cursor.getLong(addedColumn) * 1000
                    // Match normal gallery chronology: capture time first, then the
                    // file's modification time, and only then the MediaStore import time.
                    // DATE_ADDED is often identical for a whole imported batch and cannot
                    // provide a meaningful or deterministic photo order.
                    var bestTime = when {
                        mediaStoreTaken > 0L -> mediaStoreTaken
                        modified > 0L -> modified
                        else -> addedMillis
                    }

                    val isRecentlyAdded =
                        addedMillis > 0L &&
                            Math.abs(System.currentTimeMillis() - addedMillis) < 600000

                    if (mediaStoreTaken <= 0L && (bestTime == 0L || isRecentlyAdded)) {
                        var foundExif = false
                        if (mimeType.startsWith("image/") && path.isNotEmpty()) {
                            try {
                                val exif = ExifInterface(path)
                                val exifTime = exif.dateTime
                                if (exifTime != null && exifTime > 0) {
                                    bestTime = exifTime
                                    foundExif = true
                                }
                            } catch (e: Exception) { }
                        }
                        
                        if (!foundExif && path.isNotEmpty()) {
                            try {
                                val fileTime = java.io.File(path).lastModified()
                                if (fileTime > 0 && (bestTime == 0L || fileTime < bestTime - 10000)) {
                                    bestTime = fileTime
                                }
                            } catch (e: Exception) { }
                        }
                    }

                    if (bestTime == 0L) bestTime = modified

                    newEntries.add(
                        MediaEntry(
                            contentId = id,
                            uri = uri.buildUpon().appendPath(id.toString()).toString(),
                            path = path,
                            sourceMimeType = mimeType,
                            width = cursor.getInt(widthColumn),
                            height = cursor.getInt(heightColumn),
                            sourceRotationDegrees = cursor.getInt(rotationColumn),
                            sizeBytes = cursor.getLong(sizeColumn),
                            dateAddedSecs = cursor.getLong(addedColumn),
                            dateModifiedMillis = modified,
                            sourceDateTakenMillis = if (mediaStoreTaken > 0) mediaStoreTaken else null,
                            durationMillis = cursor.getLong(durationColumn),
                            isTrashed = queryTrashed,
                            bestTimestamp = bestTime
                        )
                    )
                }
            }
        }
    }
}
