package com.pixel.gallery.ui.viewer

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Texture output exists only while the user requests content rotation. Same decoder/session. */
@Composable
internal fun RotatableVideoSurface(session: VideoPlaybackSession, aspectRatio: Float,
    keepScreenOn: Boolean, modifier: Modifier = Modifier) {
    val native = session.native
    BoxWithConstraints(modifier) {
        val width = minOf(maxWidth, maxHeight * aspectRatio)
        val height = width / aspectRatio
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = false
                    session.surface = null
                    session.texture = this
                        var output: Surface? = null
                        var reportedFrame = false
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                output = Surface(texture)
                                reportedFrame = false
                                session.outputReadiness.bind(output!!, texture = true)
                                if (native == null) session.player.setVideoSurface(output)
                                else {
                                    native.setSurface(output)
                                    native.refreshPausedFrame()
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                                if (!reportedFrame) output?.let {
                                    session.outputReadiness.rendered(it)
                                    reportedFrame = true
                                }
                            }
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                output?.let {
                                    if (native == null) session.player.clearVideoSurface(it)
                                    else if (session.outputReadiness.owns(it)) native.setSurface(null)
                                }
                                output?.release()
                                output = null
                                return true
                            }
                        }
                }
            },
            update = { it.keepScreenOn = keepScreenOn },
            onRelease = { view ->
                view.surfaceTexture?.let { view.surfaceTextureListener?.onSurfaceTextureDestroyed(it) }
                if (session.texture === view) session.texture = null
            },
            modifier = Modifier.align(Alignment.Center).size(width, height),
        )
    }
}
