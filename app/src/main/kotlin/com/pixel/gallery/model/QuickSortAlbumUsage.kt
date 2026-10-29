package com.pixel.gallery.model

/** One successful batch is one use, regardless of its photo count. */
data class QuickSortAlbumUse(val jobId: String, val destinationKey: String, val usedAt: Long)

const val QUICK_SORT_USAGE_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000

fun quickSortAlbumUses(
    previous: List<QuickSortAlbumUse>,
    jobs: List<QuickSortJob>,
    now: Long,
): List<QuickSortAlbumUse> = (previous + jobs.filter { it.completed > 0 && it.undoOfJobId == null && !it.undoRequested }.map {
    // Existing journals have no success timestamp; their batch creation time is the migration fallback.
    QuickSortAlbumUse(it.id, it.destination.stableKey, it.firstSucceededAt ?: it.createdAt)
}).distinctBy { it.jobId }.filter { it.usedAt in (now - QUICK_SORT_USAGE_WINDOW_MILLIS)..now &&
    jobs.none { job -> job.id == it.jobId && job.undoRequested } }

fun frequentQuickSortAlbums(
    available: List<TransferDestination>,
    uses: List<QuickSortAlbumUse>,
    now: Long,
    limit: Int = 8,
): List<TransferDestination> {
    val stats = uses.distinctBy { it.jobId }
        .filter { it.usedAt in (now - QUICK_SORT_USAGE_WINDOW_MILLIS)..now }
        .groupBy { it.destinationKey }
    return available.distinctBy { it.stableKey }.filter { it.stableKey in stats }
        .sortedWith(compareByDescending<TransferDestination> { stats.getValue(it.stableKey).size }
            .thenByDescending { stats.getValue(it.stableKey).maxOf(QuickSortAlbumUse::usedAt) }
            .thenBy { it.stableKey })
        .take(limit.coerceAtLeast(0))
}
