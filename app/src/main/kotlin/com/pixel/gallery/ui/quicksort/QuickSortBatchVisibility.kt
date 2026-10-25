package com.pixel.gallery.ui.quicksort

import com.pixel.gallery.model.QuickSortItemStatus
import com.pixel.gallery.model.QuickSortJob

/** Remember completed batches even after the queue trims its older history. */
internal class QuickSortBatchVisibility {
    private val appliedJobs = mutableSetOf<String>()
    private var removedPaths: Set<String> = emptySet()

    fun update(jobs: List<QuickSortJob>, sessionId: String?): Set<String> {
        if (sessionId == null) return emptySet()
        for (job in jobs) {
            if (job.sessionId != sessionId || !job.isTerminal || !appliedJobs.add(job.id)) continue
            val receipts = job.items.filter { it.status == QuickSortItemStatus.Succeeded }.mapNotNull { it.receipt }
            removedPaths = if (job.undoOfJobId == null) {
                removedPaths + receipts.map { it.sourcePath }
            } else {
                removedPaths - receipts.map { it.targetPath }.toSet()
            }
        }
        return removedPaths
    }
}
