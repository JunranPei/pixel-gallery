package com.pixel.gallery.model

/** Match a saved folder and its descendants without matching sibling names. */
internal fun String.isInsideMediaFolder(folder: String): Boolean =
    folder.isNotEmpty() && startsWith(folder.trimEnd('/') + "/")
