package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry

/** Same effective date used for media and album time sorting. */
internal fun MediaEntry.chronologicalTimestamp(): Long {
    val addedMillis = dateAddedSecs.takeIf { it > 0L }?.times(1000L) ?: 0L
    val scannedTimestamp = bestTimestamp.takeIf {
        it > 0L && (addedMillis == 0L || it != addedMillis)
    }
    return sourceDateTakenMillis?.takeIf { it > 0L }
        ?: scannedTimestamp
        ?: dateModifiedMillis.takeIf { it > 0L }
        ?: addedMillis
}
