package com.pixel.gallery.model

import androidx.activity.result.IntentSenderRequest
import com.pixel.gallery.data.local.entity.MediaEntry

enum class QuickSortJobStatus { Queued, NeedsPermission, Running, Completed, Cancelled, Paused, NeedsConflictDecision }
enum class QuickSortItemStatus { Queued, Running, Succeeded, Failed, Skipped, Cancelled, Resolved }

data class QuickSortRetryOrigin(val jobId: String, val itemIndex: Int)
enum class QuickMoveStage { Planned, TargetReady, SourceRemoved, Completed }

/** Every checkpoint is persisted before the next filesystem mutation. */
data class QuickMoveCheckpoint(
    val source: MediaEntry,
    val targetPath: String,
    val temporaryPath: String?,
    val originalModifiedMillis: Long,
    val sourceSize: Long,
    val sourceSha256: String,
    val stage: QuickMoveStage = QuickMoveStage.Planned,
    val targetUri: String? = null,
)

data class TransferReceipt(
    val sourceId: Long,
    val sourceUri: String,
    val sourcePath: String,
    val targetId: Long,
    val targetUri: String,
    val targetPath: String,
    val modifiedMillis: Long,
    val dateTakenMillis: Long?,
    val contentVerified: Boolean,
    val modifiedTimeVerified: Boolean,
    val captureTimeVerified: Boolean,
    val mediaStoreAddedTimePreserved: Boolean,
    val note: String? = null,
)

data class QuickSortQueueItem(
    val entry: MediaEntry,
    val status: QuickSortItemStatus = QuickSortItemStatus.Queued,
    val checkpoint: QuickMoveCheckpoint? = null,
    val receipt: TransferReceipt? = null,
    val error: String? = null,
    val restoreCheckpoint: QuickMoveCheckpoint? = null,
    val replacementCheckpoint: QuickMoveCheckpoint? = null,
    val replacementReceipt: TransferReceipt? = null,
    val replacementRestoreCheckpoint: QuickMoveCheckpoint? = null,
    val replacementRestoreReceipt: TransferReceipt? = null,
    val replacementReleased: Boolean = false,
    val retryOrigin: QuickSortRetryOrigin? = null,
    val resolvedByJobId: String? = null,
)

data class QuickSortJob(
    val id: String,
    val destination: TransferDestination,
    val items: List<QuickSortQueueItem>,
    val createdAt: Long,
    val status: QuickSortJobStatus = QuickSortJobStatus.Queued,
    val permissionToken: String? = null,
    val permissionGranted: Boolean = false,
    val cancelRequested: Boolean = false,
    val firstSucceededAt: Long? = null,
    val sessionId: String? = null,
    val undoRequested: Boolean = false,
    val undoJobId: String? = null,
    val undoOfJobId: String? = null,
    val conflictPolicy: ConflictPolicy? = null,
    val conflictCount: Int = 0,
    val sessionClosed: Boolean = false,
    val sessionProcessId: String? = null,
) {
    val completed: Int get() = items.count { it.status == QuickSortItemStatus.Succeeded }
    val failed: Int get() = items.count { it.status == QuickSortItemStatus.Failed }
    val resolved: Int get() = items.count { it.status == QuickSortItemStatus.Resolved }
    val total: Int get() = items.size
    val isTerminal: Boolean get() = status == QuickSortJobStatus.Completed || status == QuickSortJobStatus.Cancelled
}

/** A verified retry settles the exact old failed item and any earlier retries of it. */
fun resolveQuickSortRetrySuccess(jobs: List<QuickSortJob>, jobId: String, itemIndex: Int): List<QuickSortJob> {
    val success = jobs.firstOrNull { it.id == jobId }?.items?.getOrNull(itemIndex) ?: return jobs
    val receipt = success.receipt ?: return jobs
    if (success.status != QuickSortItemStatus.Succeeded || !receipt.contentVerified || !receipt.modifiedTimeVerified || !receipt.captureTimeVerified) return jobs
    var origin = success.retryOrigin ?: return jobs
    var updated = jobs
    val visited = mutableSetOf<QuickSortRetryOrigin>()
    while (visited.add(origin)) {
        val previousJob = updated.firstOrNull { it.id == origin.jobId } ?: break
        val previous = previousJob.items.getOrNull(origin.itemIndex) ?: break
        if (previous.entry.contentId != success.entry.contentId || previous.entry.path != success.entry.path) break
        if (previous.status != QuickSortItemStatus.Failed && previous.status != QuickSortItemStatus.Resolved) break
        val currentOrigin = origin
        updated = updated.map { job ->
            if (job.id != currentOrigin.jobId) job else job.copy(items = job.items.mapIndexed { index, item ->
                if (index != currentOrigin.itemIndex) item else item.copy(status = QuickSortItemStatus.Resolved,
                    resolvedByJobId = jobId, error = null)
            })
        }
        origin = previous.retryOrigin ?: break
    }
    return updated
}

