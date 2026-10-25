package com.pixel.gallery.ui.viewer

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class VideoSurfaceTransition(initialTexture: Boolean) {
    var texture by mutableStateOf(initialTexture)
    var cover by mutableStateOf<Bitmap?>(null)
    var waitingAfter = -1
}

@Composable
internal fun rememberVideoSurfaceTransition(session: VideoPlaybackSession?, wantTexture: Boolean): VideoSurfaceTransition {
    val state = remember(session) { VideoSurfaceTransition(wantTexture) }
    LaunchedEffect(session, wantTexture) {
        if (session == null) return@LaunchedEffect
        if (state.texture != wantTexture) {
            if (!wantTexture) {
                val width = session.native?.width ?: session.videoSize.width
                val height = session.native?.height ?: session.videoSize.height
                if (width > 0 && height > 0) {
                    val scale = minOf(1f, 1440f / maxOf(width, height))
                    try {
                        // One readback at the end of a turn, while the outgoing texture is still alive.
                        state.cover = withTimeoutOrNull(200) {
                            captureSessionFrame(session, (width * scale).toInt().coerceAtLeast(1),
                                (height * scale).toInt().coerceAtLeast(1))
                        } ?: state.cover
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A missing capture must not block playback. */ }
                }
                if (state.cover != null) {
                    // Commit the cover before AndroidView detaches the outgoing surface.
                    withFrameNanos { }
                    withFrameNanos { }
                }
            }
            state.waitingAfter = session.outputReadiness.generation
            state.texture = wantTexture
        }
        if (state.cover != null && state.waitingAfter >= 0) {
            val ready = withTimeoutOrNull(1500) {
                snapshotFlow { session.outputReadiness.readyAfter(state.waitingAfter, wantTexture) }.first { it }
            } == true
            if (ready) {
                withFrameNanos { }
                withFrameNanos { }
            } else android.util.Log.w("GalleryVideo", "Output handoff timed out; releasing frame cover")
            state.cover = null
            state.waitingAfter = -1
        } else if (state.texture == wantTexture) {
            state.cover = null
        }
    }
    return state
}
