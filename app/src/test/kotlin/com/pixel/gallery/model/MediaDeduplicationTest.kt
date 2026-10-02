package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaDeduplicationTest {
    @Test
    fun globalScanFindsCrossAlbumDuplicatesWithStableKeeper() = runBlocking {
        val first = entry(2, "/Pictures/A/photo.jpg")
        val second = entry(1, "/Pictures/B/photo.jpg")
        val engine = engine(mapOf(1L to "same", 2L to "same"))

        val group = engine.scan(listOf(second, first)).groups.single()

        assertEquals(first, group.keeper)
        assertEquals(listOf(second), group.duplicates)
        assertEquals(4L, group.fingerprint.sizeBytes)
        assertEquals("0967115f2813a3541eaef77de9d9d5773f1c0c04314b0bbfe4ff3b3b1c55b5d5", group.fingerprint.sha256)
    }

    @Test
    fun perAlbumUsesFullParentPathEvenForAlbumsWithSameName() = runBlocking {
        val first = entry(1, "/DCIM/Trips/a.jpg")
        val second = entry(2, "/Pictures/Trips/b.jpg")
        val third = entry(3, "/DCIM/Trips/c.jpg")
        val engine = engine(mapOf(1L to "same", 2L to "same", 3L to "same"))

        val result = engine.scan(listOf(second, third, first), DeduplicationScope.WITHIN_ALBUMS)

        assertEquals(listOf(first, third), result.groups.single().entries)
        assertEquals(2, result.scannedCount)
    }

    @Test
    fun perAlbumScansEachFolderIndependently() = runBlocking {
        val entries = listOf(
            entry(1, "/A/1.jpg"), entry(2, "/A/2.jpg"),
            entry(3, "/B/1.jpg"), entry(4, "/B/2.jpg"),
        )
        val engine = engine(entries.associate { it.contentId to "same" })

        val groups = engine.scan(entries, DeduplicationScope.WITHIN_ALBUMS).groups

        assertEquals(listOf(listOf(1L, 2L), listOf(3L, 4L)), groups.map { it.entries.map { entry -> entry.contentId } })
    }

    @Test
    fun sameNamesSizesAndMetadataDoNotImplyEqualContents() = runBlocking {
        val entries = listOf(entry(1, "/A/photo.jpg"), entry(2, "/B/photo.jpg"))
        assertTrue(engine(mapOf(1L to "abcd", 2L to "abce")).scan(entries).groups.isEmpty())
    }

    @Test
    fun differentSizesAreExcludedBeforeOpeningAnyFile() = runBlocking {
        var opened = 0
        val engine = MediaDeduplication { opened++; error("Unique sizes must not be read") }

        val result = engine.scan(listOf(entry(1, size = 1), entry(2, size = 2)))

        assertEquals(0, opened)
        assertEquals(0, result.scannedCount)
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun repeatedIdsUrisPathsAndTransitiveAliasesAreOneFile() = runBlocking {
        val a = entry(1, "/A/a.jpg")
        val b = entry(2, "/A/b.jpg")
        val bridge = entry(3, "/A/b.jpg").copy(uri = a.uri)
        val repeatedId = entry(1, "/A/c.jpg")
        val repeatedPath = entry(4, "/A/./a.jpg")
        var opened = 0

        val result = MediaDeduplication { opened++; "same".byteInputStream() }
            .scan(listOf(a, b, bridge, repeatedId, repeatedPath, a))

        assertEquals(0, opened)
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun aliasesAlongsideRealCopyRetainOnePhysicalOriginal() = runBlocking {
        val original = entry(1, "/A/a.jpg")
        val alias = entry(2, "/A/b.jpg").copy(uri = original.uri)
        val copy = entry(3, "/A/c.jpg")

        val result = engine(mapOf(1L to "same", 3L to "same")).scan(listOf(alias, copy, original))

        assertEquals(listOf(original, copy), result.groups.single().entries)
    }

    @Test
    fun trashedFilesNeverBecomeKeepersOrDeletionCandidates() = runBlocking {
        val result = engine(mapOf(1L to "same", 2L to "same")).scan(
            listOf(entry(1).copy(isTrashed = true), entry(2)),
        )
        assertTrue(result.groups.isEmpty())
        assertEquals(0, result.scannedCount)
    }

    @Test
    fun missingUnreadableAndChangedSizeAreSkippedWhileOtherMatchesSurvive() = runBlocking {
        val engine = MediaDeduplication {
            when (it.contentId) {
                1L -> null
                2L -> throw IOException("File removed")
                3L -> "short".byteInputStream()
                4L -> "x".byteInputStream()
                else -> "same".byteInputStream()
            }
        }

        val result = engine.scan((1L..6L).map { entry(it) })

        assertEquals(listOf(5L, 6L), result.groups.single().entries.map { it.contentId })
        assertEquals(4, result.skippedCount)
        assertEquals(6, result.scannedCount)
        assertEquals(2, result.skipped.count { it.reason == DuplicateSkipReason.CONTENT_CHANGED })
    }

    @Test
    fun invalidSizesAreReportedWithoutOpeningFiles() = runBlocking {
        val result = MediaDeduplication { error("Must not open invalid sizes") }.scan(listOf(entry(1, size = -1)))
        assertEquals(DuplicateSkipReason.INVALID_SIZE, result.skipped.single().reason)
    }

    @Test
    fun progressIncludesFailedReadsAndFinishesAtTotal() = runBlocking {
        val progress = mutableListOf<DuplicateScanProgress>()
        val result = engine(mapOf(1L to "same", 2L to "same"))
            .scan(listOf(entry(1), entry(2), entry(3)), onProgress = progress::add)

        assertEquals(listOf(0, 1, 2, 3), progress.map { it.completed })
        assertTrue(progress.all { it.total == 3 })
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun validationReturnsSelectedGroupMembersAndRejectsUnknownIds() = runBlocking {
        val engine = engine(mapOf(1L to "same", 2L to "same", 3L to "same"))
        val scan = engine.scan(listOf(entry(3), entry(2), entry(1)))
        val progress = mutableListOf<DuplicateScanProgress>()

        val validation = engine.validate(scan, setOf(1, 2, 99), progress::add)

        assertEquals(listOf(1L, 2L), validation.validEntries.map { it.contentId })
        assertEquals(setOf(99L), validation.rejectedIds)
        assertEquals(1, validation.skippedCount)
        assertEquals(DuplicateScanProgress(4, 4), progress.last())
    }

    @Test
    fun originalScanKeeperMayBeRemovedWhenAlternativeIsRetained() = runBlocking {
        val opened = mutableListOf<Long>()
        val engine = MediaDeduplication { opened += it.contentId; "same".byteInputStream() }
        val scan = engine.scan(listOf(entry(1), entry(2)))
        opened.clear()

        val validation = engine.validate(scan, setOf(1))

        assertEquals(listOf(1L), validation.validEntries.map { it.contentId })
        assertEquals(listOf(2L, 1L, 2L), opened)
        assertEquals(0, validation.skippedCount)
    }

    @Test
    fun selectingEveryGroupMemberRejectsEntireGroupWithoutOpeningFiles() = runBlocking {
        var opened = 0
        val engine = MediaDeduplication { opened++; "same".byteInputStream() }
        val scan = engine.scan((1L..3L).map { entry(it) })
        opened = 0

        val validation = engine.validate(scan, setOf(1, 2, 3))

        assertTrue(validation.validEntries.isEmpty())
        assertEquals(0, opened)
        assertEquals(3, validation.skippedCount)
        assertTrue(validation.skipped.all { it.reason == DuplicateSkipReason.NO_RETAINED_COPY })
    }

    @Test
    fun changedAlternativeKeeperRejectsRemovalOfOriginalKeeper() = runBlocking {
        val files = mutableMapOf(1L to "same", 2L to "same")
        val engine = engine(files)
        val scan = engine.scan(listOf(entry(1), entry(2)))
        files[2] = "edit"

        val validation = engine.validate(scan, setOf(1))

        assertTrue(validation.validEntries.isEmpty())
        assertEquals(1L, validation.skipped.single().entry.contentId)
        assertEquals(DuplicateSkipReason.KEEPER_CHANGED, validation.skipped.single().reason)
    }

    @Test
    fun sameSizeChangeAfterScanIsRejectedBeforeRemoval() = runBlocking {
        val files = mutableMapOf(1L to "same", 2L to "same", 3L to "same")
        val engine = engine(files)
        val scan = engine.scan((1L..3L).map { entry(it) })
        files[2] = "edit"

        val validation = engine.validate(scan, setOf(2, 3))

        assertEquals(listOf(3L), validation.validEntries.map { it.contentId })
        assertEquals(2L, validation.skipped.single().entry.contentId)
        assertEquals(DuplicateSkipReason.CONTENT_CHANGED, validation.skipped.single().reason)
    }

    @Test
    fun missingRetainedFileRejectsAllRemovalsInItsGroup() = runBlocking {
        val files = mutableMapOf(1L to "same", 2L to "same", 3L to "same")
        val engine = engine(files)
        val scan = engine.scan((1L..3L).map { entry(it) })
        files.remove(1)

        val validation = engine.validate(scan, setOf(2, 3))

        assertTrue(validation.validEntries.isEmpty())
        assertEquals(2, validation.skippedCount)
        assertTrue(validation.skipped.all { it.reason == DuplicateSkipReason.KEEPER_CHANGED })
    }

    @Test
    fun retainedFileChangedDuringValidationRejectsPendingRemovals() = runBlocking {
        var keeperReads = 0
        val engine = MediaDeduplication {
            if (it.contentId == 1L && ++keeperReads == 2) "edit".byteInputStream() else "same".byteInputStream()
        }
        val scan = engine.scan(listOf(entry(1), entry(2)))

        val validation = engine.validate(scan, setOf(2))

        assertTrue(validation.validEntries.isEmpty())
        assertEquals(DuplicateSkipReason.KEEPER_CHANGED, validation.skipped.single().reason)
    }

    @Test
    fun missingSelectedDuplicateIsSkippedWhileRetainedFileStaysUntouched() = runBlocking {
        val files = mutableMapOf(1L to "same", 2L to "same")
        val engine = engine(files)
        val scan = engine.scan(listOf(entry(1), entry(2)))
        files.remove(2)

        val validation = engine.validate(scan, setOf(2))

        assertTrue(validation.validEntries.isEmpty())
        assertEquals(DuplicateSkipReason.UNREADABLE, validation.skipped.single().reason)
        assertEquals("same", files[1])
    }

    @Test
    fun cancellationDuringHashingPropagatesAndClosesStream() {
        var closed = false
        var returned = false
        try {
            runBlocking {
                val activeJob = coroutineContext[Job]!!
                val engine = MediaDeduplication {
                    object : ByteArrayInputStream(ByteArray(200_000)) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            val read = super.read(buffer, offset, length)
                            activeJob.cancel()
                            return read
                        }

                        override fun close() { closed = true; super.close() }
                    }
                }
                engine.scan(listOf(entry(1, size = 200_000), entry(2, size = 200_000)))
                returned = true
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(closed)
            assertFalse(returned)
        }
    }

    @Test
    fun unknownParentPathsCannotGroupInPerAlbumMode() = runBlocking {
        val result = engine(mapOf(1L to "same", 2L to "same")).scan(
            listOf(entry(1, "a.jpg"), entry(2, "b.jpg")), DeduplicationScope.WITHIN_ALBUMS,
        )
        assertTrue(result.groups.isEmpty())
    }

    private fun engine(files: Map<Long, String>) = MediaDeduplication { files[it.contentId]?.byteInputStream() }

    private fun entry(id: Long, path: String = "/Pictures/$id.jpg", size: Long = 4) = MediaEntry(
        contentId = id,
        uri = "content://media/external/images/media/$id",
        path = path,
        sourceMimeType = "image/jpeg",
        width = 100,
        height = 100,
        sourceRotationDegrees = 0,
        sizeBytes = size,
        dateAddedSecs = 1,
        dateModifiedMillis = 1,
    )
}