data class QuickSortQueueState(
    val jobs: List<QuickSortJob> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
    val serviceActive: Boolean = false,
    val permissionRevision: Long = 0,
    val albumUses: List<QuickSortAlbumUse> = emptyList(),
) {
    val reservedIds: Set<Long> get() = jobs.filterNot { it.isTerminal }.flatMap { it.items }
        .filter { it.status == QuickSortItemStatus.Queued || it.status == QuickSortItemStatus.Running }
        .mapTo(mutableSetOf()) { it.entry.contentId }
    val isRunning: Boolean get() = jobs.any { it.status == QuickSortJobStatus.Running }
    val needsPermission: QuickSortJob? get() = jobs.firstOrNull { it.status == QuickSortJobStatus.NeedsPermission }
    val needsConflictDecision: QuickSortJob? get() = jobs.firstOrNull { it.status == QuickSortJobStatus.NeedsConflictDecision }
    val pendingCount: Int get() = reservedIds.size
}

/** Undo belongs to the current quick-sort session; reverse jobs are never undo candidates. */
fun latestUndoableQuickSortJob(state: QuickSortQueueState, sessionId: String?): QuickSortJob? {
    if (sessionId == null || state.jobs.any { (it.undoOfJobId != null && !it.isTerminal) ||
        (it.undoRequested && it.undoJobId == null) }) return null
    return state.jobs.lastOrNull { job ->
        job.sessionId == sessionId && job.undoOfJobId == null && !job.undoRequested &&
            !job.sessionClosed && job.items.any { it.status == QuickSortItemStatus.Queued || it.status == QuickSortItemStatus.Running ||
                (it.status == QuickSortItemStatus.Succeeded && it.receipt != null && it.checkpoint != null) ||
                (!it.replacementReleased && it.replacementReceipt != null && it.replacementCheckpoint != null) }
    }
}

fun quickSortUndoItems(job: QuickSortJob): List<QuickSortQueueItem> = buildList {
    fun reverse(receipt: TransferReceipt, checkpoint: QuickMoveCheckpoint) = QuickSortQueueItem(
        entry = checkpoint.source.copy(contentId = receipt.targetId, uri = receipt.targetUri,
            path = receipt.targetPath, dateModifiedMillis = receipt.modifiedMillis,
            sourceDateTakenMillis = receipt.dateTakenMillis), restoreCheckpoint = checkpoint)
    job.items.asReversed().forEach { item ->
        if (item.status == QuickSortItemStatus.Succeeded && item.receipt != null && item.checkpoint != null) {
            add(reverse(item.receipt, item.checkpoint))
        }
        // Restore the displaced destination only after moving the incoming file back.
        if (!item.replacementReleased && item.replacementReceipt != null && item.replacementCheckpoint != null) {
            add(reverse(item.replacementReceipt, item.replacementCheckpoint))
        }
    }
}

sealed class QuickSortEnqueueResult {
    data class Accepted(val jobId: String, val acceptedCount: Int, val duplicateCount: Int, val acceptedIds: Set<Long>) : QuickSortEnqueueResult()
    data class Rejected(val reason: String) : QuickSortEnqueueResult()
}

data class QuickSortPermissionRequest(val jobId: String, val token: String, val request: IntentSenderRequest)

data class QuickSortQueueJournal(
    val version: Int = 1,
    val jobs: List<QuickSortJob> = emptyList(),
    val albumUses: List<QuickSortAlbumUse>? = emptyList(),
)

enum class QuickMoveRecoveryDecision { KeepOriginal, RepairCommitted, NeedsReview }

/** Recovery is deliberately non-destructive even when both copies are byte-for-byte identical. */
fun quickMoveRecoveryDecision(sourceExists: Boolean, sourceVerified: Boolean,
    targetExists: Boolean, targetVerified: Boolean): QuickMoveRecoveryDecision = when {
    sourceExists && sourceVerified -> QuickMoveRecoveryDecision.KeepOriginal
    !sourceExists && targetExists && targetVerified -> QuickMoveRecoveryDecision.RepairCommitted
    else -> QuickMoveRecoveryDecision.NeedsReview
}

fun matchesQuickSortPermission(job: QuickSortJob, jobId: String, token: String): Boolean =
    job.id == jobId && job.status == QuickSortJobStatus.NeedsPermission && job.permissionToken == token

/** Reservation includes canonical paths as well as IDs: a stale/new MediaStore ID must not duplicate work. */
fun selectUnreservedQuickSortEntries(entries: List<MediaEntry>, state: QuickSortQueueState): List<MediaEntry> {
    fun key(entry: MediaEntry) = runCatching { java.io.File(entry.path).canonicalPath }.getOrDefault(entry.path)
    val active = state.jobs.filterNot { it.isTerminal }.flatMap { it.items }
        .filter { it.status == QuickSortItemStatus.Queued || it.status == QuickSortItemStatus.Running }
    val ids = active.mapTo(mutableSetOf()) { it.entry.contentId }
    val paths = active.mapTo(mutableSetOf()) { key(it.entry) }
    return entries.filter { entry ->
        val path = key(entry)
        if (entry.contentId in ids || path in paths) false else {
            ids += entry.contentId
            paths += path
            true
        }
    }
}

/** Cancels only unstarted items. The worker finishes one already-started file at a commit boundary. */
fun cancelQuickSortJob(job: QuickSortJob): QuickSortJob {
    val items = job.items.map { if (it.status == QuickSortItemStatus.Queued) it.copy(status = QuickSortItemStatus.Cancelled) else it }
    return job.copy(items = items, cancelRequested = true,
        status = if (items.any { it.status == QuickSortItemStatus.Running }) job.status else QuickSortJobStatus.Cancelled)
}
