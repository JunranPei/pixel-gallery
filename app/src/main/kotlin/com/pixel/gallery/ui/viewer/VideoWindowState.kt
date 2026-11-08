package com.pixel.gallery.ui.viewer

import androidx.compose.runtime.saveable.listSaver

/** Small task-local state; neither decoded frames nor players enter the saved Bundle. */
internal data class VideoWindowSnapshot(
    val uri: String,
    val position: Long,
    val wantsPlay: Boolean,
    val muted: Boolean,
    val speed: Float,
)

internal class VideoWindowState(private var saved: VideoWindowSnapshot? = null) {
    private var current: (() -> VideoWindowSnapshot)? = null

    fun snapshot(uri: String): VideoWindowSnapshot? = (current?.invoke() ?: saved)?.takeIf { it.uri == uri }

    fun attach(reader: () -> VideoWindowSnapshot) { current = reader }

    fun detach(reader: () -> VideoWindowSnapshot) {
        if (current !== reader) return
        saved = reader()
        current = null
    }

    internal fun saveableSnapshot(): List<Any> {
        val value = current?.invoke() ?: saved ?: return emptyList()
        return listOf(1, value.uri, value.position, value.wantsPlay, value.muted, value.speed)
    }

    companion object {
        val Saver = listSaver<VideoWindowState, Any>(
            save = { it.saveableSnapshot() },
            restore = { restore(it) },
        )

        internal fun restore(values: List<Any>): VideoWindowState {
            if (values.size != 6 || values[0] != 1) return VideoWindowState()
            val uri = values[1] as? String ?: return VideoWindowState()
            val position = (values[2] as? Number)?.toLong() ?: return VideoWindowState()
            val wantsPlay = values[3] as? Boolean ?: return VideoWindowState()
            val muted = values[4] as? Boolean ?: return VideoWindowState()
            val speed = (values[5] as? Number)?.toFloat() ?: return VideoWindowState()
            if (uri.isBlank() || position < 0 || !speed.isFinite() || speed <= 0) return VideoWindowState()
            return VideoWindowState(VideoWindowSnapshot(uri, position, wantsPlay, muted, speed))
        }
    }
}
