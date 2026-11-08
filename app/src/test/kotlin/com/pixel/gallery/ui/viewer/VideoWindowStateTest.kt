package com.pixel.gallery.ui.viewer

import org.junit.Assert.*
import org.junit.Test

class VideoWindowStateTest {
    @Test fun twoWindowsKeepIndependentStateForTheSameVideo() {
        val paused = VideoWindowSnapshot("content://video/1", 7000, false, true, .75f)
        val playing = paused.copy(position = 11000, wantsPlay = true, muted = false, speed = 1.25f)
        val first = VideoWindowState(paused)
        val second = VideoWindowState(playing)
        assertEquals(paused, VideoWindowState.restore(first.saveableSnapshot()).snapshot(paused.uri))
        assertEquals(playing, VideoWindowState.restore(second.saveableSnapshot()).snapshot(paused.uri))
        assertNull(first.snapshot("content://video/2"))
    }

    @Test fun saveReadsCurrentPositionAndDetachReleasesTheReader() {
        val state = VideoWindowState()
        var position = 1000L
        var reads = 0
        val reader = { reads++; VideoWindowSnapshot("video", position, false, false, 1f) }
        state.attach(reader)
        position = 9500
        assertEquals(9500L, VideoWindowState.restore(state.saveableSnapshot()).snapshot("video")!!.position)
        state.detach(reader)
        val detachedReads = reads
        position = 15000
        assertEquals(9500L, state.snapshot("video")!!.position)
        state.saveableSnapshot()
        assertEquals(detachedReads, reads)
    }

    @Test fun obsoleteDisposeCannotDetachTheReplacementSession() {
        val state = VideoWindowState()
        val old = { VideoWindowSnapshot("video", 1000, false, false, 1f) }
        val next = { VideoWindowSnapshot("video", 4000, false, false, 1f) }
        state.attach(old)
        state.attach(next)
        state.detach(old)
        assertEquals(4000L, state.snapshot("video")!!.position)
    }

    @Test fun missingAndInvalidSavedStateUseFreshOpenDefaults() {
        listOf(emptyList(), listOf(2), listOf(1, "video", -1L, false, false, 1f),
            listOf(1, "video", 2L, false, false, Float.NaN)).forEach {
            assertNull(VideoWindowState.restore(it).snapshot("video"))
        }
    }
}
