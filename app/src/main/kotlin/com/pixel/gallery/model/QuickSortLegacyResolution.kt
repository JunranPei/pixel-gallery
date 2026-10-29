package com.pixel.gallery.model

import java.io.File

/** Repair old retries that predate explicit retry origins, only with matching verified fingerprints. */
fun resolveLegacyQuickSortFailures(jobs: List<QuickSortJob>): List<QuickSortJob> {
    data class Key(val id: Long, val path: String, val destination: String)
    data class Success(val job: QuickSortJob, val item: QuickSortQueueItem)
    val successes = mutableMapOf<Key, MutableList<Success>>()
    for (job in jobs) for (item in job.items) {
        val receipt = item.receipt ?: continue
        if (item.status != QuickSortItemStatus.Succeeded || item.checkpoint == null ||
            !receipt.contentVerified || !receipt.modifiedTimeVerified || !receipt.captureTimeVerified) continue
        val directory = File(receipt.targetPath).parentFile?.canonicalPath ?: continue
        successes.getOrPut(Key(item.entry.contentId, item.entry.path, directory)) { mutableListOf() }.add(Success(job, item))
    }
    var changed = false
    val resolved = jobs.map { job ->
        var jobChanged = false
        val items = job.items.map itemLoop@ { item ->
            val checkpoint = item.checkpoint ?: return@itemLoop item
            if (item.status != QuickSortItemStatus.Failed || item.retryOrigin != null ||
                item.restoreCheckpoint != null || item.replacementCheckpoint != null) return@itemLoop item
            val candidate = successes[Key(item.entry.contentId, item.entry.path, File(job.destination.path).canonicalPath)]
                ?.lastOrNull { success ->
                    val verified = success.item.checkpoint!!
                    val receipt = success.item.receipt!!
                    success.job.createdAt >= job.createdAt && success.item.entry == item.entry &&
                        verified.sourceSha256 == checkpoint.sourceSha256 && verified.sourceSize == checkpoint.sourceSize &&
                        verified.originalModifiedMillis == checkpoint.originalModifiedMillis &&
                        (checkpoint.targetPath == receipt.targetPath || !File(checkpoint.targetPath).exists())
                } ?: return@itemLoop item
            jobChanged = true
            changed = true
            item.copy(status = QuickSortItemStatus.Resolved, resolvedByJobId = candidate.job.id, error = null)
        }
        if (jobChanged) job.copy(items = items) else job
    }
    return if (changed) resolved else jobs
}
