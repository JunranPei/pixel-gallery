package com.pixel.gallery.ui.viewer

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun VideoOutputSurface(session: VideoPlaybackSession, aspectRatio: Float,
    texture: Boolean, keepScreenOn: Boolean, modifier: Modifier = Modifier) {
    val surfaceReady = session.outputReadiness.readyAfter(0, texture = false)
    BoxWithConstraints(modifier) {
        val width = minOf(maxWidth, maxHeight * aspectRatio)
        AndroidView(
            factory = { VideoSurfaceHost(it, session) },
            update = { it.update(texture, surfaceReady, keepScreenOn) },
            onRelease = { it.dispose() },
            modifier = Modifier.align(Alignment.Center).size(width, width / aspectRatio),
        )
    }
}

/** Keep the ordinary surface attached; hand the decoder directly to a valid destination. */
internal class VideoSurfaceHost(context: Context, private val session: VideoPlaybackSession) : FrameLayout(context) {
    private val native = session.native
    private var wantTexture = false
    private var disposed = false
    private var bound: Surface? = null
    private var textureView: TextureView? = null
    private var textureOutput: Surface? = null
    private val ordinary = SurfaceView(context).apply {
        if (Build.VERSION.SDK_INT >= 34) setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { selectOutput() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { clearIfBound(holder.surface) }
        })
    }

    init { addView(ordinary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    fun update(texture: Boolean, surfaceReady: Boolean, keepAwake: Boolean) {
        keepScreenOn = keepAwake
        wantTexture = texture
        if (texture && textureView == null) createTexture()
        selectOutput()
        if (!texture && surfaceReady && session.outputReadiness.owns(ordinary.holder.surface)) releaseTexture()
    }

    private fun createTexture() {
        val view = TextureView(context)
        var output: Surface? = null
        textureView = view
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (disposed || textureView !== view) return
                output = Surface(texture)
                textureOutput = output
                selectOutput()
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                output?.let { session.outputReadiness.rendered(it) }
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                output?.let { clearIfBound(it); it.release() }
                if (textureOutput === output) textureOutput = null
                output = null
                return true
            }
        }
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun selectOutput() {
        if (disposed) return
        val output = (if (wantTexture) textureOutput else ordinary.holder.surface)
            ?.takeIf { it.isValid } ?: return
        if (bound === output) return
        session.surface = ordinary
        session.texture = if (wantTexture) textureView else null
        session.outputReadiness.bind(output, wantTexture)
        if (native == null) session.player.setVideoSurface(output) else native.setSurface(output)
        bound = output
        if (native != null) {
            if (wantTexture) native.refreshPausedFrame() else session.nativeSurfaceBound(ordinary)
        }
    }

    private fun clearIfBound(output: Surface) {
        if (bound !== output) return
        if (native == null) session.player.clearVideoSurface(output)
        else if (session.outputReadiness.owns(output)) native.setSurface(null)
        session.outputReadiness.unbind(output)
        bound = null
    }

    private fun releaseTexture() {
        val view = textureView ?: return
        textureView = null
        if (session.texture === view) session.texture = null
        view.surfaceTexture?.let { view.surfaceTextureListener?.onSurfaceTextureDestroyed(it) }
        removeView(view)
    }

    fun dispose() {
        disposed = true
        bound?.let(::clearIfBound)
        releaseTexture()
        if (session.surface === ordinary) session.surface = null
    }
}
