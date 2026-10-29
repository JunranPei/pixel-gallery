package com.pixel.gallery.model

import com.google.gson.Gson
import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class QuickSortQueueTest {
    private fun media(id: Long, path: String = "/pictures/$id.jpg") = MediaEntry(id, "content://media/external/images/media/$id",
        path, "image/jpeg", 1, 1, 0, 4, 100, 100_000, 90_000)
    private fun job(items: List<QuickSortQueueItem>, status: QuickSortJobStatus = QuickSortJobStatus.Queued) =
        QuickSortJob("batch", TransferDestination("target", "Target", "/target"), items, 1000, status)

    @Test fun reservationsRejectSameFileEvenWithAnotherMediaId() {
        val state = QuickSortQueueState(jobs = listOf(job(listOf(QuickSortQueueItem(media(1, "/pictures/photo.jpg"))))))
        val candidates = listOf(media(2, "/pictures/./photo.jpg"), media(1, "/pictures/renamed.jpg"), media(3), media(3))
        assertEquals(listOf(3L), selectUnreservedQuickSortEntries(candidates, state).map { it.contentId })
    }

    @Test fun cancellationRetainsRunningFileAndCancelsOnlyRemainder() {
        val cancelled = cancelQuickSortJob(job(listOf(QuickSortQueueItem(media(1), QuickSortItemStatus.Succeeded),
            QuickSortQueueItem(media(2), QuickSortItemStatus.Running), QuickSortQueueItem(media(3))), QuickSortJobStatus.Running))
        assertTrue(cancelled.cancelRequested)
        assertEquals(listOf(QuickSortItemStatus.Succeeded, QuickSortItemStatus.Running, QuickSortItemStatus.Cancelled), cancelled.items.map { it.status })
        assertEquals(setOf(2L), QuickSortQueueState(jobs = listOf(cancelled)).reservedIds)
    }

    @Test fun cancellationReleasesAnUnstartedBatch() {
        val cancelled = cancelQuickSortJob(job(listOf(QuickSortQueueItem(media(1)))))
        assertEquals(QuickSortJobStatus.Cancelled, cancelled.status)
        assertTrue(QuickSortQueueState(jobs = listOf(cancelled)).reservedIds.isEmpty())
    }

    @Test fun oldPermissionResponseCannotAuthorizeAnotherBatchOrRetry() {
        val waiting = job(listOf(QuickSortQueueItem(media(1))), QuickSortJobStatus.NeedsPermission).copy(permissionToken = "new")
        assertFalse(matchesQuickSortPermission(waiting, "another-batch", "new"))
        assertFalse(matchesQuickSortPermission(waiting, "batch", "old"))
        assertTrue(matchesQuickSortPermission(waiting, "batch", "new"))
        assertFalse(matchesQuickSortPermission(waiting.copy(status = QuickSortJobStatus.Cancelled), "batch", "new"))
    }

    @Test fun recoveryNeverDeletesAStillExistingOriginal() {
        assertEquals(QuickMoveRecoveryDecision.KeepOriginal, quickMoveRecoveryDecision(true, true, true, true))
        assertEquals(QuickMoveRecoveryDecision.KeepOriginal, quickMoveRecoveryDecision(true, true, true, false))
        assertEquals(QuickMoveRecoveryDecision.NeedsReview, quickMoveRecoveryDecision(true, false, true, true))
        assertEquals(QuickMoveRecoveryDecision.RepairCommitted, quickMoveRecoveryDecision(false, false, true, true))
        assertEquals(QuickMoveRecoveryDecision.NeedsReview, quickMoveRecoveryDecision(false, false, true, false))
        assertEquals(QuickMoveRecoveryDecision.NeedsReview, quickMoveRecoveryDecision(false, false, false, false))
    }

    @Test fun journalRoundTripRetainsRecoveryPathsHashAndPermissionToken() {
        val checkpoint = QuickMoveCheckpoint(media(1), "/target/1.jpg", "/target/.pixel-transfer-batch.tmp",
            100_000, 4, "sha256", QuickMoveStage.TargetReady, "content://media/external/images/media/2")
        val original = QuickSortQueueJournal(jobs = listOf(job(listOf(QuickSortQueueItem(media(1),
            QuickSortItemStatus.Running, checkpoint)), QuickSortJobStatus.NeedsPermission).copy(permissionToken = "unique-token")))
        val gson = Gson()
        assertEquals(original, gson.fromJson(gson.toJson(original), QuickSortQueueJournal::class.java))
    }

    @Test fun equalLengthCorruptionFailsContentVerification() = withFiles { source, target ->
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        val snapshot = fingerprintMediaFile(source)
        target.writeBytes(byteArrayOf(1, 2, 3, 9))
        assertThrows(IOException::class.java) { verifyMediaFile(target, snapshot) }
        assertTrue(source.exists())
    }

    @Test fun verifiedCopyPreservesBytesAndModificationTime() = withFiles { source, target ->
        source.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        assertTrue(source.setLastModified(1_600_000_000_000L))
        val snapshot = fingerprintMediaFile(source)
        assertTrue(target.createNewFile())
        copyVerifiedMediaBytes(source, target, snapshot)
        assertEquals(snapshot, fingerprintMediaFile(target))
        assertEquals(snapshot, fingerprintMediaFile(source))
    }

    @Test fun sourceMutationAfterSnapshotPreventsVerifiedCopy() = withFiles { source, target ->
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        val snapshot = fingerprintMediaFile(source)
        source.writeBytes(byteArrayOf(4, 3, 2, 1))
        source.setLastModified(snapshot.modifiedMillis)
        target.createNewFile()
        assertThrows(IOException::class.java) { copyVerifiedMediaBytes(source, target, snapshot) }
        assertTrue(source.exists())
    }

    private fun withFiles(block: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("quick-sort-test").toFile()
        try { block(File(directory, "source.bin"), File(directory, "target.bin")) }
        finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
