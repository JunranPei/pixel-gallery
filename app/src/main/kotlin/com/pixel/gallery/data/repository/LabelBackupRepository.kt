package com.pixel.gallery.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.room.withTransaction
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class LabelExportResult(val labels: Int, val media: Int, val skipped: Int)
data class LabelImportPreview(
    val backup: LabelBackup,
    val resolution: LabelBackupMatches,
    val candidates: Map<Long, LabelMediaFingerprint>,
    val unreadable: Int,
)
data class LabelImportResult(val media: Int, val labelsAdded: Int, val linksAdded: Int)
class InvalidLabelBackupException(cause: Throwable) : IOException("Invalid label backup", cause)
class ChangedLabelMediaException : IOException("Files changed during import")

@Singleton
class LabelBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GalleryDatabase,
    private val mediaRepository: MediaRepository,
) {
    suspend fun export(uri: Uri, onProgress: (Int, Int) -> Unit): LabelExportResult = withContext(Dispatchers.IO) {
        val backup = mediaRepository.withMediaMutation {
            val snapshot = database.withTransaction {
                Triple(database.labelDao().getLabels(), database.labelDao().getMemberships(),
                    mediaRepository.currentActiveEntries())
            }
            val links = snapshot.second.groupBy { it.contentId }
            val entries = snapshot.third.filter { it.contentId in links }
            var skipped = links.size - entries.size
            val files = entries.mapIndexedNotNull { index, entry ->
                currentCoroutineContext().ensureActive()
                val result = try {
                    val fingerprint = fingerprint(entry)
                    BackupMedia("media-$index", fingerprint.sha256, fingerprint.sizeBytes,
                        fingerprint.fileName, fingerprint.relativePath,
                        links.getValue(entry.contentId).map { "label-${it.labelId}" })
                } catch (error: CancellationException) {
                    throw error
                } catch (_: IOException) {
                    skipped++; null
                } catch (_: SecurityException) {
                    skipped++; null
                }
                onProgress(index + 1, entries.size)
                result
            }
            require(files.size <= 100_000 && snapshot.first.size <= 5_000)
            LabelBackup(exportedAt = Instant.now().toString(),
                labels = snapshot.first.map { BackupLabel("label-${it.id}", it.name, it.systemKind) },
                media = files, skippedMedia = skipped)
        }
        currentCoroutineContext().ensureActive()
        val json = LabelBackupJson.gson.toJson(backup).toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_BACKUP_BYTES)
        // The same reader validates our own output, including reference and count limits.
        LabelBackupJson.read(json.toString(Charsets.UTF_8).reader())
        currentCoroutineContext().ensureActive()
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot create backup")
        stream.use {
            for (offset in json.indices step 64 * 1024) {
                currentCoroutineContext().ensureActive()
                it.write(json, offset, minOf(64 * 1024, json.size - offset))
            }
            it.flush()
        }
        currentCoroutineContext().ensureActive()
        LabelExportResult(backup.labels.size, backup.media.size, backup.skippedMedia)
    }

    suspend fun preview(uri: Uri, onProgress: (Int, Int) -> Unit): LabelImportPreview = withContext(Dispatchers.IO) {
        val backup = readBackup(uri)
        val sizes = backup.media.map { it.sizeBytes }.toSet()
        var unreadable = 0
        val fingerprints = mediaRepository.withMediaMutation {
            // A Room snapshot from before the migration may have different MediaStore IDs.
            mediaRepository.syncWithMediaStore(force = true)
            val entries = mediaRepository.currentActiveEntries()
            entries.mapIndexedNotNull { index, entry ->
                currentCoroutineContext().ensureActive()
                val result = try {
                    // Stat actual files; a stale cached SIZE must not hide an otherwise valid match.
                    if (File(entry.path).length() in sizes) fingerprint(entry) else null
                } catch (error: CancellationException) {
                    throw error
                } catch (_: IOException) {
                    unreadable++; null
                } catch (_: SecurityException) {
                    unreadable++; null
                }
                onProgress(index + 1, entries.size)
                result
            }
        }
        LabelImportPreview(backup, matchLabelBackup(backup, fingerprints), fingerprints.associateBy { it.contentId }, unreadable)
    }

    suspend fun apply(preview: LabelImportPreview): LabelImportResult = withContext(Dispatchers.IO) {
        val ids = preview.resolution.matches.values.toSet()
        mediaRepository.withValidLabelMedia(ids) {
            val activeIds = mediaRepository.currentActiveEntries().mapTo(hashSetOf()) { it.contentId }
            if (!activeIds.containsAll(ids)) throw LabelMediaUnavailableException("Some matched files are no longer in the library")
            // Preview may stay open while a file is edited, replaced, or moved by another app.
            val current = ids.toList().chunked(400).flatMap { database.mediaDao().getEntriesByIds(it) }
            for (entry in current) {
                val expected = preview.candidates.getValue(entry.contentId)
                val actual = fingerprint(entry)
                if (expected.sha256 != actual.sha256 || expected.sizeBytes != actual.sizeBytes ||
                    expected.relativePath != actual.relativePath) throw ChangedLabelMediaException()
            }
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                val dao = database.labelDao()
                val existing = dao.getLabels().toMutableList()
                val labelIds = mutableMapOf<String, Long>()
                var addedLabels = 0
                preview.backup.labels.forEach { label ->
                    currentCoroutineContext().ensureActive()
                    val normalized = normalizeLabelName(label.name)
                    val found = if (label.systemKind != null) existing.find { it.systemKind == label.systemKind }
                        else existing.find { it.normalizedName == normalized }
                    if (found != null) {
                        require(found.systemKind == label.systemKind) { "Label conflicts with a system label" }
                        labelIds[label.key] = found.id
                    } else {
                        require(label.systemKind == null) { "Missing system label" }
                        val entry = LabelEntry(name = label.name.trim(), normalizedName = normalized,
                            sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1)
                        val id = dao.insertLabel(entry)
                        existing += entry.copy(id = id)
                        labelIds[label.key] = id
                        addedLabels++
                    }
                }
                val before = dao.getMemberships().toHashSet()
                val links = preview.backup.media.flatMap { file ->
                    val id = preview.resolution.matches[file.key]
                    if (id == null) emptyList() else file.labelKeys.map { MediaLabelEntry(labelIds.getValue(it), id) }
                }.distinct().filterNot { it in before }
                links.chunked(500).forEach {
                    currentCoroutineContext().ensureActive()
                    dao.addMemberships(it)
                }
                // Room DAO calls can execute synchronously inside a transaction, without a suspension.
                // Observe cancellation before the transaction can mark those writes successful.
                currentCoroutineContext().ensureActive()
                LabelImportResult(ids.size, addedLabels, links.size)
            }
        }
    }

    private suspend fun readBackup(uri: Uri): LabelBackup {
        // Cap before parsing; malformed or truncated input never causes any database writes.
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BACKUP_BYTES) throw InvalidLabelBackupException(IOException("Backup too large"))
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("Cannot open backup")
        return try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(bytes.inputStream(), decoder).use(LabelBackupJson::read).also {
                currentCoroutineContext().ensureActive()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw InvalidLabelBackupException(error)
        }
    }

    private suspend fun fingerprint(entry: MediaEntry): LabelMediaFingerprint {
        val file = File(entry.path)
        val length = file.length()
        val modified = file.lastModified()
        if (!file.isFile || !file.canRead()) throw IOException("Media unavailable")
        val hash = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
                bytesRead += count
            }
        }
        if (bytesRead != length || file.length() != length || file.lastModified() != modified) throw IOException("Media changed")
        val path = file.absolutePath.replace('\\', '/')
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath.replace('\\', '/').trimEnd('/') + "/"
        val relative = if (path.startsWith(storageRoot)) path.removePrefix(storageRoot)
            else path.replace(Regex("^/storage/[^/]+/"), "")
        return LabelMediaFingerprint(entry.contentId, hash.digest().joinToString("") { "%02x".format(it) },
            bytesRead, file.name, relative)
    }

    private companion object { const val MAX_BACKUP_BYTES = 64 * 1024 * 1024 }
}
