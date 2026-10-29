package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.utils.MimeTypes

enum class MediaTypeFilter {
    ALL, IMAGES, VIDEOS;

    fun matches(entry: MediaEntry): Boolean = when (this) {
        ALL -> true
        IMAGES -> MimeTypes.isImage(entry.sourceMimeType)
        VIDEOS -> MimeTypes.isVideo(entry.sourceMimeType)
    }
}
