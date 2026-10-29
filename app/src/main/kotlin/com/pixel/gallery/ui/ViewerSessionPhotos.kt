package com.pixel.gallery.ui

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.TransferReceipt

internal fun reconcileViewerSessionPhotos(
    current: List<MediaEntry>,
    latest: List<MediaEntry>,
): List<MediaEntry> {
    if (current === latest || latest.isEmpty()) return current
    if (current.isEmpty()) return latest
    val latestById = latest.associateBy(MediaEntry::contentId)
    return updateViewerPhotos(current) { entry -> latestById[entry.contentId] ?: entry }
}

/** Replay only matching moves, in journal order, including repeated moves and ID changes. */
internal class ViewerMoveReceiptIndex(receipts: List<TransferReceipt>) {
    private data class OrderedMove(val position: Int, val receipt: TransferReceipt)

    // Index both ID and path: an ID alone can refer to a stale/different file.
    private val bySource = mutableMapOf<Long, MutableMap<String, MutableList<OrderedMove>>>()

    init {
        receipts.forEachIndexed { position, receipt ->
            bySource.getOrPut(receipt.sourceId) { mutableMapOf() }
                .getOrPut(receipt.sourcePath) { mutableListOf() }
                .add(OrderedMove(position, receipt))
        }
    }

    fun remap(current: List<MediaEntry>): List<MediaEntry> {
        if (bySource.isEmpty()) return current
        return updateViewerPhotos(current) { original ->
            var entry = original
            var lastPosition = -1
            while (true) {
                val move = bySource[entry.contentId]?.get(entry.path)
                    ?.firstOrNull { it.position > lastPosition } ?: break
                val receipt = move.receipt
                entry = entry.copy(
                    contentId = receipt.targetId,
                    uri = receipt.targetUri,
                    path = receipt.targetPath,
                    dateModifiedMillis = receipt.modifiedMillis,
                    sourceDateTakenMillis = receipt.dateTakenMillis,
                )
                // Never replay an earlier receipt after following a chain (or cycle).
                lastPosition = move.position
            }
            entry
        }
    }
}

internal fun remapMovedViewerPhotos(
    current: List<MediaEntry>,
    receipts: List<TransferReceipt>,
): List<MediaEntry> = ViewerMoveReceiptIndex(receipts).remap(current)

internal fun refreshViewerSessionPhotos(
    current: List<MediaEntry>,
    latest: List<MediaEntry>,
    receipts: List<TransferReceipt>,
): List<MediaEntry> {
    val moves = ViewerMoveReceiptIndex(receipts)
    val movedCurrent = moves.remap(current)
    // Entry starts with the live list itself. Avoid a second replay and a full
    // ID map when there is no independent metadata snapshot to reconcile.
    val movedLatest = if (current === latest) movedCurrent else moves.remap(latest)
    return reconcileViewerSessionPhotos(movedCurrent, movedLatest)
}

/** Preserve list identity and avoid copying the whole gallery unless an entry actually changes. */
private inline fun updateViewerPhotos(
    current: List<MediaEntry>,
    update: (MediaEntry) -> MediaEntry,
): List<MediaEntry> {
    var changed: MutableList<MediaEntry>? = null
    current.forEachIndexed { index, original ->
        val updated = update(original)
        if (updated != original) {
            val result = changed ?: current.toMutableList().also { changed = it }
            result[index] = updated
        }
    }
    return changed ?: current
}
