package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Size comes from the file, never a possibly lagging MediaStore SIZE column. */
data class DuplicateFileState(val size: Long, val signature: String?)

data class DuplicateIndexRecord(
    val key: String,
    val path: String,
    val signature: String,
    val size: Long,
    val sample: String? = null,
    val sha256: String? = null,
)

interface DuplicateFingerprintStore {
    suspend fun load(uris: List<String>): Map<String, DuplicateIndexRecord>
    suspend fun loadDuplicateCandidates(uris: List<String>): Map<String, DuplicateIndexRecord> =
        load(uris).filterValues { it.sha256 != null }
    suspend fun save(records: List<DuplicateIndexRecord>)
    suspend fun remove(uris: List<String>)
    suspend fun retain(keys: Set<String>) = Unit
}

data class DuplicateIndexStats(val reusedFiles: Int, val contentFilesRead: Int, val bytesRead: Long)

/** Incremental content work; stat-only inventory checks still run on every completed scan. */
class IncrementalDeduplication(
    private val store: DuplicateFingerprintStore,
    private val stateOf: (MediaEntry) -> DuplicateFileState?,
    private val open: (MediaEntry) -> InputStream?,
) {
    /** Unverified preview only. Callers must label it as updating and disable removal. */
    suspend fun cached(entries: List<MediaEntry>, scope: DeduplicationScope): DuplicateScanResult? {
        val files = distinctFiles(entries.filterNot { it.isTrashed })
        val saved = store.loadDuplicateCandidates(files.map(::indexKey))
        if (saved.isEmpty()) return null
        val matches = files.mapNotNull { entry ->
            saved[indexKey(entry)]?.takeIf { it.path == entry.path && it.sha256 != null }?.let {
                entry.copy(sizeBytes = it.size) to DuplicateFingerprint(it.size, it.sha256!!)
            }
        }
        return DuplicateScanResult(groups(matches, scope), emptyList(), files.size)
    }

    suspend fun scan(
        entries: List<MediaEntry>,
        scope: DeduplicationScope,
        onProgress: (DuplicateScanProgress) -> Unit = {},
    ): DuplicateScanResult {
        val files = distinctFiles(entries.filterNot { it.isTrashed })
        val saved = store.load(files.map(::indexKey))
        val skipped = linkedMapOf<String, DuplicateSkippedFile>()
        val pending = linkedMapOf<String, DuplicateIndexRecord>()
        val invalid = linkedSetOf<String>()
        val readFiles = hashSetOf<String>()
        var bytesRead = 0L
        var reused = 0
        var completed = 0
        val readBuffer = ByteArray(SAMPLE_BYTES)
        val total = files.size
        onProgress(DuplicateScanProgress(0, total))

        suspend fun flush() {
            if (pending.isNotEmpty()) { store.save(pending.values.toList()); pending.clear() }
            if (invalid.isNotEmpty()) { store.remove(invalid.toList()); invalid.clear() }
        }
        suspend fun finishOne() {
            completed++
            // Avoid 150,000 UI state publications and persist cancellation-safe checkpoints.
            if (completed % 128 == 0 || completed == total) {
                flush()
                onProgress(DuplicateScanProgress(completed, total))
                yield()
            }
        }
        fun reject(entry: MediaEntry, changed: Boolean) {
            skipped[entry.uri] = DuplicateSkippedFile(entry,
                if (changed) DuplicateSkipReason.CONTENT_CHANGED else DuplicateSkipReason.UNREADABLE)
            invalid += indexKey(entry)
            pending.remove(indexKey(entry))
        }
        fun save(work: Work) {
            work.state?.signature?.let { signature ->
                val key = indexKey(work.entry)
                val row = DuplicateIndexRecord(key, work.entry.path, signature, work.size, work.sample, work.hash)
                if (row != saved[key]) pending[key] = row
            }
        }
        suspend fun read(work: Work, full: Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val prefix = MessageDigest.getInstance("SHA-256")
                var count = 0L
                val buffer = readBuffer
                val stream = open(work.entry) ?: error("Unavailable file")
                readFiles += work.entry.uri
                stream.use {
                    while (full || count < SAMPLE_BYTES) {
                        currentCoroutineContext().ensureActive()
                        val wanted = if (full) buffer.size else (SAMPLE_BYTES - count).toInt()
                        var length = it.read(buffer, 0, wanted)
                        if (length < 0) break
                        if (length == 0) {
                            val byte = it.read()
                            if (byte < 0) break
                            buffer[0] = byte.toByte(); length = 1
                        }
                        digest.update(buffer, 0, length)
                        val prefixLength = minOf(length.toLong(), (SAMPLE_BYTES - count).coerceAtLeast(0)).toInt()
                        if (prefixLength > 0) prefix.update(buffer, 0, prefixLength)
                        count += length; bytesRead += length
                        yield()
                    }
                }
                val after = stateOf(work.entry)
                if (work.state != null && (after != work.state ||
                        (full && count != work.state.size) || (!full && count != minOf(work.state.size, SAMPLE_BYTES.toLong())))) {
                    reject(work.entry, changed = true)
                    return false
                }
                work.sample = prefix.digest().hex()
                if (full || count < SAMPLE_BYTES || work.size == count) {
                    work.size = count
                    work.hash = digest.digest().hex()
                }
                save(work)
                return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reject(work.entry, changed = false)
                return false
            }
        }

        val work = ArrayList<Work>(files.size)
        for ((index, entry) in files.withIndex()) {
            currentCoroutineContext().ensureActive()
            val state = stateOf(entry)
            if (state?.signature == null) invalid += indexKey(entry)
            val old = saved[indexKey(entry)]?.takeIf {
                state?.signature != null && it.signature == state.signature && it.size == state.size && it.path == entry.path
            }
            if (old != null) reused++
            val item = Work(entry, state, state?.size ?: -1, old?.sample, old?.sha256)
            // Unstatable/document-provider streams cannot be size-filtered or cached safely.
            if (state == null) {
                if (!read(item, full = true)) { finishOne(); continue }
            } else if (state.size < 0) {
                reject(entry, changed = false); finishOne(); continue
            }
            work += item
            if (index % 512 == 0) yield()
        }
        val matches = mutableListOf<Pair<MediaEntry, DuplicateFingerprint>>()
        for (sameSize in work.groupBy { (if (scope == DeduplicationScope.WITHIN_ALBUMS) albumPath(it.entry) else "") to it.size }.values) {
            if (sameSize.size == 1) {
                save(sameSize.single()); finishOne(); continue
            }
            val sampled = sameSize.filter { it.sample != null || read(it, full = false) }
            for (sameSample in sampled.groupBy { it.sample }.values) {
                if (sameSample.size > 1) {
                    for (item in sameSample) {
                        if (item.hash != null || read(item, full = true)) {
                            matches += item.entry.copy(sizeBytes = item.size) to DuplicateFingerprint(item.size, item.hash!!)
                        }
                    }
                }
            }
            for (item in sameSize) { if (item.entry.uri !in skipped) save(item); finishOne() }
        }
        flush()
        if (scope == DeduplicationScope.ALL_FILES) store.retain(files.mapTo(hashSetOf(), ::indexKey))
        currentCoroutineContext().ensureActive()
        return DuplicateScanResult(groups(matches, scope), skipped.values.toList(), completed,
            DuplicateIndexStats(reused, readFiles.size, bytesRead))
    }

    private class Work(val entry: MediaEntry, val state: DuplicateFileState?, var size: Long,
        var sample: String? = null, var hash: String? = null)

    // MediaStore's external and external_primary URIs can name the same file. Content identity
    // is guarded by the file signature; a provider URI alias must not force a second full read.
    private fun indexKey(entry: MediaEntry): String =
        if (entry.path.startsWith('/')) "file:${entry.path}" else entry.uri

    private fun groups(matches: List<Pair<MediaEntry, DuplicateFingerprint>>, scope: DeduplicationScope): List<DuplicateGroup> =
        matches.groupBy { (entry, fingerprint) -> (if (scope == DeduplicationScope.WITHIN_ALBUMS) albumPath(entry) else "") to fingerprint }
            .mapNotNull { (key, rows) ->
                if (rows.size < 2) null else rows.map { it.first }.sortedWith(duplicateKeeperOrder).let {
                    DuplicateGroup(it.first(), it.drop(1), key.second)
                }
            }.sortedWith(compareBy({ it.keeper.path }, { it.keeper.contentId }))

    private fun ByteArray.hex(): String = buildString(size * 2) {
        val digits = "0123456789abcdef"
        for (byte in this@hex) { append(digits[(byte.toInt() and 255) ushr 4]); append(digits[byte.toInt() and 15]) }
    }
    private companion object { const val SAMPLE_BYTES = 64 * 1024 }
}
