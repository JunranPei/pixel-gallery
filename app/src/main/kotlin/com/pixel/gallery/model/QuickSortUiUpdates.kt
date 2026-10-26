package com.pixel.gallery.model

/** The gallery observes batch changes; per-file progress belongs to the service notification. */
internal fun sameQuickSortUiState(previous: QuickSortQueueState, current: QuickSortQueueState): Boolean {
    if (previous.loaded != current.loaded || previous.error != current.error ||
        previous.serviceActive != current.serviceActive || previous.permissionRevision != current.permissionRevision ||
        previous.albumUses != current.albumUses || previous.jobs.size != current.jobs.size) return false
    return previous.jobs.indices.all { index ->
        val before = previous.jobs[index]
        val after = current.jobs[index]
        before === after || (after.copy(items = before.items) == before && before.items.size == after.items.size &&
            before.items.indices.all { itemIndex ->
                val old = before.items[itemIndex]
                val item = after.items[itemIndex]
                old === item || (!before.isTerminal && old.entry == item.entry && old.retryOrigin == item.retryOrigin) ||
                    (old.entry == item.entry && old.status == item.status && old.receipt == item.receipt &&
                    old.error == item.error && old.replacementReceipt == item.replacementReceipt &&
                    old.replacementReleased == item.replacementReleased && old.resolvedByJobId == item.resolvedByJobId &&
                    (old.receipt == null || (old.checkpoint != null) == (item.checkpoint != null)) &&
                    (old.replacementReceipt == null || (old.replacementCheckpoint != null) == (item.replacementCheckpoint != null)))
            })
    }
}

internal fun QuickSortQueueState.prepareForUi(): QuickSortQueueState = apply {
    reservedIds
    heldItems
    moveReceipts
    jobs.forEach { job -> job.completed; job.failed; job.resolved }
}
