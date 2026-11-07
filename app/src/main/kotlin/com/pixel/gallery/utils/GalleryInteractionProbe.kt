package com.pixel.gallery.utils

import com.pixel.gallery.BuildConfig

/** Optional runtime observations; retains no view, media or Activity references. */
internal object GalleryInteractionProbe {
    @Volatile var enabled = false
    data class Navigation(val selected: Int, val settled: Int, val target: Int, val scrolling: Boolean)
    @Volatile var navigation: Navigation? = null
    @Volatile var albumWeight = 0f
    fun navigation(selected: Int, settled: Int, target: Int, scrolling: Boolean) {
        if (BuildConfig.DEBUG && enabled) navigation = Navigation(selected, settled, target, scrolling)
    }
    fun weight(value: Float) {
        if (BuildConfig.DEBUG && enabled) albumWeight = value
    }
}
