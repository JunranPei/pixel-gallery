package com.pixel.gallery.model

/** Controls how new gallery entries are mapped to Android tasks. */
enum class CloneMode {
    DISABLED,
    MANUAL,
    AUTO;

    companion object {
        fun fromStoredValue(value: String?): CloneMode =
            entries.firstOrNull { it.name == value } ?: DISABLED
    }
}
