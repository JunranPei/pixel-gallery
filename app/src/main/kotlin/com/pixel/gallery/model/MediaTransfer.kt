package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import java.io.File

enum class TransferMode {
    COPY,
    MOVE
}

enum class ConflictPolicy {
    KEEP_BOTH,
    SKIP,
    REPLACE
}

data class TransferDestination(
    val stableKey: String,
    val displayName: String,
    val path: String,
    val coverUri: String? = null,
    val itemCount: Int = 0,
    val lastModified: Long = 0L
)

data class TransferProgress(
    val mode: TransferMode,
    val completed: Int,
    val total: Int,
    val currentName: String
)

data class TransferItemFailure(
    val entry: MediaEntry,
    val reason: String
)

data class TransferSummary(
    val mode: TransferMode,
    val succeeded: Int,
    val skipped: Int,
    val failures: List<TransferItemFailure>
) {
    val failed: Int get() = failures.size
    val completedAny: Boolean get() = succeeded > 0
}

fun buildTransferDestinations(entries: List<MediaEntry>): List<TransferDestination> =
    entries
        .filter { it.path.isNotBlank() }
        .groupBy { File(it.path).parentFile?.absolutePath?.trimEnd(File.separatorChar).orEmpty() }
        .filterKeys { it.isNotEmpty() }
        .map { (path, albumEntries) ->
            val cover = albumEntries.maxByOrNull { it.bestTimestamp } ?: albumEntries.first()
            TransferDestination(
                stableKey = path,
                displayName = File(path).name.ifEmpty { path },
                path = path,
                coverUri = cover.uri,
                itemCount = albumEntries.size,
                lastModified = albumEntries.maxOfOrNull { it.dateModifiedMillis } ?: 0L
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

fun getAvailableTransferName(originalName: String, exists: (String) -> Boolean): String {
    if (!exists(originalName)) return originalName
    val dot = originalName.lastIndexOf('.')
    val baseName = if (dot > 0) originalName.substring(0, dot) else originalName
    val extension = if (dot > 0) originalName.substring(dot) else ""
    var index = 1
    while (true) {
        val candidate = "$baseName ($index)$extension"
        if (!exists(candidate)) return candidate
        index++
    }
}
