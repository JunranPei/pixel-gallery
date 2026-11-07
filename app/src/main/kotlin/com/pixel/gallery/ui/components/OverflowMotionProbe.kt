package com.pixel.gallery.ui.components

import com.pixel.gallery.BuildConfig
import java.util.concurrent.CopyOnWriteArrayList

internal object OverflowMotionProbe {
    @Volatile var enabled = false
    data class Sample(val opening: Boolean, val progress: Float, val frame: OverflowFrame)
    val samples = CopyOnWriteArrayList<Sample>()
    fun record(opening: Boolean, progress: Float, frame: OverflowFrame) {
        if (BuildConfig.DEBUG && enabled && samples.size < 2000) samples += Sample(opening, progress, frame)
    }
}
