package com.pixel.gallery.ui.viewer

import android.graphics.Bitmap
import android.os.SystemClock
import com.pixel.gallery.utils.AppLogger
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class VideoSurfaceTransition(initialTexture: Boolean) {
    var texture by mutableStateOf(initialTexture)
    var cover by mutableStateOf<Bitmap?>(null)
    var drawnCover by mutableStateOf<Bitmap?>(null)
    var waitingAfter = -1
    var switchStarted = 0L
}

@Composable
internal fun rememberVideoSurfaceTransition(session: VideoPlaybackSession?, wantTexture: Boolean): VideoSurfaceTransition {
    val state = remember(session) { VideoSurfaceTransition(wantTexture) }
    LaunchedEffect(session, wantTexture) {
        if (session == null) return@LaunchedEffect
        if (state.texture != wantTexture) {
            if (state.cover == null) {
                val width = session.native?.width ?: session.videoSize.width
                val height = session.native?.height ?: session.videoSize.height
                if (width > 0 && height > 0) {
                    val scale = minOf(1f, 1440f / maxOf(width, height))
                    try {
                        // Capture either outgoing output before it is detached.
                        state.cover = withTimeoutOrNull(200) {
                            captureSessionFrame(session, (width * scale).toInt().coerceAtLeast(1),
                                (height * scale).toInt().coerceAtLeast(1))
                        } ?: state.cover
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A missing capture must not block playback. */ }
                }
            }
            val cover = state.cover
            if (cover != null) {
                val drawn = withTimeoutOrNull(250) {
                    snapshotFlow { state.drawnCover === cover }.first { it }
                } == true
                if (drawn) {
                    withFrameNanos { }
                    withFrameNanos { }
                }
            }
            state.waitingAfter = session.outputReadiness.generation
            state.switchStarted = SystemClock.elapsedRealtime()
            state.texture = wantTexture
        }
        if (state.cover != null && state.waitingAfter >= 0) {
            val ready = withTimeoutOrNull(1500) {
                snapshotFlow { session.outputReadiness.readyAfter(state.waitingAfter, wantTexture) }.first { it }
            } == true
            if (ready) {
                withFrameNanos { }
                withFrameNanos { }
            }
            AppLogger.log("GalleryVideo", "handoff texture=$wantTexture ready=$ready durationMs=${SystemClock.elapsedRealtime() - state.switchStarted} generation=${session.outputReadiness.generation}")
            state.cover = null
            state.drawnCover = null
            state.waitingAfter = -1
        } else if (state.texture == wantTexture) {
            state.cover = null
            state.drawnCover = null
        }
    }
    return state
}
