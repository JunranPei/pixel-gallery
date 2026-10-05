package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

enum class DeduplicationScope { ALL_FILES, WITHIN_ALBUMS }

data class DuplicateFingerprint(val sizeBytes: Long, val sha256: String)

data class DuplicateGroup(
    val keeper: MediaEntry,
    val duplicates: List<MediaEntry>,
    val fingerprint: DuplicateFingerprint,
) {
    val entries: List<MediaEntry> get() = listOf(keeper) + duplicates
}

enum class DuplicateSkipReason { UNREADABLE, INVALID_SIZE, CONTENT_CHANGED, KEEPER_CHANGED, NO_RETAINED_COPY }

data class DuplicateSkippedFile(val entry: MediaEntry, val reason: DuplicateSkipReason)

data class DuplicateScanProgress(val completed: Int, val total: Int)

data class DuplicateScanResult(
    val groups: List<DuplicateGroup>,
    val skipped: List<DuplicateSkippedFile>,
    val scannedCount: Int,
) {
    val skippedCount: Int get() = skipped.size
}

data class DuplicateValidationResult(
    val validEntries: List<MediaEntry>,
    val skipped: List<DuplicateSkippedFile>,
    val rejectedIds: Set<Long> = emptySet(),
) {
    val skippedCount: Int get() = (skipped.map { it.entry.contentId } + rejectedIds).distinct().size
}

/**
 * Content comparison only; this class never deletes files. The caller supplies the stream opener
 * and runs it on an IO dispatcher. A scan describes a snapshot, so [validate] must run immediately
 * before handing the chosen duplicates to the platform's removal flow.
 */
class MediaDeduplication(private val openInputStream: (MediaEntry) -> InputStream?) {
    suspend fun scan(
        entries: List<MediaEntry>,
        scope: DeduplicationScope = DeduplicationScope.ALL_FILES,
        onProgress: (DuplicateScanProgress) -> Unit = {},
    ): DuplicateScanResult {
        currentCoroutineContext().ensureActive()
        val skipped = mutableListOf<DuplicateSkippedFile>()
        val unique = distinctFiles(entries.filterNot { it.isTrashed })
        val eligible = unique.filter { entry ->
            (entry.sizeBytes >= 0).also { valid ->
                if (!valid) skipped += DuplicateSkippedFile(entry, DuplicateSkipReason.INVALID_SIZE)
            }
        }
        // Hash every active entry in the scan snapshot. Size is still used to
        // avoid comparing different-sized files, but it must not hide files
        // from the progress count or the content check.
        val candidates = eligible.groupBy { entry ->
            val partition = when (scope) {
                DeduplicationScope.ALL_FILES -> ""
                DeduplicationScope.WITHIN_ALBUMS -> albumPath(entry)
            }
            partition to entry.sizeBytes
        }.values
        val total = unique.size
        var completed = 0
        onProgress(DuplicateScanProgress(completed, total))
        val groups = mutableListOf<DuplicateGroup>()
        unique.filter { it.sizeBytes < 0L }.forEach {
            onProgress(DuplicateScanProgress(++completed, total))
        }
        for (bucket in candidates) {
            val hashes = linkedMapOf<DuplicateFingerprint, MutableList<MediaEntry>>()
            for (entry in bucket) {
                when (val read = fingerprint(entry)) {
                    is FingerprintRead.Success -> hashes.getOrPut(read.fingerprint) { mutableListOf() }.add(entry)
                    is FingerprintRead.Failure -> skipped += DuplicateSkippedFile(entry, read.reason)
                }
                onProgress(DuplicateScanProgress(++completed, total))
            }
            hashes.forEach { (hash, matches) ->
                if (matches.size > 1) groups += DuplicateGroup(matches.first(), matches.drop(1), hash)
            }
        }
        currentCoroutineContext().ensureActive()
        return DuplicateScanResult(groups.sortedWith(compareBy({ it.keeper.path }, { it.keeper.contentId })), skipped, completed)
    }

