package com.pixel.gallery.ui.viewer

import com.google.gson.Gson
import com.pixel.gallery.data.repository.ImageViewerSettings
import com.pixel.gallery.data.repository.VideoSettings
import com.pixel.gallery.data.repository.ViewerScreenDirection
import org.junit.Assert.*
import org.junit.Test

class ViewerPreferencesMigrationTest {
    @Test fun olderPlayerSettingsKeepPlaybackChoicesAndReceiveSafeViewerDefaults() {
        val settings = Gson().fromJson("""{"autoplay":false,"muted":true,"rememberSpeed":true,"lastSpeed":1.5}""", VideoSettings::class.java)
        assertFalse(settings.autoplay)
        assertTrue(settings.muted)
        assertTrue(settings.rememberSpeed)
        assertEquals(1.5f, settings.lastSpeed)
        assertEquals(ViewerScreenDirection.SYSTEM, settings.screenDirection)
        assertFalse(settings.rememberDirection)
        assertFalse(settings.rememberZoom)
    }

    @Test fun newImagePreferencesPreserveExistingZoomMemoryDefault() {
        val settings = Gson().fromJson("{}", ImageViewerSettings::class.java)
        assertTrue(settings.rememberZoom)
        assertFalse(settings.rememberDirection)
        assertEquals(ViewerScreenDirection.SYSTEM, settings.screenDirection)
    }

    @Test fun imageAndVideoPreferencesHaveIndependentMemoryChoices() {
        val gson = Gson()
        val image = ImageViewerSettings(ViewerScreenDirection.LANDSCAPE, rememberDirection = true, rememberZoom = false)
        val video = VideoSettings(screenDirection = ViewerScreenDirection.PORTRAIT, rememberZoom = true)
        assertEquals(image, gson.fromJson(gson.toJson(image), ImageViewerSettings::class.java))
        assertEquals(video, gson.fromJson(gson.toJson(video), VideoSettings::class.java))
    }
}
