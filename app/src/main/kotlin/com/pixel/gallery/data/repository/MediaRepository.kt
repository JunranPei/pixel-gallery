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
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.exifinterface.media.ExifInterface
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.local.dao.KnownEntry
import com.pixel.gallery.data.local.dao.MediaDao
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.local.entity.VaultEntry
import com.google.gson.Gson
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.ConflictPolicy
import com.pixel.gallery.model.isSameTransferDirectory
import com.pixel.gallery.model.TransferItemFailure
import com.pixel.gallery.model.TransferMode
import com.pixel.gallery.model.TransferProgress
import com.pixel.gallery.model.TransferSummary
import com.pixel.gallery.model.ReplacementRecoveryAction
import com.pixel.gallery.model.ReplacementStage
import com.pixel.gallery.model.getAvailableTransferName
import com.pixel.gallery.model.isVerifiedTransferSize
import com.pixel.gallery.model.replacementRecoveryAction
import com.pixel.gallery.utils.StorageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.indexedbmp.IndexedBmpStore
import io.github.indexedheif.IndexedHeifStore
import io.github.indexedjpeg.IndexedJpegStore
import io.github.indexedjxl.IndexedJxlStore
import io.github.indexedpng.IndexedPngStore
import io.github.indexedraw.IndexedRawStore
import io.github.indexedtiff.IndexedTiffStore
import io.github.indexedwebp.IndexedWebpStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val settingsRepository: SettingsRepository
) {
    data class VaultRestoreResult(
        val restoredIds: List<Long>,
        val failedIds: List<Long>
    )

    private data class ReplaceJournal(
        val sourcePath: String,
        val targetPath: String,
        val temporaryPath: String,
        val backupPath: String,
        val mode: TransferMode,
        val stage: ReplacementStage
    )

    private enum class TransferOutcome { COMPLETED, SKIPPED }

    private val gson = Gson()
    private val repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val replacementRecoveryComplete = CompletableDeferred<Unit>()
    private val mediaMutationMutex = Mutex()
    private val syncMutex = Mutex()
    @Volatile private var mediaMutationInProgress = false

    private val jpegIndexStore by lazy { IndexedJpegStore(context) }
    private val pngIndexStore by lazy { IndexedPngStore(context) }
    private val tiffIndexStore by lazy { IndexedTiffStore(context) }
    private val webpIndexStore by lazy { IndexedWebpStore(context) }
    private val rawIndexStore by lazy { IndexedRawStore(context) }
    private val heifIndexStore by lazy { IndexedHeifStore(context) }
    private val bmpIndexStore by lazy { IndexedBmpStore(context) }
    private val jxlIndexStore by lazy { IndexedJxlStore(context) }

    private suspend fun <T> withMediaMutation(block: suspend () -> T): T =
        mediaMutationMutex.withLock {
            mediaMutationInProgress = true
            try {
                block()
            } finally {
                mediaMutationInProgress = false
            }
        }

    init {
        repositoryScope.launch(Dispatchers.IO) {
            try {
                recoverInterruptedReplacements()
                val allPaths = mediaDao.getAllMediaPaths()
                val missingEntries = allPaths.filter { entry ->
                    !java.io.File(entry.path).exists()
                }

                if (missingEntries.isNotEmpty()) {
                    val clearedPaths = deletePersistentIndexes(missingEntries.map { it.path })
                    val removableEntries = missingEntries.filter { entry ->
                        entry.path.isBlank() || entry.path in clearedPaths
                    }
                    mediaDao.deleteByIds(removableEntries.map { it.contentId })
                    android.util.Log.d(
                        "MediaRepository",
                        "Startup physical check: Removed ${removableEntries.size} missing entries after index cleanup.",
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaRepository", "Failed to run startup physical check", e)
            } finally {
                replacementRecoveryComplete.complete(Unit)
            }
        }
    }

    val allEntries: StateFlow<List<MediaEntry>> = combine(
        mediaDao.observeAllEntriesChanges().map { mediaDao.getAllEntriesPaged() },
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
        mediaDao.observeFavouritesChanges().map { mediaDao.getFavouritesPaged() },
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

    val trash: StateFlow<List<MediaEntry>> = mediaDao.observeTrashChanges().map {
        mediaDao.getTrashPaged()
    }.stateIn(
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

    suspend fun trashMediaBulk(uriStrings: List<String>): Boolean = withMediaMutation {
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = uriStrings.map { Uri.parse(it) }

                if (Environment.isExternalStorageManager()) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_TRASHED, 1)
                    }
                    uris.forEach { uri ->
                        context.contentResolver.update(uri, values, null, null)
                    }
                    runCatching { syncWithMediaStore(force = true) }
                        .onFailure { android.util.Log.e("MediaRepository", "Trash sync failed", it) }
                    true
                } else {
                    val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
                    MainActivity.launchIntentSender(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    false
                }
            } else {
                false
            }
        }
    }

    private fun deletePersistentIndexes(sourcePaths: Iterable<String>): Set<String> = buildSet {
        sourcePaths.asSequence()
            .distinct()
            .forEach { sourcePath ->
                if (sourcePath.isBlank()) {
                    add(sourcePath)
                    return@forEach
                }
                val failures = buildList {
                    if (!runCatching { jpegIndexStore.delete(sourcePath) }.getOrDefault(false)) add("JPEG")
                    if (!runCatching { pngIndexStore.delete(sourcePath) }.getOrDefault(false)) add("PNG")
                    if (!runCatching { tiffIndexStore.delete(sourcePath) }.getOrDefault(false)) add("TIFF")
                    if (!runCatching { webpIndexStore.delete(sourcePath) }.getOrDefault(false)) add("WebP")
                    if (!runCatching { rawIndexStore.delete(sourcePath) }.getOrDefault(false)) add("RAW")
                    if (!runCatching { heifIndexStore.delete(sourcePath) }.getOrDefault(false)) add("HEIF")
                    if (!runCatching { bmpIndexStore.delete(sourcePath) }.getOrDefault(false)) add("BMP")
                    if (!runCatching { jxlIndexStore.delete(sourcePath) }.getOrDefault(false)) add("JXL")
                }
                if (failures.isNotEmpty()) {
                    android.util.Log.w(
                        "MediaRepository",
                        "Failed to delete ${failures.joinToString()} indexes for $sourcePath",
                    )
                } else {
                    add(sourcePath)
                }
            }
    }

    private fun relocatePersistentIndexes(sourcePath: String, destinationPath: String): Boolean {
        if (sourcePath == destinationPath) return true
        val failures = buildList {
            if (!runCatching { jpegIndexStore.relocate(sourcePath, destinationPath) }.getOrDefault(false)) add("JPEG")
            if (!runCatching { pngIndexStore.relocate(sourcePath, destinationPath) }.getOrDefault(false)) add("PNG")
            if (!runCatching { tiffIndexStore.relocate(sourcePath, destinationPath) }.getOrDefault(false)) add("TIFF")
            if (!runCatching { webpIndexStore.relocate(sourcePath, destinationPath) }.getOrDefault(false)) add("WebP")
            if (!runCatching { rawIndexStore.relocate(sourcePath, destinationPath) }.getOrDefault(false)) add("RAW")
            if (!runCatching { heifIndexStore.relocate(sourcePath, destinationPath) }.getOrDefault(false)) add("HEIF")
            if (!runCatching { bmpIndexStore.relocate(sourcePath, destinationPath) }.getOrDefault(false)) add("BMP")
            if (!runCatching { jxlIndexStore.relocate(sourcePath, destinationPath) }.getOrDefault(false)) add("JXL")
        }
        if (failures.isNotEmpty()) {
            android.util.Log.w(
                "MediaRepository",
                "Failed to relocate ${failures.joinToString()} indexes from $sourcePath to $destinationPath",
            )
        }
        return failures.isEmpty()
    }

    suspend fun restoreMedia(id: Long, uriString: String) = withContext(Dispatchers.IO) {
        restoreMediaBulk(listOf(uriString))
    }

    suspend fun restoreMediaBulk(uriStrings: List<String>): Boolean = withMediaMutation {
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = uriStrings.map { Uri.parse(it) }

                if (Environment.isExternalStorageManager()) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_TRASHED, 0)
                    }
                    uris.forEach { uri ->
                        context.contentResolver.update(uri, values, null, null)
                    }
                    runCatching { syncWithMediaStore(force = true) }
                        .onFailure { android.util.Log.e("MediaRepository", "Restore sync failed", it) }
                    true
                } else {
                    val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, false)
                    MainActivity.launchIntentSender(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    false
                }
            } else {
                false
            }
        }
    }

    suspend fun deleteMediaBulk(uriStrings: List<String>): Boolean = withMediaMutation {
        withContext(Dispatchers.IO) {
            val uris = uriStrings.map { Uri.parse(it) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                MainActivity.launchIntentSender(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                false
            } else {
                uris.forEach { uri ->
                    context.contentResolver.delete(uri, null, null)
                }
                runCatching { syncWithMediaStore(force = true) }
                    .onFailure { android.util.Log.e("MediaRepository", "Delete sync failed", it) }
                true
            }
        }
    }

    // --- Vault ---
    suspend fun moveToVault(entry: MediaEntry): Boolean = withMediaMutation {
        withContext(Dispatchers.IO) {
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
                if (originalFile.delete() && !originalFile.exists()) {
                    true
                } else {
                    vaultFile.delete()
                    false
                }
            } catch (e: Exception) {
                false
            }
        }

        if (moved) {
            relocatePersistentIndexes(originalFile.absolutePath, vaultFile.absolutePath)
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
    }

    suspend fun restoreFromVault(id: Long): Boolean = withMediaMutation {
        withContext(Dispatchers.IO) {
        val vaultEntry = mediaDao.getVaultEntry(id) ?: return@withContext false
        val vaultFile = java.io.File(vaultEntry.vaultPath)
        if (!vaultFile.isFile) return@withContext false

        val requestedFile = java.io.File(vaultEntry.originalPath)
        val destinationDirectory = requestedFile.parentFile ?: return@withContext false
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) return@withContext false
        val originalFile = if (requestedFile.exists()) {
            java.io.File(
                destinationDirectory,
                getAvailableTransferName(requestedFile.name) { candidate ->
                    java.io.File(destinationDirectory, candidate).exists()
                }
            )
        } else {
            requestedFile
        }
        val vaultLastModified = vaultFile.lastModified()
        var copiedDestination = false
        
        val restored = if (vaultFile.renameTo(originalFile)) {
            true
        } else {
            try {
                vaultFile.copyTo(originalFile, overwrite = false)
                copiedDestination = true
                if (!isVerifiedTransferSize(vaultFile.length(), originalFile.length())) {
                    originalFile.delete()
                    return@withContext false
                }
                originalFile.setLastModified(vaultLastModified)
                vaultFile.delete() && !vaultFile.exists()
            } catch (e: Exception) {
                if (copiedDestination && originalFile.exists() && vaultFile.exists()) {
                    originalFile.delete()
                }
                false
            }
        }

        if (restored) {
            relocatePersistentIndexes(vaultFile.absolutePath, originalFile.absolutePath)
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
    }

    suspend fun restoreFromVaultBulk(ids: List<Long>): VaultRestoreResult = withContext(Dispatchers.IO) {
        val restoredIds = mutableListOf<Long>()
        val failedIds = mutableListOf<Long>()

        ids.distinct().forEach { id ->
            val restored = runCatching { restoreFromVault(id) }
                .onFailure { error ->
                    android.util.Log.e("MediaRepository", "Failed to restore vault item $id", error)
                }
                .getOrDefault(false)
            if (restored) {
                restoredIds += id
            } else {
                failedIds += id
            }
        }

        VaultRestoreResult(restoredIds = restoredIds, failedIds = failedIds)
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

    suspend fun createTransferFolder(parent: TransferDestination, name: String): Result<TransferDestination> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanName = name.trim()
                require(cleanName.isNotEmpty()) { "Folder name cannot be empty" }
                require(!cleanName.contains('/') && !cleanName.contains('\\')) { "Folder name cannot contain path separators" }
                require(cleanName != "." && cleanName != "..") { "Invalid folder name" }

                parent.documentUri?.let { parentDocumentUri ->
                    val parentUri = Uri.parse(parentDocumentUri)
                    require(findSafChild(parentUri, cleanName) == null) { "A folder with this name already exists" }
                    val createdUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        cleanName
                    ) ?: throw IOException("Could not create folder")
                    val mappedPath = File(parent.path, cleanName).canonicalPath
                    return@runCatching TransferDestination(
                        stableKey = createdUri.toString(),
                        displayName = cleanName,
                        path = mappedPath,
                        documentUri = createdUri.toString()
                    )
                }

                val parentFile = File(parent.path)
                val folder = File(parentFile, cleanName)
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
    ): TransferSummary = withMediaMutation {
            withContext(Dispatchers.IO) {
            replacementRecoveryComplete.await()
            val destinationDir = File(destination.path)
        if (
            destination.documentUri == null &&
            ((!destinationDir.exists() && !destinationDir.mkdirs()) || !destinationDir.isDirectory)
        ) {
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

            // Treat copying or moving a file into the directory it already
            // occupies as a no-op. In particular, KEEP_BOTH must never turn
            // the source itself into an accidental numbered duplicate.
            if (isSameTransferDirectory(entry.path, destination.path)) {
                skipped++
                return@forEachIndexed
            }

            try {
                destination.documentUri?.let { documentUri ->
                    when (
                        transferEntryToSaf(
                            entry = entry,
                            sourceFile = sourceFile,
                            destination = destination,
                            parentUri = Uri.parse(documentUri),
                            mode = mode,
                            conflictPolicy = conflictPolicy
                        )
                    ) {
                        TransferOutcome.COMPLETED -> succeeded++
                        TransferOutcome.SKIPPED -> skipped++
                    }
                    return@forEachIndexed
                }

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

        if (succeeded > 0) {
            // Do not report 100% until the final MediaStore state and Room snapshot agree.
            // Force this pass because observer-driven syncs may have recorded an
            // intermediate MediaStore generation while the batch was still running.
            runCatching { syncWithMediaStore(force = true) }
                .onFailure { android.util.Log.e("MediaRepository", "Transfer succeeded but MediaStore sync failed", it) }
        }

        onProgress(
            TransferProgress(
                mode = mode,
                completed = entries.size,
                total = entries.size,
                currentName = ""
            )
        )

            TransferSummary(mode, succeeded, skipped, failures)
            }
    }

    fun isMediaMutationInProgress(): Boolean = mediaMutationInProgress

    suspend fun syncWhenMediaMutationsIdle(force: Boolean = false) =
        mediaMutationMutex.withLock { syncWithMediaStore(force) }

    private data class SafChild(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long
    )

    private suspend fun transferEntryToSaf(
        entry: MediaEntry,
        sourceFile: File,
        destination: TransferDestination,
        parentUri: Uri,
        mode: TransferMode,
        conflictPolicy: ConflictPolicy
    ): TransferOutcome {
        if (conflictPolicy == ConflictPolicy.REPLACE) {
            throw IOException("Replace is not available for system document destinations")
        }

        val children = listSafChildren(parentUri)
        cleanupStaleSafTransfers(children)
        val visibleChildren = children.filterNot { it.name.startsWith(".pixel-transfer-") }
        val existing = visibleChildren.firstOrNull { it.name.equals(sourceFile.name, ignoreCase = true) }
        if (existing != null && conflictPolicy == ConflictPolicy.SKIP) {
            return TransferOutcome.SKIPPED
        }
        val existingNames = visibleChildren.mapTo(mutableSetOf()) { it.name.lowercase() }
        val targetName = if (conflictPolicy == ConflictPolicy.KEEP_BOTH) {
            getAvailableTransferName(sourceFile.name) { it.lowercase() in existingNames }
        } else {
            sourceFile.name
        }

        val temporaryName = ".pixel-transfer-${UUID.randomUUID()}-${sourceFile.name}"
        val sourceLastModified = sourceFile.lastModified()
        var targetUri = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            entry.sourceMimeType,
            temporaryName
        ) ?: throw IOException("Could not create temporary destination document")
        val targetFile = File(destination.path, targetName)
        var scannedUri: Uri? = null

        try {
            copyToUriVerified(entry, sourceFile, targetUri)
            targetUri = DocumentsContract.renameDocument(
                context.contentResolver,
                targetUri,
                targetName
            ) ?: throw IOException("Could not commit destination document")
            if (sourceLastModified > 0L && targetFile.isFile) {
                targetFile.setLastModified(sourceLastModified)
            }
            val committedSize = queryDocumentSize(targetUri)
            if (!isVerifiedTransferSize(sourceFile.length(), committedSize)) {
                throw IOException("Committed destination verification failed")
            }
            scannedUri = scanFile(targetFile, entry.sourceMimeType)
                ?: throw IOException("Destination could not be indexed; source was kept")

            if (mode == TransferMode.MOVE) {
                val wasFavourite = mediaDao.isFavourite(entry.contentId).first()
                if (!removeOriginal(entry, sourceFile)) {
                    throw IOException("Destination is valid, but the source could not be removed")
                }
                relocatePersistentIndexes(sourceFile.absolutePath, targetFile.absolutePath)
                migrateFavourite(entry.contentId, scannedUri, wasFavourite)
            }
            return TransferOutcome.COMPLETED
        } catch (error: Exception) {
            // Roll back the new document whenever the source still exists. If source
            // removal already completed, retaining the verified target is safer.
            if (sourceFile.exists()) {
                scannedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, targetUri) }
                scanFile(sourceFile, entry.sourceMimeType)
            }
            throw error
        }
    }

    private fun listSafChildren(parentUri: Uri): List<SafChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri,
            DocumentsContract.getDocumentId(parentUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableListOf<SafChild>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else -1L
                val lastModified = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) {
                    cursor.getLong(modifiedColumn)
                } else {
                    0L
                }
                result += SafChild(
                    uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, id),
                    name = name,
                    size = size,
                    lastModified = lastModified
                )
            }
        }
        return result
    }

    private fun findSafChild(parentUri: Uri, name: String): SafChild? =
        listSafChildren(parentUri).firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun cleanupStaleSafTransfers(children: List<SafChild>) {
        val staleBefore = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        children.filter {
            it.name.startsWith(".pixel-transfer-") &&
                it.lastModified in 1 until staleBefore
        }.forEach { child ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, child.uri) }
        }
    }

    private fun copyToUriVerified(
        entry: MediaEntry,
        sourceFile: File,
        targetUri: Uri,
        targetFile: File? = null
    ) {
        val expectedBytes = sourceFile.length()
        val resolver = context.contentResolver
        val source = resolver.openInputStream(Uri.parse(entry.uri)) ?: FileInputStream(sourceFile)
        source.use { input ->
            val descriptor = resolver.openFileDescriptor(targetUri, "w")
                ?: throw IOException("Could not open destination")
            descriptor.use { parcelFileDescriptor ->
                FileOutputStream(parcelFileDescriptor.fileDescriptor).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
        }

        val providerSize = queryDocumentSize(targetUri)
        val actualBytes = when {
            providerSize >= 0L -> providerSize
            targetFile != null && targetFile.exists() -> targetFile.length()
            else -> -1L
        }
        if (!isVerifiedTransferSize(expectedBytes, actualBytes)) {
            throw IOException("Destination verification failed ($actualBytes of $expectedBytes bytes)")
        }
    }

    private fun queryDocumentSize(uri: Uri): Long {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_SIZE)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun removeOriginal(entry: MediaEntry, sourceFile: File): Boolean {
        runCatching { context.contentResolver.delete(Uri.parse(entry.uri), null, null) }
        if (!sourceFile.exists()) return true
        return runCatching { sourceFile.delete() && !sourceFile.exists() }.getOrDefault(false)
    }

    private suspend fun migrateFavourite(sourceId: Long, targetUri: Uri, wasFavourite: Boolean) {
        if (!wasFavourite) return
        val newId = targetUri.lastPathSegment?.toLongOrNull() ?: run {
            android.util.Log.w("MediaRepository", "Could not migrate favourite for target URI $targetUri")
            return
        }
        mediaDao.addFavourite(com.pixel.gallery.data.local.entity.FavouriteEntry(newId))
        mediaDao.removeFavourite(sourceId)
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
        val transferToken = UUID.randomUUID().toString()
        val temporary = File(destinationDir, ".pixel-transfer-$transferToken.tmp")
        val backup = File(destinationDir, ".pixel-backup-$transferToken.tmp")
        val journalFile = File(replaceJournalDirectory(), "$transferToken.json")
        var journal = ReplaceJournal(
            sourcePath = sourceFile.canonicalPath,
            targetPath = targetFile.canonicalPath,
            temporaryPath = temporary.canonicalPath,
            backupPath = backup.canonicalPath,
            mode = mode,
            stage = ReplacementStage.CREATED
        )
        var targetBackedUp = false
        var targetCommitted = false
        val sourceLastModified = sourceFile.lastModified()
        val wasFavourite = mode == TransferMode.MOVE && mediaDao.isFavourite(entry.contentId).first()

        writeReplaceJournal(journalFile, journal)
        try {
            copyFileVerified(sourceFile, temporary)
            journal = journal.copy(stage = ReplacementStage.TEMP_READY)
            writeReplaceJournal(journalFile, journal)

            if (!targetFile.renameTo(backup)) {
                throw IOException("Could not prepare the existing destination for replacement")
            }
            targetBackedUp = true
            journal = journal.copy(stage = ReplacementStage.TARGET_BACKED_UP)
            writeReplaceJournal(journalFile, journal)

            if (!temporary.renameTo(targetFile)) {
                throw IOException("Could not commit replacement")
            }
            targetCommitted = true
            journal = journal.copy(stage = ReplacementStage.TARGET_COMMITTED)
            writeReplaceJournal(journalFile, journal)
            targetFile.setLastModified(sourceLastModified)

            val targetUri = scanFile(targetFile, entry.sourceMimeType)
                ?: throw IOException("Replacement could not be indexed; original files were kept")
            if (mode == TransferMode.MOVE) {
                if (!removeOriginal(entry, sourceFile)) {
                    throw IOException("Replacement was rolled back because the source could not be removed")
                }
                relocatePersistentIndexes(sourceFile.absolutePath, targetFile.absolutePath)
                journal = journal.copy(stage = ReplacementStage.SOURCE_REMOVED)
                writeReplaceJournal(journalFile, journal)
                migrateFavourite(entry.contentId, targetUri, wasFavourite)
            }

            if (!backup.exists() || backup.delete()) {
                journalFile.delete()
            }
        } catch (error: Exception) {
            val sourceStillExists = sourceFile.exists()
            if (mode == TransferMode.COPY || sourceStillExists) {
                val restored = rollbackReplacement(
                    targetFile = targetFile,
                    temporary = temporary,
                    backup = backup,
                    targetBackedUp = targetBackedUp,
                    targetCommitted = targetCommitted,
                    mimeType = entry.sourceMimeType
                )
                if (sourceStillExists) scanFile(sourceFile, entry.sourceMimeType)
                if (restored) journalFile.delete()
            }
            throw error
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun copyFileVerified(sourceFile: File, targetFile: File) {
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(targetFile, false).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        if (!isVerifiedTransferSize(sourceFile.length(), targetFile.length())) {
            throw IOException("Destination verification failed")
        }
    }

    private suspend fun rollbackReplacement(
        targetFile: File,
        temporary: File,
        backup: File,
        targetBackedUp: Boolean,
        targetCommitted: Boolean,
        mimeType: String
    ): Boolean {
        if (targetCommitted && targetFile.exists() && !targetFile.delete()) return false
        if (targetBackedUp && backup.exists() && !backup.renameTo(targetFile)) return false
        if (temporary.exists() && !temporary.delete()) return false
        if (targetBackedUp && targetFile.exists()) scanFile(targetFile, mimeType)
        return !backup.exists() && !temporary.exists() && (!targetBackedUp || targetFile.exists())
    }

    private fun replaceJournalDirectory(): File =
        File(context.filesDir, "media-transfer-journal").apply { mkdirs() }

    private fun writeReplaceJournal(file: File, journal: ReplaceJournal) {
        val temporary = File(file.parentFile, "${file.name}.new")
        val bytes = gson.toJson(journal).toByteArray(Charsets.UTF_8)
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            if (file.exists() && !file.delete()) throw IOException("Could not update transfer journal", it)
            if (!temporary.renameTo(file)) throw IOException("Could not update transfer journal", it)
        }
    }

    private fun recoverInterruptedReplacements() {
        val directory = replaceJournalDirectory()
        directory.listFiles { file -> file.extension == "json" }?.forEach { journalFile ->
            runCatching {
                val journal = gson.fromJson(journalFile.readText(), ReplaceJournal::class.java)
                val source = File(journal.sourcePath)
                val target = File(journal.targetPath)
                val temporary = File(journal.temporaryPath)
                val backup = File(journal.backupPath)
                val stabilized = when (
                    replacementRecoveryAction(journal.stage, journal.mode, source.exists())
                ) {
                    ReplacementRecoveryAction.CLEAN_UNCOMMITTED -> {
                        if (backup.exists() && !target.exists()) backup.renameTo(target)
                        if (temporary.exists()) temporary.delete()
                        target.exists() && !backup.exists() && !temporary.exists()
                    }
                    ReplacementRecoveryAction.ROLLBACK_TO_BACKUP -> {
                        if (target.exists()) target.delete()
                        if (!target.exists() && backup.exists()) backup.renameTo(target)
                        if (temporary.exists()) temporary.delete()
                        target.exists() && !backup.exists() && !temporary.exists()
                    }
                    ReplacementRecoveryAction.KEEP_COMMITTED -> {
                        if (temporary.exists()) temporary.delete()
                        if (backup.exists()) backup.delete()
                        target.exists() && !backup.exists() && !temporary.exists()
                    }
                }
                if (stabilized) journalFile.delete()
            }.onFailure {
                android.util.Log.e("MediaRepository", "Could not recover replacement journal ${journalFile.name}", it)
            }
        }
        directory.listFiles { file -> file.name.endsWith(".json.new") }?.forEach { it.delete() }
    }

    private suspend fun moveEntry(entry: MediaEntry, sourceFile: File, targetFile: File) {
        val sourceLastModified = sourceFile.lastModified()
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

        val wasFavourite = mediaDao.isFavourite(entry.contentId).first()
        if (sameVolume && sourceFile.renameTo(targetFile)) {
            targetFile.setLastModified(sourceLastModified)
            val scannedUri = scanFile(targetFile, entry.sourceMimeType)
            if (scannedUri == null) {
                if (targetFile.renameTo(sourceFile)) scanFile(sourceFile, entry.sourceMimeType)
                throw IOException("Move was rolled back because the destination could not be indexed")
            }
            val sourceUri = Uri.parse(entry.uri)
            if (scannedUri != sourceUri) {
                runCatching { context.contentResolver.delete(sourceUri, null, null) }
                migrateFavourite(entry.contentId, scannedUri, wasFavourite)
            }
            relocatePersistentIndexes(sourceFile.absolutePath, targetFile.absolutePath)
            return
        }

        val copiedUri = copyEntry(entry, sourceFile, targetFile)

        if (!removeOriginal(entry, sourceFile)) {
            // Restore the pre-move state when possible. If cleanup fails, both the
            // verified destination and source remain, which is still content-safe.
            runCatching { context.contentResolver.delete(copiedUri, null, null) }
            scanFile(sourceFile, entry.sourceMimeType)
            throw IOException("Move was rolled back because the source could not be removed")
        }

        relocatePersistentIndexes(sourceFile.absolutePath, targetFile.absolutePath)
        migrateFavourite(entry.contentId, copiedUri, wasFavourite)
    }

    private suspend fun copyEntry(entry: MediaEntry, sourceFile: File, targetFile: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return copyEntryWithMediaStore(entry, sourceFile, targetFile)
            }.onFailure {
                android.util.Log.w("MediaRepository", "MediaStore copy failed, trying file fallback", it)
            }
        }

        copyFileVerified(sourceFile, targetFile)
        targetFile.setLastModified(sourceFile.lastModified())
        return scanFile(targetFile, entry.sourceMimeType) ?: run {
            targetFile.delete()
            throw IOException("Destination could not be indexed; source was kept")
        }
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
            copyToUriVerified(entry, sourceFile, targetUri, targetFile)
            val published = resolver.update(
                targetUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            if (published != 1) throw IOException("Could not publish destination media")
            if (targetFile.isFile) {
                targetFile.setLastModified(sourceFile.lastModified())
            }
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
        withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mimeType)
                ) { _, uri ->
                    if (continuation.isActive) continuation.resume(uri)
                }
            }
        }

    suspend fun syncWithMediaStore(force: Boolean = false) = syncMutex.withLock {
        withContext(Dispatchers.IO) {
        val resolver = context.contentResolver


        
        // Optimize: Use Generation API (API 30+) to skip scan if nothing changed in MediaStore
        var currentGeneration = 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                currentGeneration = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
                val lastSynced = settingsRepository.lastSyncedGeneration.first()
                if (!force && lastSynced > 0L && currentGeneration == lastSynced) {
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
        val currentPaths = mutableSetOf<String>()
        val currentPathsById = mutableMapOf<Long, String>()

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
        queryMediaStore(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, knownEntries, newEntries, currentIds, currentPaths, currentPathsById, false)
        queryMediaStore(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, knownEntries, newEntries, currentIds, currentPaths, currentPathsById, true)
        
        // Query Videos
        queryMediaStore(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, knownEntries, newEntries, currentIds, currentPaths, currentPathsById, false)
        queryMediaStore(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, knownEntries, newEntries, currentIds, currentPaths, currentPathsById, true)

        val failedRelocationIds = mutableSetOf<Long>()
        relocatedIndexSourcePaths(knownEntries.values, currentPathsById).forEach { relocation ->
            if (!relocatePersistentIndexes(relocation.sourcePath, relocation.destinationPath)) {
                failedRelocationIds.add(relocation.contentId)
            }
        }
        val deletedSourcePaths = deletedIndexSourcePaths(
            knownEntries = knownEntries.values,
            currentIds = currentIds,
            currentPaths = currentPaths,
        )
        val clearedPaths = deletePersistentIndexes(deletedSourcePaths)
        val obsoleteIds = knownEntries.values.asSequence()
            .filter { known -> known.contentId !in currentIds }
            .filter { known ->
                known.path.isBlank() || known.path in currentPaths || known.path in clearedPaths
            }
            .map { it.contentId }
            .toList()
        if (failedRelocationIds.isNotEmpty()) {
            newEntries.removeAll { entry -> entry.contentId in failedRelocationIds }
        }
        // Publish additions/updates and removals as one Room invalidation. Emitting the
        // intermediate "old + new" list caused two full grid reorders and could make a
        // live Viewer briefly bind its current page to another media item.
        mediaDao.reconcileMedia(newEntries, obsoleteIds)

        // Save generation after successful sync
        val indexMaintenanceComplete =
            failedRelocationIds.isEmpty() && deletedSourcePaths.all { it in clearedPaths }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && currentGeneration > 0L && indexMaintenanceComplete) {
            try {
                settingsRepository.setLastSyncedGeneration(currentGeneration)
            } catch (e: Exception) {
                android.util.Log.e("MediaRepository", "Failed to save synced generation", e)
            }
        }
        }
    }

    private fun queryMediaStore(
        resolver: ContentResolver,
        uri: android.net.Uri,
        projection: Array<String>,
        knownEntries: Map<Long, KnownEntry>,
        newEntries: MutableList<MediaEntry>,
        currentIds: MutableSet<Long>,
        currentPaths: MutableSet<String>,
        currentPathsById: MutableMap<Long, String>,
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
                if (path.isNotEmpty()) {
                    currentPaths.add(path)
                    currentPathsById[id] = path
                }
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

internal fun deletedIndexSourcePaths(
    knownEntries: Collection<KnownEntry>,
    currentIds: Set<Long>,
    currentPaths: Set<String>,
): List<String> = knownEntries.asSequence()
    .filter { known -> known.contentId !in currentIds }
    .map { it.path }
    .filter { it.isNotBlank() && it !in currentPaths }
    .distinct()
    .toList()

internal data class IndexPathRelocation(
    val contentId: Long,
    val sourcePath: String,
    val destinationPath: String,
)

internal fun relocatedIndexSourcePaths(
    knownEntries: Collection<KnownEntry>,
    currentPathsById: Map<Long, String>,
): List<IndexPathRelocation> = knownEntries.asSequence()
    .mapNotNull { known ->
        val currentPath = currentPathsById[known.contentId]
        if (known.path.isBlank() || currentPath.isNullOrBlank() || currentPath == known.path) {
            null
        } else {
            IndexPathRelocation(known.contentId, known.path, currentPath)
        }
    }
    .distinct()
    .toList()