    /**
     * Reopens both retained and removed files. Any group member may be selected, provided at least
     * one is retained. The first unselected entry in the group's existing order is the keeper.
     * A missing or changed keeper rejects its whole group; a changed candidate rejects that file.
     * The keeper is checked again after the candidates, narrowing the window for concurrent changes.
     * IDs outside the scan and groups with no retained copy are never removable.
     */
    suspend fun validate(
        result: DuplicateScanResult,
        selectedIds: Set<Long>,
        onProgress: (DuplicateScanProgress) -> Unit = {},
    ): DuplicateValidationResult {
        currentCoroutineContext().ensureActive()
        val allowedIds = result.groups.flatMap { it.entries }.map { it.contentId }.toSet()
        val rejectedIds = selectedIds - allowedIds
        val work = result.groups.mapNotNull { group ->
            group.entries.filter { it.contentId in selectedIds }.takeIf { it.isNotEmpty() }?.let { group to it }
        }
        val total = work.sumOf { (_, selected) -> selected.size + 2 }
        var completed = 0
        val valid = mutableListOf<MediaEntry>()
        val skipped = mutableListOf<DuplicateSkippedFile>()
        onProgress(DuplicateScanProgress(completed, total))
        for ((group, selected) in work) {
            val retainedEntry = group.entries.firstOrNull { it.contentId !in selectedIds }
            if (retainedEntry == null) {
                skipped += selected.map { DuplicateSkippedFile(it, DuplicateSkipReason.NO_RETAINED_COPY) }
                completed += selected.size + 2
                onProgress(DuplicateScanProgress(completed, total))
                continue
            }
            val keeper = fingerprint(retainedEntry)
            onProgress(DuplicateScanProgress(++completed, total))
            if (keeper !is FingerprintRead.Success || keeper.fingerprint != group.fingerprint) {
                skipped += selected.map { DuplicateSkippedFile(it, DuplicateSkipReason.KEEPER_CHANGED) }
                completed += selected.size + 1
                onProgress(DuplicateScanProgress(completed, total))
                continue
            }
            val groupValid = mutableListOf<MediaEntry>()
            for (entry in selected) {
                when (val read = fingerprint(entry)) {
                    is FingerprintRead.Failure -> skipped += DuplicateSkippedFile(entry, read.reason)
                    is FingerprintRead.Success -> {
                        if (read.fingerprint == group.fingerprint) groupValid += entry
                        else skipped += DuplicateSkippedFile(entry, DuplicateSkipReason.CONTENT_CHANGED)
                    }
                }
                onProgress(DuplicateScanProgress(++completed, total))
            }
            val retained = fingerprint(retainedEntry)
            onProgress(DuplicateScanProgress(++completed, total))
            if (retained is FingerprintRead.Success && retained.fingerprint == group.fingerprint) {
                valid += groupValid
            } else {
                skipped += groupValid.map { DuplicateSkippedFile(it, DuplicateSkipReason.KEEPER_CHANGED) }
            }
        }
        currentCoroutineContext().ensureActive()
        return DuplicateValidationResult(valid, skipped, rejectedIds)
    }

    private suspend fun fingerprint(entry: MediaEntry): FingerprintRead {
        currentCoroutineContext().ensureActive()
        return try {
            val stream = openInputStream(entry) ?: return FingerprintRead.Failure(DuplicateSkipReason.UNREADABLE)
            stream.use {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var bytesRead = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        // A custom/provider stream may return zero without EOF; force forward progress.
                        val single = it.read()
                        if (single < 0) break
                        digest.update(single.toByte())
                        bytesRead++
                    } else {
                        digest.update(buffer, 0, count)
                        bytesRead += count
                    }
                    if (bytesRead > entry.sizeBytes) return FingerprintRead.Failure(DuplicateSkipReason.CONTENT_CHANGED)
                    yield()
                }
                currentCoroutineContext().ensureActive()
                if (bytesRead != entry.sizeBytes) FingerprintRead.Failure(DuplicateSkipReason.CONTENT_CHANGED)
                else FingerprintRead.Success(DuplicateFingerprint(bytesRead, digest.digest().toHex()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            FingerprintRead.Failure(DuplicateSkipReason.UNREADABLE)
        }
    }

    private sealed interface FingerprintRead {
        data class Success(val fingerprint: DuplicateFingerprint) : FingerprintRead
        data class Failure(val reason: DuplicateSkipReason) : FingerprintRead
    }
}

private fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            append(hex[(byte.toInt() and 0xff) ushr 4])
            append(hex[byte.toInt() and 0xf])
        }
    }
}

private fun normalizedMediaPath(path: String): String {
    val parts = mutableListOf<String>()
    for (part in path.replace('\\', '/').split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex) else parts.add(part)
            else -> parts.add(part)
        }
    }
    return (if (path.startsWith('/') || path.startsWith('\\')) "/" else "") + parts.joinToString("/")
}

private fun albumPath(entry: MediaEntry): String {
    val path = normalizedMediaPath(entry.path)
    val separator = path.lastIndexOf('/')
    // Missing parent information must never merge unrelated unknown folders.
    return if (separator >= 0) "path:${path.substring(0, separator)}" else "unknown:${entry.contentId}"
}

/** Collapse alias chains before grouping, so a duplicate database row can never delete itself. */
private fun distinctFiles(entries: List<MediaEntry>): List<MediaEntry> {
    val sorted = entries.sortedWith(compareBy({ it.path }, { it.contentId }))
    val parents = IntArray(sorted.size) { it }
    fun root(index: Int): Int {
        var current = index
        while (parents[current] != current) {
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }
    val identities = mutableMapOf<String, Int>()
    sorted.forEachIndexed { index, entry ->
        val keys = buildList {
            add("id:${entry.contentId}")
            if (entry.uri.isNotBlank()) add("uri:${entry.uri}")
            if (entry.path.isNotBlank()) add("path:${normalizedMediaPath(entry.path)}")
        }
        keys.forEach { key ->
            val other = identities.putIfAbsent(key, index)
            if (other != null) {
                val a = root(index)
                val b = root(other)
                parents[maxOf(a, b)] = minOf(a, b)
            }
        }
    }
    return sorted.filterIndexed { index, _ -> root(index) == index }
}
