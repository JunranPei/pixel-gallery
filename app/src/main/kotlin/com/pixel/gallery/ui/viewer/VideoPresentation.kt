package com.pixel.gallery.ui.viewer

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.util.concurrent.atomic.AtomicBoolean

/** Surface readiness and cover changes invalidate this layer, not controls/zoom/layout parents. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun VideoPresentation(
    session: VideoPlaybackSession?,
    firstFrameModel: Any?,
    presented: Boolean,
    aspectRatio: Float,
    texture: Boolean,
    keepScreenOn: Boolean,
) {
    val showSurface = session?.firstFrame == true && presented && session.outputReadiness.isBuffered
    val cover = session?.retainedFrame
    val generation = session?.outputReadiness?.generation
    val mayClear = session?.retainedFrameMayClear == true
    val view = LocalView.current
    if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
        DisposableEffect(session, cover, generation, showSurface, mayClear, view) {
            val observer = view.viewTreeObserver
            var active = true
            val callback = if (session != null && cover != null && generation != null &&
                showSurface && mayClear && observer.isAlive) Runnable {
                // Frame-commit callbacks may run on RenderThread. Touch session state on main only.
                view.post {
                    if (active) {
                        ViewerLoadMetrics.event("VIDEO_HANDOFF_COMMITTED",
                            "session=${session.id} generation=$generation")
                        session.clearRetainedFrame(cover, generation)
                    }
                }
            } else null
            if (callback != null) {
                observer.registerFrameCommitCallback(callback)
                view.invalidate()
            }
            onDispose {
                active = false
                if (callback != null && observer.isAlive) observer.unregisterFrameCommitCallback(callback)
            }
        }
    } else {
        LaunchedEffect(session, cover, generation, showSurface, mayClear) {
            if (session != null && cover != null && generation != null && showSurface && mayClear) {
                withFrameNanos { }
                withFrameNanos { }
                session.clearRetainedFrame(cover, generation)
            }
        }
    }
    if (cover == null && firstFrameModel != null && !showSurface) {
        GlideImage(firstFrameModel, contentDescription = null, contentScale = ContentScale.Fit,
            requestBuilderTransform = { it.dontTransform().format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE).override(720) },
            modifier = Modifier.fillMaxSize())
    }
    session?.let { playback ->
        key(playback, playback.native) {
            VideoOutputSurface(playback, aspectRatio, texture, keepScreenOn,
                Modifier.fillMaxSize().graphicsLayer { alpha = if (showSurface) 1f else 0f })
        }
    }
    cover?.let { bitmap ->
        val drawn = remember(bitmap) { AtomicBoolean(false) }
        Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().drawWithContent {
                drawContent()
                if (drawn.compareAndSet(false, true)) {
                    ViewerLoadMetrics.event("VIDEO_COVER_DRAW",
                        "session=${session?.id} generation=$generation showSurface=$showSurface")
                }
            })
    }
}
