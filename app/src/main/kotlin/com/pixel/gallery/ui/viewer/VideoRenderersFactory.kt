package com.pixel.gallery.ui.viewer

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

internal class VideoRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init { setEnableDecoderFallback(true) }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        // Audio shares the playback loop. Catch-up decoding must yield between batches.
        out.filterIsInstance<MediaCodecRenderer>().forEach { it.setRenderTimeLimitMs(8) }
    }
}
