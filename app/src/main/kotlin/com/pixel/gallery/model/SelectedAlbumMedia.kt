package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Resolve only when starting a duplicate scan; preserve source order and selection scope. */
internal suspend fun collectSelectedAlbumMedia(
    source: List<MediaEntry>,
    paths: Set<String>,
    labelMediaIds: Set<Long>,
    hidden: Boolean,
): List<MediaEntry> {
    if (paths.isEmpty() && (hidden || labelMediaIds.isEmpty())) return emptyList()
    val result = ArrayList<MediaEntry>()
    source.forEachIndexed { index, entry ->
        if (index % 256 == 0) coroutineContext.ensureActive()
        val selected = if (hidden) paths.any { entry.path.isInsideMediaFolder(it) }
        else entry.contentId in labelMediaIds || (paths.isNotEmpty() && parentPath(entry.path) in paths)
        if (selected) result.add(entry)
    }
    return result
}

private fun parentPath(path: String): String {
    val normalized = path.replace('\\', '/')
    val separator = normalized.lastIndexOf('/')
    return when {
        separator == 0 -> "/"
        separator > 0 -> normalized.substring(0, separator)
        else -> ""
    }
}
