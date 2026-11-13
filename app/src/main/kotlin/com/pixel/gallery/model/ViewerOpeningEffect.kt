package com.pixel.gallery.model

enum class ViewerOpeningEffect {
    NONE, THUMBNAIL, SCALE_FADE;

    companion object {
        fun fromPreference(value: String?): ViewerOpeningEffect =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}
