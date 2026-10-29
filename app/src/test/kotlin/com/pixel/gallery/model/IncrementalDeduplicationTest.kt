package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncrementalDeduplicationTest {
    private class Store : DuplicateFingerprintStore {
        val rows = mutableMapOf<String, DuplicateIndexRecord>()
        override suspend fun load(uris: List<String>): Map<String, DuplicateIndexRecord> {
            val wanted = uris.toHashSet()
            return rows.filterKeys { it in wanted }
        }
        override suspend fun save(records: List<DuplicateIndexRecord>) { records.forEach { rows[it.key] = it } }
        override suspend fun remove(uris: List<String>) { uris.forEach(rows::remove) }
        override suspend fun retain(keys: Set<String>) { rows.keys.retainAll(keys) }
    }
    private class Files {
        val bytes = mutableMapOf<Long, ByteArray>()
        val revisions = mutableMapOf<Long, Int>()
        val opened = mutableListOf<Long>()
        val store = Store()
        fun add(id: Long, value: ByteArray) { bytes[id] = value; revisions[id] = (revisions[id] ?: 0) + 1 }
        fun engine() = IncrementalDeduplication(store, { entry ->
            bytes[entry.contentId]?.let { DuplicateFileState(it.size.toLong(), "revision-${revisions[entry.contentId]}") }
        }, { entry -> opened += entry.contentId; bytes[entry.contentId]?.inputStream() })
    }
    private fun entry(id: Long, path: String = "/A/$id.jpg", size: Long = 999) =
        MediaEntry(id, "content://media/$id", path, "image/jpeg", 1, 1, 0, size, 1, id)

    @Test fun restartReusesFingerprintsWithoutAnyContentReads() = runBlocking {
        val files = Files().apply { add(1, "same".toByteArray()); add(2, "same".toByteArray()) }
        val entries = listOf(entry(1), entry(2))
        val first = files.engine().scan(entries, DeduplicationScope.ALL_FILES)
        files.opened.clear()
        val second = files.engine().scan(entries, DeduplicationScope.ALL_FILES)
        assertEquals(first.groups, second.groups)
        assertEquals(1, second.groups.size)
        assertTrue(files.opened.isEmpty())
        assertEquals(0L, second.indexStats!!.bytesRead)
        assertEquals(2, second.indexStats!!.reusedFiles)
    }

    @Test fun staleProviderSizeCannotHideKeepBothCopy() = runBlocking {
        val files = Files().apply { add(1, "identical".toByteArray()); add(2, "identical".toByteArray()) }
        val result = files.engine().scan(listOf(entry(1, size = 0), entry(2, size = 123456)), DeduplicationScope.WITHIN_ALBUMS)
        assertEquals(setOf(1L, 2L), result.groups.single().entries.map { it.contentId }.toSet())
        assertTrue(result.groups.single().entries.all { it.sizeBytes == 9L })
    }

    @Test fun uniqueActualSizesRequireNoContentReads() = runBlocking {
        val files = Files().apply { add(1, byteArrayOf(1)); add(2, byteArrayOf(1, 2)) }
        val result = files.engine().scan(listOf(entry(1), entry(2)), DeduplicationScope.ALL_FILES)
        assertTrue(result.groups.isEmpty())
        assertTrue(files.opened.isEmpty())
        assertEquals(2, result.scannedCount)
    }

    @Test fun addingCopyOfPreviouslyUniqueFileChecksBothCandidates() = runBlocking {
        val files = Files().apply { add(1, "old".toByteArray()) }
        files.engine().scan(listOf(entry(1)), DeduplicationScope.ALL_FILES)
        files.add(2, "old".toByteArray())
        val result = files.engine().scan(listOf(entry(1), entry(2)), DeduplicationScope.ALL_FILES)
        assertEquals(1, result.groups.size)
        assertEquals(setOf(1L, 2L), files.opened.toSet())
    }

    @Test fun sameSizeOverwriteInvalidatesOnlyChangedContent() = runBlocking {
        val files = Files().apply { add(1, "same".toByteArray()); add(2, "same".toByteArray()) }
        val entries = listOf(entry(1), entry(2))
        files.engine().scan(entries, DeduplicationScope.ALL_FILES)
        files.add(2, "diff".toByteArray())
        files.opened.clear()
        val result = files.engine().scan(entries, DeduplicationScope.ALL_FILES)
        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(2L), files.opened)
        assertEquals(1, result.indexStats!!.reusedFiles)
    }

    @Test fun samePrefixRequiresFullHashAndDifferentTailsAreNotDuplicates() = runBlocking {
        val first = ByteArray(131072) { 1 }
        val second = first.copyOf().apply { this[lastIndex] = 2 }
        val files = Files().apply { add(1, first); add(2, second) }
        val result = files.engine().scan(listOf(entry(1), entry(2)), DeduplicationScope.ALL_FILES)
        assertTrue(result.groups.isEmpty())
        assertEquals(2L * (65536 + 131072), result.indexStats!!.bytesRead)
    }

    @Test fun distinctPrefixesAvoidReadingLargeTails() = runBlocking {
        val files = Files().apply { add(1, ByteArray(131072) { 1 }); add(2, ByteArray(131072) { 2 }) }
        val result = files.engine().scan(listOf(entry(1), entry(2)), DeduplicationScope.ALL_FILES)
        assertTrue(result.groups.isEmpty())
        assertEquals(2L * 65536, result.indexStats!!.bytesRead)
    }

    @Test fun albumScopesAreIndependentAndMovingUpdatesMembership() = runBlocking {
        val files = Files().apply { add(1, "same".toByteArray()); add(2, "same".toByteArray()) }
        val first = entry(1, "/A/a.jpg"); val second = entry(2, "/B/b.jpg")
        assertTrue(files.engine().scan(listOf(first, second), DeduplicationScope.WITHIN_ALBUMS).groups.isEmpty())
        val moved = second.copy(path = "/A/b.jpg")
        assertEquals(1, files.engine().scan(listOf(first, moved), DeduplicationScope.WITHIN_ALBUMS).groups.size)
        assertEquals(1, files.engine().scan(listOf(first, second), DeduplicationScope.ALL_FILES).groups.size)
    }

    @Test fun deletedAndTrashedCopiesNeverSurviveInResultsOrPreview() = runBlocking {
        val files = Files().apply { add(1, "same".toByteArray()); add(2, "same".toByteArray()) }
        val entries = listOf(entry(1), entry(2))
        files.engine().scan(entries, DeduplicationScope.ALL_FILES)
        val current = listOf(entry(1), entry(2).copy(isTrashed = true))
        assertTrue(files.engine().cached(current, DeduplicationScope.ALL_FILES)!!.groups.isEmpty())
        assertTrue(files.engine().scan(current, DeduplicationScope.ALL_FILES).groups.isEmpty())
        files.bytes.remove(2)
        val missing = files.engine().scan(entries, DeduplicationScope.ALL_FILES)
        assertTrue(missing.groups.isEmpty())
        assertEquals(1, missing.skippedCount)
        assertFalse(files.store.rows.values.any { it.path == entry(2).path })
    }

    @Test fun unstatableStreamsFallBackToFullReadAndStillMatchKnownSizeFiles() = runBlocking {
        val files = Files().apply { add(1, "same".toByteArray()) }
        val engine = IncrementalDeduplication(files.store,
            { if (it.contentId == 1L) DuplicateFileState(4, "one") else null },
            { "same".byteInputStream() })
        assertEquals(1, engine.scan(listOf(entry(1), entry(2)), DeduplicationScope.ALL_FILES).groups.size)
        assertFalse(files.store.rows.values.any { it.path == entry(2).path })
    }

    @Test fun midReadReplacementIsSkippedAndDoesNotPoisonCache() = runBlocking {
        val files = Files().apply { add(1, "same".toByteArray()); add(2, "same".toByteArray()) }
        val engine = IncrementalDeduplication(files.store,
            { DuplicateFileState(4, "revision-${files.revisions[it.contentId]}") },
            { entry -> if (entry.contentId == 2L) files.revisions[2] = 2; "same".byteInputStream() })
        val result = engine.scan(listOf(entry(1), entry(2)), DeduplicationScope.ALL_FILES)
        assertTrue(result.groups.isEmpty())
        assertEquals(DuplicateSkipReason.CONTENT_CHANGED, result.skipped.single().reason)
        assertFalse(files.store.rows.values.any { it.path == entry(2).path })
    }

    @Test fun aliasesStillCountAsOnePhysicalFile() = runBlocking {
        val files = Files().apply { add(1, "same".toByteArray()); add(2, "same".toByteArray()) }
        val result = files.engine().scan(listOf(entry(1), entry(1), entry(2).copy(uri = entry(1).uri)), DeduplicationScope.ALL_FILES)
        assertTrue(result.groups.isEmpty())
        assertEquals(1, result.scannedCount)
    }

    @Test fun deletionValidationIgnoresIndexAndReadsCurrentContents() = runBlocking {
        val files = Files().apply { add(1, "same".toByteArray()); add(2, "same".toByteArray()) }
        val result = files.engine().scan(listOf(entry(1), entry(2)), DeduplicationScope.ALL_FILES)
        files.add(2, "diff".toByteArray())
        var reads = 0
        val validated = MediaDeduplication { reads++; files.bytes[it.contentId]!!.inputStream() }.validate(result, setOf(2))
        assertTrue(validated.validEntries.isEmpty())
        assertTrue(reads >= 3)
    }

    @Test fun providerUriAliasesReuseTheSamePhysicalFileIndex() = runBlocking {
        val files = Files().apply { add(1, "same".toByteArray()); add(2, "same".toByteArray()) }
        val entries = listOf(entry(1), entry(2))
        files.engine().scan(entries, DeduplicationScope.ALL_FILES)
        files.opened.clear()
        val result = files.engine().scan(entries.map { it.copy(uri = it.uri.replace("media/", "media/external_primary/")) }, DeduplicationScope.ALL_FILES)
        assertEquals(1, result.groups.size)
        assertTrue(files.opened.isEmpty())
    }

    @Test fun completedGlobalScanPrunesRemovedFilesButAlbumScanPreservesOtherAlbums() = runBlocking {
        val files = Files().apply { add(1, byteArrayOf(1)); add(2, byteArrayOf(1, 2)) }
        files.engine().scan(listOf(entry(1), entry(2)), DeduplicationScope.ALL_FILES)
        files.engine().scan(listOf(entry(1)), DeduplicationScope.WITHIN_ALBUMS)
        assertEquals(2, files.store.rows.size)
        files.engine().scan(listOf(entry(1)), DeduplicationScope.ALL_FILES)
        assertEquals(1, files.store.rows.size)
    }

    @Test fun cancellationKeepsOnlyValidCheckpointsAndNextScanResumes() = runBlocking {
        val store = Store()
        val entries = (1L..300L).map { entry(it) }
        val engine = IncrementalDeduplication(store, { DuplicateFileState(it.contentId, "stable-${it.contentId}") },
            { error("Unique sizes must not read content") })
        val scanJob = Job()
        try {
            withContext(scanJob) {
                engine.scan(entries, DeduplicationScope.ALL_FILES) {
                    if (it.stage == DuplicateScanStage.COMPARING && it.completed >= 128) scanJob.cancel()
                }
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(128, store.rows.size)
        val resumed = engine.scan(entries, DeduplicationScope.ALL_FILES)
        assertEquals(128, resumed.indexStats!!.reusedFiles)
        assertEquals(300, resumed.scannedCount)
        assertEquals(0L, resumed.indexStats!!.bytesRead)
    }

    @Test fun hundredFiftyThousandUniqueSizesRemainMetadataOnlyOnRepeat() = runBlocking {
        val entries = (1L..150_000L).map { entry(it) }
        val store = Store()
        fun engine() = IncrementalDeduplication(store,
            { DuplicateFileState(it.contentId, "unchanged-${it.contentId}") },
            { error("A unique actual size cannot have a duplicate") })
        assertEquals(150_000, engine().scan(entries, DeduplicationScope.ALL_FILES).scannedCount)
        val repeated = engine().scan(entries, DeduplicationScope.ALL_FILES)
        assertEquals(150_000, repeated.indexStats!!.reusedFiles)
        assertEquals(0L, repeated.indexStats!!.bytesRead)
        assertTrue(repeated.groups.isEmpty())
    }

    @Test fun inventoryProgressAdvancesBeforeContentIsOpened() = runBlocking {
        val progress = mutableListOf<DuplicateScanProgress>()
        var stateCalls = 0
        val files = Files().apply { add(1, "same".toByteArray()); add(2, "same".toByteArray()) }
        val engine = IncrementalDeduplication(files.store, {
            stateCalls++
            DuplicateFileState(4, "stable")
        }, {
            assertTrue(stateCalls >= 2)
            assertTrue(progress.any { it.stage == DuplicateScanStage.INVENTORY && it.completed == 2 })
            "same".byteInputStream()
        })
        val result = engine.scan(listOf(entry(1), entry(2)), DeduplicationScope.WITHIN_ALBUMS, progress::add)
        assertEquals(1, result.groups.size)
        assertTrue(progress.any { it.stage == DuplicateScanStage.SAMPLING && it.completed == 2 })
        assertEquals(2, progress.last().completed)
        assertEquals(2, progress.last().total)
        assertTrue(progress.last().bytesRead > 0)
    }

    @Test fun unreadableCandidateIsReportedInsteadOfBeingCountedAsVerified() = runBlocking {
        val engine = IncrementalDeduplication(Store(), { DuplicateFileState(4, "stable") }, { null })
        val result = engine.scan(listOf(entry(1), entry(2)), DeduplicationScope.WITHIN_ALBUMS)
        assertEquals(2, result.skippedCount)
        assertTrue(result.groups.isEmpty())
        assertEquals(0, result.scannedCount - result.skippedCount)
    }
}
