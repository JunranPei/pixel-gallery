package com.pixel.gallery.model

import com.google.gson.Gson
import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.*
import org.junit.Test

class QuickSortRetryResolutionTest {
    private val entry = MediaEntry(7, "content://media/7", "/source/photo.jpg", "image/jpeg", 10, 10, 0, 42, 1, 1000)
    private val target = TransferDestination("target", "Target", "/target")
    private val receipt = TransferReceipt(7, entry.uri, entry.path, 8, "content://media/8", "/target/photo.jpg", 1000, null, true, true, true, true)
    private fun job(id: String, item: QuickSortQueueItem) = QuickSortJob(id, target, listOf(item), 0, QuickSortJobStatus.Completed)

    @Test fun `successful retry resolves exact failure and earlier failed retries`() {
        val first = job("first", QuickSortQueueItem(entry, QuickSortItemStatus.Failed, error = "first failure"))
        val second = job("second", first.items.single().copy(retryOrigin = QuickSortRetryOrigin("first", 0)))
        val third = job("third", QuickSortQueueItem(entry, QuickSortItemStatus.Succeeded, receipt = receipt,
            retryOrigin = QuickSortRetryOrigin("second", 0)))
        val resolved = resolveQuickSortRetrySuccess(listOf(first, second, third), "third", 0)
        assertEquals(listOf(0, 0, 0), resolved.map { it.failed })
        assertEquals(listOf(1, 1, 0), resolved.map { it.resolved })
        assertTrue(resolved.take(2).all { it.items.single().resolvedByJobId == "third" && it.items.single().error == null })
        assertEquals(resolved, Gson().fromJson(Gson().toJson(QuickSortQueueJournal(jobs = resolved)), QuickSortQueueJournal::class.java).jobs)
    }

    @Test fun `failed cancelled skipped or unverified retry leaves failure intact`() {
        val failed = job("first", QuickSortQueueItem(entry, QuickSortItemStatus.Failed, error = "kept"))
        for (status in listOf(QuickSortItemStatus.Failed, QuickSortItemStatus.Cancelled, QuickSortItemStatus.Skipped, QuickSortItemStatus.Running)) {
            val retry = job("retry", QuickSortQueueItem(entry, status, receipt = receipt, retryOrigin = QuickSortRetryOrigin("first", 0)))
            val jobs = listOf(failed, retry)
            assertSame(jobs, resolveQuickSortRetrySuccess(jobs, "retry", 0))
        }
        val unverified = job("retry", QuickSortQueueItem(entry, QuickSortItemStatus.Succeeded,
            receipt = receipt.copy(contentVerified = false), retryOrigin = QuickSortRetryOrigin("first", 0)))
        assertEquals(1, resolveQuickSortRetrySuccess(listOf(failed, unverified), "retry", 0).first().failed)
    }

    @Test fun `partial retry does not resolve unrelated failed item or a stale path`() {
        val failed = job("first", QuickSortQueueItem(entry, QuickSortItemStatus.Failed)).copy(
            items = listOf(QuickSortQueueItem(entry, QuickSortItemStatus.Failed),
                QuickSortQueueItem(entry.copy(contentId = 9, path = "/source/other.jpg"), QuickSortItemStatus.Failed)))
        val retry = job("retry", QuickSortQueueItem(entry, QuickSortItemStatus.Succeeded,
            receipt = receipt, retryOrigin = QuickSortRetryOrigin("first", 0)))
        assertEquals(1, resolveQuickSortRetrySuccess(listOf(failed, retry), "retry", 0).first().failed)
        val stale = retry.copy(items = listOf(retry.items.single().copy(entry = entry.copy(path = "/source/different.jpg"))))
        assertEquals(2, resolveQuickSortRetrySuccess(listOf(failed, stale), "retry", 0).first().failed)
    }

    @Test fun `legacy completion repairs stale failure only when fingerprint destination and entry match`() {
        val checkpoint = QuickMoveCheckpoint(entry, receipt.targetPath, null, 1000, 42, "verified-sha", QuickMoveStage.Planned)
        val failed = job("old", QuickSortQueueItem(entry, QuickSortItemStatus.Failed, checkpoint, error = "old failure"))
        val success = job("old-retry", QuickSortQueueItem(entry, QuickSortItemStatus.Succeeded,
            checkpoint.copy(stage = QuickMoveStage.Completed), receipt))
        assertEquals(1, resolveLegacyQuickSortFailures(listOf(failed, success)).first().resolved)
        val changedBytes = success.copy(items = listOf(success.items.single().copy(checkpoint = checkpoint.copy(sourceSha256 = "other-bytes"))))
        assertEquals(1, resolveLegacyQuickSortFailures(listOf(failed, changedBytes)).first().failed)
        val changedTarget = success.copy(items = listOf(success.items.single().copy(receipt = receipt.copy(targetPath = "/different/photo.jpg"))))
        assertEquals(1, resolveLegacyQuickSortFailures(listOf(failed, changedTarget)).first().failed)
        assertEquals(1, resolveLegacyQuickSortFailures(listOf(failed.copy(items = listOf(failed.items.single().copy(checkpoint = null))), success)).first().failed)
    }
}
