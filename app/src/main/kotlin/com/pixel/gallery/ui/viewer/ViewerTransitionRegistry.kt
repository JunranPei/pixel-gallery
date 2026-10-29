package com.pixel.gallery.ui.viewer

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.pixel.gallery.data.local.entity.MediaEntry
import kotlin.math.abs

internal data class ViewerTransitionKey(val uri: String, val version: Long)
internal fun MediaEntry.viewerTransitionKey() = ViewerTransitionKey(uri, dateModifiedMillis)
internal val LocalViewerTransitions = staticCompositionLocalOf<ViewerTransitionRegistry?> { null }
internal val LocalViewerGridScope = staticCompositionLocalOf<Any?> { null }

/** Only currently composed cells register; no image load or library-wide lookup is performed. */
internal class ViewerTransitionRegistry {
    data class Active(val key: ViewerTransitionKey?, val gesture: ViewerDismissGesture)
    private data class Anchor(val scope: Any?, val key: ViewerTransitionKey,
        val coordinates: LayoutCoordinates, val radius: Float, val epoch: Int)
    private val anchors = mutableMapOf<Any, Anchor>()
    var active by mutableStateOf<Active?>(null)
    var destinationScope: Any? = null
    private var epoch = 0
    val revealing: Boolean get() = active?.gesture?.isRevealing == true

    fun beginReveal() { epoch++ }
    fun register(token: Any, scope: Any?, key: ViewerTransitionKey, coordinates: LayoutCoordinates, radius: Float) {
        anchors[token] = Anchor(scope, key, coordinates, radius, epoch)
    }
    fun remove(token: Any) { anchors.remove(token) }
    fun hidden(scope: Any?, key: ViewerTransitionKey): Boolean =
        revealing && scope == destinationScope && key == active?.key

    fun target(key: ViewerTransitionKey?, origin: Offset, width: Int, height: Float): ViewerThumbnailTarget? {
        val anchor = anchors.values.lastOrNull { it.key == key && it.scope == destinationScope &&
            it.epoch == epoch && it.coordinates.isAttached } ?: return null
        val coordinates = anchor.coordinates
        val topLeft = coordinates.localToWindow(Offset.Zero) - origin
        val full = Rect(topLeft.x, topLeft.y, topLeft.x + coordinates.size.width, topLeft.y + coordinates.size.height)
        val clipped = coordinates.boundsInWindow().translate(-origin)
        // Partly clipped/offscreen/stale cells use the fade fallback, never a guessed destination.
        if (full.width <= 0 || full.height <= 0 || full.left < 0 || full.top < 0 ||
            full.right > width || full.bottom > height || abs(full.width - clipped.width) > 1f ||
            abs(full.height - clipped.height) > 1f) return null
        return ViewerThumbnailTarget(full, anchor.radius)
    }
}

internal fun Modifier.viewerThumbnailAnchor(key: ViewerTransitionKey, radius: Dp): Modifier = composed {
    val registry = LocalViewerTransitions.current
    val scope = LocalViewerGridScope.current
    val token = remember(key, scope) { Any() }
    val pixels = with(LocalDensity.current) { radius.toPx() }
    DisposableEffect(registry, token) { onDispose { registry?.remove(token) } }
    this.onGloballyPositioned { registry?.register(token, scope, key, it, pixels) }
        .drawWithContent { if (registry?.hidden(scope, key) != true) drawContent() }
}
