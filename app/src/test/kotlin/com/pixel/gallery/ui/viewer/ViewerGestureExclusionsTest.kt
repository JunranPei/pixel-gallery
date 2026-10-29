package com.pixel.gallery.ui.viewer

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ViewerGestureExclusionsTest {
    @Test fun `sheet intercepts header grid and blank space but leaves exposed media usable`() {
        val registry = ViewerGestureExclusions()
        registry.register("sheet") { Rect(0f, 300f, 400f, 800f) }
        assertFalse(registry.contains(200f, 200f))
        assertTrue(registry.contains(200f, 310f))
        assertTrue(registry.contains(30f, 500f))
        assertTrue(registry.contains(390f, 790f))
    }

    @Test fun `live window coordinates follow panel collapse and detach without a stale hit area`() {
        val registry = ViewerGestureExclusions()
        var bounds: Rect? = Rect(0f, 300f, 400f, 800f)
        registry.register("sheet") { bounds }
        assertTrue(registry.contains(200f, 500f))
        bounds = Rect(0f, 732f, 400f, 800f)
        assertFalse(registry.contains(200f, 500f))
        assertTrue(registry.contains(200f, 750f))
        bounds = null
        assertFalse(registry.contains(200f, 750f))
    }

    @Test fun `removing a sheet does not remove the separate undo button exclusion`() {
        val registry = ViewerGestureExclusions()
        registry.register("sheet") { Rect(0f, 732f, 400f, 800f) }
        registry.register("undo") { Rect(328f, 660f, 384f, 716f) }
        registry.remove("sheet")
        assertFalse(registry.contains(200f, 750f))
        assertTrue(registry.contains(350f, 680f))
        registry.remove("undo")
        assertFalse(registry.contains(350f, 680f))
    }
}
