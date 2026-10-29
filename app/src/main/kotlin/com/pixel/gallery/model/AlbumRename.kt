package com.pixel.gallery.model

import java.io.File

fun isValidAlbumName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && !trimmed.startsWith('.') &&
        trimmed.toByteArray(Charsets.UTF_8).size <= 255 &&
        name.none { it.isISOControl() || it == '/' || it == '\\' }
}

internal fun renamedAlbumDirectory(source: File, name: String): File {
    require(isValidAlbumName(name)) { "Invalid album name" }
    val parent = requireNotNull(source.parentFile) { "Storage roots cannot be renamed" }
    val target = File(parent, name.trim())
    require(target.parentFile?.canonicalFile == parent.canonicalFile) { "Invalid album path" }
    require(target == source || !target.exists()) { "An album with this name already exists" }
    return target
}

internal fun remapAlbumPath(path: String, source: String, target: String): String = when {
    path == source -> target
    path.startsWith("$source/") -> target + path.removePrefix(source)
    else -> path
}
