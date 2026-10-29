package com.pixel.gallery.ui.viewer

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import com.pixel.gallery.MainActivity

/** Window-level recognizers must respect overlays before dispatching to Compose/native images. */
internal class ViewerGestureExclusions {
    private val areas = mutableMapOf<Any, () -> Rect?>()
    fun register(token: Any, bounds: () -> Rect?) { areas[token] = bounds }
    fun remove(token: Any) { areas.remove(token) }
    fun contains(x: Float, y: Float): Boolean = areas.values.any { it()?.contains(Offset(x, y)) == true }
}

internal fun Modifier.excludeViewerGestures(): Modifier = composed {
    val registry = (LocalContext.current as? MainActivity)?.viewerGestureExclusions
    val token = remember { Any() }
    DisposableEffect(registry, token) { onDispose { registry?.remove(token) } }
    onGloballyPositioned { coordinates ->
        registry?.register(token) { if (coordinates.isAttached) coordinates.boundsInWindow() else null }
    }
}
