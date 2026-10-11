package com.pixel.gallery.model

import androidx.activity.result.IntentSenderRequest
import com.pixel.gallery.data.local.entity.MediaEntry

enum class QuickSortJobStatus { Queued, NeedsPermission, Running, Completed, Cancelled, Paused }
enum class QuickSortItemStatus { Queued, Running, Succeeded, Failed, Skipped, Cancelled }
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
) {
    val completed: Int get() = items.count { it.status == QuickSortItemStatus.Succeeded }
    val failed: Int get() = items.count { it.status == QuickSortItemStatus.Failed }
    val total: Int get() = items.size
    val isTerminal: Boolean get() = status == QuickSortJobStatus.Completed || status == QuickSortJobStatus.Cancelled
}

data class QuickSortQueueState(
    val jobs: List<QuickSortJob> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
    val serviceActive: Boolean = false,
    val permissionRevision: Long = 0,
) {
    val reservedIds: Set<Long> get() = jobs.filterNot { it.isTerminal }.flatMap { it.items }
        .filter { it.status == QuickSortItemStatus.Queued || it.status == QuickSortItemStatus.Running }
        .mapTo(mutableSetOf()) { it.entry.contentId }
    val isRunning: Boolean get() = jobs.any { it.status == QuickSortJobStatus.Running }
    val needsPermission: QuickSortJob? get() = jobs.firstOrNull { it.status == QuickSortJobStatus.NeedsPermission }
    val pendingCount: Int get() = reservedIds.size
}

sealed class QuickSortEnqueueResult {
    data class Accepted(val jobId: String, val acceptedCount: Int, val duplicateCount: Int, val acceptedIds: Set<Long>) : QuickSortEnqueueResult()
    data class Rejected(val reason: String) : QuickSortEnqueueResult()
}

data class QuickSortPermissionRequest(val jobId: String, val token: String, val request: IntentSenderRequest)

data class QuickSortQueueJournal(val version: Int = 1, val jobs: List<QuickSortJob> = emptyList())

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
