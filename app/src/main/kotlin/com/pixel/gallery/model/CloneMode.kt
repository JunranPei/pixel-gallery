package com.pixel.gallery.model

/** Legacy storage values for the automatic-clone preference. */
enum class CloneMode {
    DISABLED,
    MANUAL,
    AUTO;

    companion object {
        fun fromStoredValue(value: String?): CloneMode =
            entries.firstOrNull { it.name == value } ?: DISABLED

        fun automaticEnabledFromStoredValue(value: String?): Boolean =
            value == AUTO.name
    }
}
