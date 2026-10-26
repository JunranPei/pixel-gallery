package com.pixel.gallery.ui.quicksort

import com.pixel.gallery.model.QuickSortItemStatus
import com.pixel.gallery.model.QuickSortJob
import com.pixel.gallery.data.local.entity.MediaEntry

/** Remember completed batches even after the queue trims its older history. */
internal class QuickSortBatchVisibility {
    private val appliedJobs = mutableSetOf<String>()
    private var removedPaths: Set<String> = emptySet()
    var relocatedEntries: Map<Pair<Long, String>, MediaEntry> = emptyMap()
        private set
    private val originsByLocation = mutableMapOf<Pair<Long, String>, MutableSet<Pair<Long, String>>>()

    fun update(jobs: List<QuickSortJob>, sessionId: String?): Set<String> {
        if (sessionId == null) return emptySet()
        for (job in jobs) {
            if (job.sessionId != sessionId || !job.isTerminal || !appliedJobs.add(job.id)) continue
            val receipts = job.items.filter { it.status == QuickSortItemStatus.Succeeded }.mapNotNull { it.receipt }
            val relocated = relocatedEntries.toMutableMap()
            for (item in job.items) {
                val receipt = item.receipt ?: continue
                if (item.status != QuickSortItemStatus.Succeeded) continue
                val sourceLocation = receipt.sourceId to receipt.sourcePath
                val origins = originsByLocation.remove(sourceLocation) ?: mutableSetOf(sourceLocation)
                origins.add(sourceLocation)
                val destination = item.entry.copy(contentId = receipt.targetId, uri = receipt.targetUri,
                    path = receipt.targetPath, dateModifiedMillis = receipt.modifiedMillis,
                    sourceDateTakenMillis = receipt.dateTakenMillis)
                origins.forEach { relocated[it] = destination }
                originsByLocation.getOrPut(receipt.targetId to receipt.targetPath) { mutableSetOf() }.addAll(origins)
            }
            relocatedEntries = relocated
            removedPaths = if (job.undoOfJobId == null) {
                removedPaths + receipts.map { it.sourcePath }
            } else {
                removedPaths - receipts.map { it.targetPath }.toSet()
            }
        }
        return removedPaths
    }
}
