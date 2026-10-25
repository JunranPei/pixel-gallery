package com.pixel.gallery.ui.viewer

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException

/** One viewport-sized capture per dismiss, never a per-frame readback loop. */
@Composable
internal fun rememberVideoDismissFrame(session: VideoPlaybackSession?, revealing: Boolean): Bitmap? {
    var frame by remember(session) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(session, revealing) {
        if (!revealing) { frame = null; return@LaunchedEffect }
        if (session == null) return@LaunchedEffect
        val width = session.native?.width ?: session.player.videoSize.width
        val height = session.native?.height ?: session.player.videoSize.height
        if (width <= 0 || height <= 0) return@LaunchedEffect
        val scale = minOf(1f, 1440f / maxOf(width, height))
        try {
            frame = captureSessionFrame(session, (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Retain the existing surface if a capture is unavailable. */ }
    }
    return frame
}
