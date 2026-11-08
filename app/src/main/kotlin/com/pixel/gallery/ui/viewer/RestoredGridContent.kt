package com.pixel.gallery.ui.viewer

import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

internal val LocalRestoredGridContent = staticCompositionLocalOf<RestoredGridContent?> { null }

/** Holds only the composed thumbnail tokens during a restored window's first frame. */
internal class RestoredGridContent(private val gate: RestoredWindowDrawGate, private val view: View) {
    internal class Thumbnail {
        var coordinates: LayoutCoordinates? = null
        var complete = false
    }
    private val thumbnails = mutableSetOf<Thumbnail>()
    var dataReady = false
    var expectedData: Any? = null
    var presentedData: Any? = null
    var expectedLabels: Any? = null
    var presentedLabels: Any? = null
    var expectedGrouping: Any? = null
    var presentedGrouping: Any? = null
    // Null means the current data has not reached the lazy-grid layout yet.
    var visibleImageCount: (() -> Int?)? = null
    val isWaiting get() = gate.isWaiting
    private var lastDiagnostic = ""

    init {
        gate.onClosed {
            thumbnails.clear()
            visibleImageCount = null
            expectedData = null
            presentedData = null
            expectedLabels = null
            presentedLabels = null
            expectedGrouping = null
            presentedGrouping = null
        }
    }

    fun add(item: Thumbnail) { if (isWaiting) thumbnails.add(item) }
    fun remove(item: Thumbnail) { thumbnails.remove(item) }
    fun complete(item: Thumbnail) {
        // Glide's request callback precedes its Compose draw-node update. Queue
        // completion behind that delivery rather than opening the draw gate here.
        view.post {
            if (isWaiting) {
                item.complete = true
                view.invalidate()
            }
        }
    }
    fun ready(): Boolean {
        if (ViewerLoadMetrics.isEnabled) {
            val diagnostic = "data=$dataReady matched=${expectedData === presentedData} " +
                "layout=${visibleImageCount?.invoke()} registered=${thumbnails.size} " +
                "attached=${thumbnails.count { it.coordinates?.isAttached == true }} " +
                "complete=${thumbnails.count { it.complete }}"
            if (diagnostic != lastDiagnostic) {
                lastDiagnostic = diagnostic
                ViewerLoadMetrics.event("RESTORE_GRID_WAIT", diagnostic)
            }
        }
        if (!dataReady) return false
        if (expectedData == null || expectedData !== presentedData) return pendingLayout()
        if (expectedLabels !== presentedLabels || expectedGrouping !== presentedGrouping) return pendingLayout()
        val expectedImages = visibleImageCount?.invoke() ?: return pendingLayout()
        var visibleImages = 0
        for (item in thumbnails) {
            val coordinates = item.coordinates?.takeIf { it.isAttached } ?: continue
            val bounds = coordinates.boundsInWindow()
            if (bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
                bounds.left < view.width && bounds.top < view.height) {
                visibleImages++
                if (!item.complete) return false
            }
        }
        if (visibleImages < expectedImages) return pendingLayout()
        thumbnails.clear()
        visibleImageCount = null
        return true
    }

    private fun pendingLayout(): Boolean {
        // Compose can defer lazy-item layout until dispatchDraw. Since pre-draw
        // is held here, request a normal View layout to create those items first.
        view.requestLayout()
        return false
    }
}

internal class RestoredThumbnailRequest(
    val modifier: Modifier,
    val transform: (RequestBuilder<Drawable>) -> RequestBuilder<Drawable>,
)

@Composable
internal fun rememberRestoredThumbnailRequest(
    model: Any?,
    transform: (RequestBuilder<Drawable>) -> RequestBuilder<Drawable>,
): RestoredThumbnailRequest {
    val content = LocalRestoredGridContent.current
    val waiting = remember(content) { content?.isWaiting == true }
    if (!waiting || content == null) return remember(transform) { RestoredThumbnailRequest(Modifier, transform) }
    val item = remember(content, model, transform) { RestoredGridContent.Thumbnail() }
    DisposableEffect(content, item) {
        content.add(item)
        onDispose { content.remove(item) }
    }
    return remember(content, item, transform) {
        val listener = object : RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                content.complete(item)
                return false
            }
            override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                content.complete(item)
                return false
            }
        }
        RestoredThumbnailRequest(Modifier.onGloballyPositioned { item.coordinates = it }) {
            transform(it).addListener(listener)
        }
    }
}
