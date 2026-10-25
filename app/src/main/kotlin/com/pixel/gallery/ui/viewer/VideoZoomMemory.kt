package com.pixel.gallery.ui.viewer

import androidx.compose.animation.core.SnapSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pixel.gallery.data.repository.SavedViewerZoom
import com.pixel.gallery.data.repository.VideoSettings
import com.pixel.gallery.data.repository.VideoSettingsRepository
import kotlinx.coroutines.flow.first
import me.saket.telephoto.zoomable.ZoomableState

@Composable
internal fun VideoZoomMemory(
    state: ZoomableState, repository: VideoSettingsRepository, settings: VideoSettings?,
    source: String, active: Boolean, viewport: IntSize,
    session: VideoPlaybackSession?,
) {
    val host = LocalContext.current as? android.app.Activity
    val rememberZoom = settings?.rememberZoom
    var restored by remember(source) { mutableStateOf(false) }
    val currentViewport by rememberUpdatedState(viewport)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(source, active, rememberZoom, viewport != IntSize.Zero) {
        if (!active || rememberZoom == null || viewport == IntSize.Zero) return@LaunchedEffect
        snapshotFlow { state.contentTransformation.isSpecified }.first { it }
        val saved = if (rememberZoom) repository.zoom(true, source) else null
        state.zoomTo(saved?.scale ?: 1f, animationSpec = SnapSpec())
        if (saved != null) {
            val bounds = state.transformedContentBounds
            state.panBy(Offset(
                viewport.width / 2f - bounds.left - bounds.width * saved.centerX.coerceIn(0f, 1f),
                viewport.height / 2f - bounds.top - bounds.height * saved.centerY.coerceIn(0f, 1f),
            ), animationSpec = SnapSpec())
        }
        restored = true
    }
    DisposableEffect(source, active, rememberZoom, lifecycle, state, session) {
        fun save() {
            if (!active || !restored || host?.isInPictureInPictureMode == true) return
            host?.let { repository.saveDirection(true, source, it.visibleViewerOrientation()) }
            if (rememberZoom != true) return
            val bounds = state.transformedContentBounds
            val transform = state.contentTransformation
            if (!transform.isSpecified || bounds.width <= 0f || bounds.height <= 0f) return
            repository.saveZoom(true, source, SavedViewerZoom(transform.scaleMetadata.userZoom,
                (currentViewport.width / 2f - bounds.left) / bounds.width,
                (currentViewport.height / 2f - bounds.top) / bounds.height))
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) save() }
        val checkpoint: () -> Unit = { save() }
        session?.onPresentationCheckpoint = checkpoint
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            save()
            if (session?.onPresentationCheckpoint === checkpoint) session.onPresentationCheckpoint = null
        }
    }
}
