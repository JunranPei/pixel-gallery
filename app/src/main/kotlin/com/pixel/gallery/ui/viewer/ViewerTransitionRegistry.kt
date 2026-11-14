package com.pixel.gallery.ui.viewer

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import java.lang.ref.WeakReference
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

internal data class ViewerTransitionKey(val uri: String, val version: Long)
internal fun MediaEntry.viewerTransitionKey() = ViewerTransitionKey(uri, dateModifiedMillis)
internal val LocalViewerTransitions = staticCompositionLocalOf<ViewerTransitionRegistry?> { null }
internal val LocalViewerGridScope = staticCompositionLocalOf<Any?> { null }
internal data class ViewerOpeningSeed(val key: ViewerTransitionKey,
    val target: ViewerThumbnailTarget, val bitmap: Bitmap? = null)

/** Only currently composed cells register; no image load or library-wide lookup is performed. */
internal class ViewerTransitionRegistry {
    data class Active(val key: ViewerTransitionKey?, val gesture: ViewerDismissGesture)
    private data class Anchor(val scope: Any?, val key: ViewerTransitionKey,
        val coordinates: LayoutCoordinates, val radius: Float, val epoch: Int)
    private val anchors = mutableMapOf<Any, Anchor>()
    private val images = mutableMapOf<Pair<Any?, ViewerTransitionKey>, WeakReference<Bitmap>>()
    private var openingSeed: ViewerOpeningSeed? = null
    var active by mutableStateOf<Active?>(null)
    var opening by mutableStateOf<ViewerOpeningTransition?>(null)
    var destinationScope: Any? = null
    private var epoch = 0
    val revealing: Boolean get() = opening?.isRunning == true || active?.gesture?.isRevealing == true
    val backdropAlpha: Float get() = opening?.takeIf { it.isRunning }?.visual?.backdropAlpha
        ?: active?.gesture?.visual?.backdropAlpha ?: 1f

    fun beginReveal() { epoch++ }
    fun register(token: Any, scope: Any?, key: ViewerTransitionKey, coordinates: LayoutCoordinates, radius: Float) {
        anchors[token] = Anchor(scope, key, coordinates, radius, epoch)
    }
    fun remove(token: Any) { anchors.remove(token) }
    fun image(scope: Any?, key: ViewerTransitionKey, bitmap: Bitmap?) {
        if (bitmap == null) images.remove(scope to key)
        else images[scope to key] = WeakReference(bitmap)
    }
    // Capture before navigation changes the header, grid placement or window insets.
    fun captureOpening(key: ViewerTransitionKey) {
        openingSeed = null
        val anchor = anchors.values.lastOrNull {
            it.key == key && it.scope == destinationScope && it.coordinates.isAttached
        } ?: return
        val c = anchor.coordinates
        val p = c.localToWindow(Offset.Zero)
        val full = Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
        val clipped = c.boundsInWindow()
        if (full.width <= 0 || full.height <= 0 ||
            abs(full.width - clipped.width) > 1f || abs(full.height - clipped.height) > 1f) return
        val bitmap = images[anchor.scope to key]?.get()?.takeUnless { it.isRecycled }
        openingSeed = ViewerOpeningSeed(key, ViewerThumbnailTarget(full, anchor.radius), bitmap)
    }
    fun consumeOpening(key: ViewerTransitionKey?): ViewerOpeningSeed? {
        val seed = openingSeed.takeIf { it?.key == key }
        openingSeed = null
        return seed
    }
    fun hidden(scope: Any?, key: ViewerTransitionKey): Boolean =
        scope == destinationScope && ((active?.gesture?.isRevealing == true && key == active?.key) ||
            (opening?.isRunning == true && opening?.preview != null && key == opening?.key))

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

/** Borrow the grid's existing decoded pixels; do not issue a second image request. */
@Composable
internal fun rememberViewerThumbnailListener(key: ViewerTransitionKey): RequestListener<Drawable> {
    val registry = LocalViewerTransitions.current
    val scope = LocalViewerGridScope.current
    DisposableEffect(registry, scope, key) {
        onDispose { registry?.image(scope, key, null) }
    }
    return remember(registry, scope, key) {
        object : RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>,
                isFirstResource: Boolean): Boolean {
                registry?.image(scope, key, null)
                return false
            }
            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>,
                dataSource: DataSource, isFirstResource: Boolean): Boolean {
                registry?.image(scope, key, (resource as? BitmapDrawable)?.bitmap)
                return false
            }
        }
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
