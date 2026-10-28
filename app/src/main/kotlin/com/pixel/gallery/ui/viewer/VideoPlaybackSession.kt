package com.pixel.gallery.ui.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.services.VideoPlaybackService
import java.util.UUID
import kotlinx.coroutines.*

/** View-only playback state. Never changes the media file or the image renderer. */
class VideoPlaybackSession(
    val activity: MainActivity?,
    context: Context,
    val uri: String,
    private val sourceKey: String,
    private val repository: VideoSettingsRepository,
    initialSettings: VideoSettings,
    val motionPhoto: Boolean,
    val onEnded: () -> Unit,
) {
    val id = UUID.randomUUID().toString()
    private val appContext = context.applicationContext
    var settings = initialSettings
        private set
    var muted by mutableStateOf(initialSettings.muted)
        private set
    var speed by mutableFloatStateOf(if (initialSettings.rememberSpeed) initialSettings.lastSpeed else 1f)
        private set
    var firstFrame by mutableStateOf(false)
    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
        private set
    var native by mutableStateOf<NativeVideoPlayback?>(null)
        private set
    var surface: SurfaceView? = null
    var texture: android.view.TextureView? = null
    internal val outputReadiness = VideoOutputReadiness()
    private val outputScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var retainedFrame by mutableStateOf<Bitmap?>(null)
        private set
    private var retainedFrameJob: Job? = null
    var retainedFrameMayClear by mutableStateOf(false)
        private set
    internal fun onActivityResumed() { retainedFrameMayClear = true }
    var contentRotationDegrees: Int = 0
    var onPresentationCheckpoint: (() -> Unit)? = null
    var captureBusy by mutableStateOf(false)
    var error by mutableStateOf(false)
    private var released = false
    private var active = false
    private var routeVisible = false
    private var activityVisible = true
    private var inPip = false
    private var pipEntering = false
    private var pipSourceRect: Rect? = null
    private var lastPipParams: PictureInPictureParams? = null

    internal fun preparePipTransition() {
        pipEntering = true
        activity?.prepareVideoPip(pipSourceRect)
    }
    private var wantsPlay = motionPhoto || initialSettings.autoplay
    private var applyingPolicy = false
    private var audioRegistered = false
    private var initialPosition = if (!motionPhoto && initialSettings.rememberPosition) repository.position(sourceKey) else 0L

    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true))
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_MOTION_PHOTO_METADATA)))
        .build()

    init {
        ViewerLoadMetrics.event(
            "VIDEO_SESSION_CREATE",
            "session=$id uri=$uri sourceKey=$sourceKey motionPhoto=$motionPhoto " +
                "autoplay=${initialSettings.autoplay} background=${initialSettings.backgroundAction} " +
                "rememberPosition=${initialSettings.rememberPosition} " +
                "pid=${android.os.Process.myPid()} media3=${MediaLibraryInfo.VERSION}",
        )
        speed = speed.takeIf { it in VideoPlaybackSpeeds } ?: 1f
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        if (ViewerLoadMetrics.isEnabled) {
            var sequence = 0L
            var previousCallbackNs = 0L
            player.setVideoFrameMetadataListener { ptsUs, releaseNs, format, _ ->
                val nowNs = System.nanoTime()
                ViewerLoadMetrics.videoFrame(
                    "session=$id seq=${++sequence} callbackNanos=$nowNs ptsUs=$ptsUs " +
                        "releaseNanos=$releaseNs callbackGapNs=${if (previousCallbackNs == 0L) -1 else nowNs - previousCallbackNs} " +
                        "generation=${outputReadiness.generation} fps=${format.frameRate}",
                )
                previousCallbackNs = nowNs
            }
        }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                ViewerLoadMetrics.event("VIDEO_DECODER_RELEASED", "session=$id decoder=$decoderName")
            }
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int,
                bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                ViewerLoadMetrics.event("VIDEO_AUDIO_UNDERRUN", "session=$id bufferMs=$bufferSizeMs elapsedMs=$elapsedSinceLastFeedMs")
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                ViewerLoadMetrics.event("VIDEO_DECODER_INIT",
                    "session=$id decoder=$decoderName durationMs=$initializationDurationMs initializedMs=$initializedTimestampMs")
            }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                ViewerLoadMetrics.event("VIDEO_DROPPED_FRAMES", "session=$id count=$droppedFrames elapsedMs=$elapsedMs")
            }
            override fun onVideoFrameProcessingOffset(eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long, frameCount: Int) {
                ViewerLoadMetrics.event("VIDEO_PROCESSING_OFFSET",
                    "session=$id sumUs=$totalProcessingOffsetUs count=$frameCount")
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                // TextureView confirms latching in onSurfaceTextureUpdated instead.
                if (!outputReadiness.texture && outputReadiness.owns(output)) {
                    outputReadiness.rendered(output)
                    surface?.takeIf { it.holder.surface === output }?.let { confirmSurfaceBuffer(it) }
                }
            }
        })
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
                ViewerLoadMetrics.event(
                    "VIDEO_FIRST_FRAME",
                    videoDiagnostics(),
                )
                updatePipParams()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                ViewerLoadMetrics.event(
                    "VIDEO_PLAY_WHEN_READY",
                    "requested=$playWhenReady reason=$reason ${videoDiagnostics()}",
                )
                if (!applyingPolicy && native == null) {
                    wantsPlay = playWhenReady
                    checkpoint()
                    refreshAudioService()
                    updatePipParams()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                ViewerLoadMetrics.event(
                    "VIDEO_PLAYBACK_STATE",
                    "state=${playbackStateName(playbackState)} code=$playbackState ${videoDiagnostics()}",
                )
                if (native != null) return
                if (playbackState == Player.STATE_READY && initialPosition > 0) {
                    if (player.duration > 0 && initialPosition >= player.duration - 1000) player.seekTo(0)
                    initialPosition = 0
                }
                if (playbackState == Player.STATE_ENDED) completed()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) checkpoint()
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Track selection is intentionally changed to audio-only while the
                // activity is in the background. Media3 reports UNKNOWN at that
                // point; keeping the last real dimensions prevents the returning
                // surface from making a one-frame 16:9 layout jump.
                if (videoSize.width > 0 && videoSize.height > 0) {
                    this@VideoPlaybackSession.videoSize = videoSize
                }
                ViewerLoadMetrics.event(
                    "VIDEO_SIZE",
                    "size=${videoSize.width}x${videoSize.height} ratio=${videoSize.pixelWidthHeightRatio} ${videoDiagnostics()}",
                )
                updatePipParams()
            }
            override fun onPlayerError(error: PlaybackException) {
                ViewerLoadMetrics.event(
                    "VIDEO_PLAYER_ERROR",
                    "error=${error.errorCodeName} message=${error.message ?: "none"} ${videoDiagnostics()}",
                )
                if (released || native != null) return
                val position = player.currentPosition
                applyingPolicy = true
                player.stop()
                applyingPolicy = false
                firstFrame = false
                native = NativeVideoPlayback(appContext, Uri.parse(uri), position,
                    onReady = { firstFrame = true; applyOptions(); applyPolicy() },
                    onComplete = ::completed,
                    onInterrupted = ::pause,
                    onFailure = { this@VideoPlaybackSession.error = true; wantsPlay = false; refreshAudioService() })
            }
        })
        applyOptions()
        player.setMediaItem(MediaItem.fromUri(uri), initialPosition)
        player.prepare()
    }

    internal fun nativeSurfaceBound(view: SurfaceView) {
        val output = view.holder.surface
        ViewerLoadMetrics.event(
            "VIDEO_NATIVE_SURFACE_BOUND",
            "valid=${output.isValid} generation=${outputReadiness.generation} ${videoDiagnostics()}",
        )
        outputReadiness.bind(output, texture = false)
        native?.refreshPausedFrame()
        confirmSurfaceBuffer(view, nativeOutput = true)
    }

    /**
     * Capture one small cover before Android tears down the SurfaceView on background.
     * This is a transition cover only; it is released after the replacement output
     * has presented a frame and is never kept as a media cache.
     */
    internal fun captureRetainedFrame(reason: String) {
        val autoPip = settings.backgroundAction == VideoBackgroundAction.PIP &&
            wantsPlay && active && routeVisible && firstFrame && pipSupported
        if (inPip || pipEntering || activity?.isVideoPip == true || autoPip) return
        retainedFrameMayClear = false
        if (released || retainedFrame != null || retainedFrameJob?.isActive == true) return
        if (!outputReadiness.isBuffered) return
        val view = surface ?: return
        val output = view.holder.surface
        if (!output.isValid && texture == null) return
        val sourceWidth = native?.width ?: videoSize.width
        val sourceHeight = native?.height ?: videoSize.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val scale = minOf(1f, 720f / maxOf(sourceWidth, sourceHeight).toFloat())
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        retainedFrameJob = outputScope.launch {
            val frame = try {
                withTimeoutOrNull(180) { captureSessionFrame(this@VideoPlaybackSession, width, height) }
            } catch (_: CancellationException) {
                null
            } catch (_: Exception) {
                null
            }
            // A successful outgoing copy remains valid after the surface is unbound.
            if (frame != null && !released) {
                retainedFrame = frame
                ViewerLoadMetrics.event(
                    "VIDEO_RETAINED_FRAME",
                    "reason=$reason size=${frame.width}x${frame.height} ${videoDiagnostics()}",
                )
            } else {
                frame?.recycle()
                ViewerLoadMetrics.event(
                    "VIDEO_RETAINED_FRAME_MISS",
                    "reason=$reason ${videoDiagnostics()}",
                )
            }
            retainedFrameJob = null
        }
    }

    internal fun clearRetainedFrame() {
        if (retainedFrame != null) ViewerLoadMetrics.event("VIDEO_COVER_CLEAR", videoDiagnostics())
        // Compose/RenderThread may still reference the published bitmap.
        retainedFrame = null
    }

    internal fun clearRetainedFrame(expected: Bitmap, generation: Int) {
        if (retainedFrame === expected && retainedFrameMayClear && outputReadiness.canReveal(generation)) {
            clearRetainedFrame()
        }
    }

    private fun confirmSurfaceBuffer(view: SurfaceView, nativeOutput: Boolean = false) {
        val output = view.holder.surface
        val generation = outputReadiness.generation
        outputScope.launch {
            // Check the new surface only until a buffer exists, never during steady playback.
            repeat(12) {
                if (!outputReadiness.owns(output, generation) || released) return@launch
                val copied = try {
                    withTimeoutOrNull(100) { captureVideoFrame(view, 1, 1) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (copied != null) {
                    copied.recycle()
                    outputReadiness.bufferAvailable(output, generation)
                    ViewerLoadMetrics.event("VIDEO_BUFFER_AVAILABLE", "generation=$generation ${videoDiagnostics()}")
                    if (nativeOutput && outputReadiness.owns(output, generation)) outputReadiness.rendered(output)
                    return@launch
                }
                delay(32)
            }
        }
    }

    fun updateSettings(value: VideoSettings) {
        if (settings == value) return
        settings = value
        ViewerLoadMetrics.event(
            "VIDEO_SETTINGS_UPDATE",
            "autoplay=${value.autoplay} background=${value.backgroundAction} muted=${value.muted} " +
                "speed=${value.lastSpeed} ${videoDiagnostics()}",
        )
        applyOptions()
        applyPolicy()
    }

    private fun applyOptions() {
        player.volume = if (muted) 0f else 1f
        player.setPlaybackSpeed(speed)
        player.repeatMode = if (motionPhoto || settings.endAction == VideoEndAction.LOOP) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        native?.configure(muted, speed, motionPhoto || settings.endAction == VideoEndAction.LOOP)
    }

    fun toggleMute() { muted = !muted; applyOptions() }
    fun setSpeed(value: Float): Boolean {
        if (value !in VideoPlaybackSpeeds) return false
        if (native?.setSpeed(value) == false) return false
        speed = value
        player.setPlaybackSpeed(value)
        if (settings.rememberSpeed) repository.update { it.copy(lastSpeed = value) }
        return true
    }

    fun updateVisibility(isActive: Boolean, isRouteVisible: Boolean, isActivityVisible: Boolean, isInPip: Boolean) {
        if (active == isActive && routeVisible == isRouteVisible &&
            activityVisible == isActivityVisible && inPip == isInPip) return
        ViewerLoadMetrics.event(
            "VIDEO_VISIBILITY",
            "active=$isActive routeVisible=$isRouteVisible activityVisible=$isActivityVisible " +
                "inPip=$isInPip ${videoDiagnostics()}",
        )
        if (active && (!isActive || !isRouteVisible || !isActivityVisible)) checkpoint()
        active = isActive
        routeVisible = isRouteVisible
        activityVisible = isActivityVisible
        if (inPip && !isInPip && !isActivityVisible) wantsPlay = false
        inPip = isInPip
        if (inPip || activityVisible) pipEntering = false
        applyPolicy()
        updatePipParams()
    }

    private fun applyPolicy() {
        if (released) return
        val allowed = active && routeVisible && (activityVisible || inPip || pipEntering ||
            (!motionPhoto && settings.backgroundAction == VideoBackgroundAction.AUDIO))
        applyingPolicy = true
        player.playWhenReady = allowed && wantsPlay && native == null
        native?.setPlaying(allowed && wantsPlay)
        // Audio-only playback must not keep decoding an invisible video track.
        val audioOnly = !activityVisible && !inPip && !pipEntering && settings.backgroundAction == VideoBackgroundAction.AUDIO
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) != audioOnly) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
        }
        applyingPolicy = false
        ViewerLoadMetrics.event(
            "VIDEO_POLICY",
            "allowed=$allowed audioOnly=$audioOnly wantsPlay=$wantsPlay applying=$applyingPolicy " +
                "${videoDiagnostics()}",
        )
        refreshAudioService()
    }

    fun togglePlay() {
        wantsPlay = !(native?.isPlaying ?: player.isPlaying)
        if (wantsPlay && player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        applyPolicy()
        updatePipParams()
    }

    fun pause() { wantsPlay = false; checkpoint(); applyPolicy(); updatePipParams() }
    private fun completed() {
        if (!motionPhoto) repository.savePosition(sourceKey, 0)
        wantsPlay = false
        refreshAudioService()
        updatePipParams()
        if (!motionPhoto && active && routeVisible && settings.endAction == VideoEndAction.NEXT) onEnded()
    }

    fun checkpoint() {
        if (released || motionPhoto || !settings.rememberPosition) return
        val position = native?.currentPosition?.toLong() ?: player.currentPosition
        val duration = native?.duration?.toLong() ?: player.duration
        repository.savePosition(sourceKey, if (duration > 0 && position >= duration - 1000) 0 else position)
    }

    val pipSupported: Boolean get() = activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
    fun enterPip(): Boolean {
        val host = activity ?: return false
        if (!pipSupported || motionPhoto || !active || !routeVisible || !firstFrame) return false
        checkpoint()
        onPresentationCheckpoint?.invoke()
        updatePipParams()
        preparePipTransition()
        val entered = runCatching { host.enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
        if (!entered) { pipEntering = false; host.cancelVideoPip() }
        return entered
    }

    fun onUserLeaveHint() {
        if (active && routeVisible) onPresentationCheckpoint?.invoke()
        if (settings.backgroundAction == VideoBackgroundAction.PIP && wantsPlay && active && routeVisible && firstFrame) {
            updatePipParams()
            activity?.armVideoPip(pipSourceRect)
            pipEntering = true
            if (Build.VERSION.SDK_INT < 31) enterPip()
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val width = native?.width ?: videoSize.width
        val height = native?.height ?: videoSize.height
        val pixelRatio = if (native == null) videoSize.pixelWidthHeightRatio else 1f
        val rawRatio = if (width > 0 && height > 0) width.toFloat() * pixelRatio / height else 16f / 9f
        val ratio = (if (contentRotationDegrees % 180 != 0) 1f / rawRatio else rawRatio).coerceIn(1f / 2.39f, 2.39f)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 10000).toInt(), 10000))
        if (activity?.isVideoPipLayoutLocked != true && activity?.isVideoPip != true) {
            (texture ?: surface)?.let { view ->
                val rect = Rect()
                if (view.isAttachedToWindow && view.getGlobalVisibleRect(rect) && !rect.isEmpty) pipSourceRect = rect
            }
        }
        pipSourceRect?.let(builder::setSourceRectHint)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setSeamlessResizeEnabled(true)
            builder.setAutoEnterEnabled(!motionPhoto && active && routeVisible && wantsPlay && firstFrame &&
                pipSourceRect != null && settings.backgroundAction == VideoBackgroundAction.PIP)
        }
        return builder.build()
    }

    fun updatePipParams() {
        if (activity?.videoSession !== this || !pipSupported || released) return
        val params = pipParams()
        if (params == lastPipParams) return
        runCatching {
            activity?.setPictureInPictureParams(params)
            lastPipParams = params
            ViewerLoadMetrics.event("VIDEO_PIP_PARAMS", "session=$id source=$pipSourceRect " +
                "ratio=${params.aspectRatio} locked=${activity?.isVideoPipLayoutLocked}")
        }
    }

    private fun refreshAudioService() {
        val needed = !motionPhoto && active && routeVisible && wantsPlay && settings.backgroundAction == VideoBackgroundAction.AUDIO
        if (needed && !audioRegistered) {
            audioRegistered = VideoPlaybackService.retain(appContext, this)
            if (!audioRegistered && !activityVisible) pause()
        } else if (!needed && audioRegistered) {
            audioRegistered = false
            VideoPlaybackService.drop(appContext, id)
        }
    }

    fun release() {
        ViewerLoadMetrics.event(
            "VIDEO_SESSION_RELEASE",
            videoDiagnostics() + " surfaceGeneration=${outputReadiness.generation}",
        )
        checkpoint()
        onPresentationCheckpoint?.invoke()
        onPresentationCheckpoint = null
        released = true
        retainedFrameJob?.cancel()
        retainedFrameJob = null
        clearRetainedFrame()
        outputScope.cancel()
        VideoPlaybackService.drop(appContext, id)
        native?.release()
        player.release()
        surface = null
    }

    internal fun diagnosticsSnapshot(): String = videoDiagnostics()

    private fun videoDiagnostics(): String {
        val output = surface?.holder?.surface
        val textureView = texture
        val counters = if (ViewerLoadMetrics.isEnabled) player.videoDecoderCounters?.let {
            " videoRendered=${it.renderedOutputBufferCount} videoDropped=${it.droppedBufferCount}" +
                " videoSkipped=${it.skippedOutputBufferCount} maxConsecutiveDropped=${it.maxConsecutiveDroppedBufferCount}"
        } ?: "" else ""
        return "session=$id firstFrame=$firstFrame native=${native != null} " +
            "playback=${playbackStateName(player.playbackState)} playWhenReady=${player.playWhenReady} " +
            "isPlaying=${player.isPlaying} position=${player.currentPosition} duration=${player.duration} " +
            "size=${videoSize.width}x${videoSize.height} " +
            "surfaceValid=${output?.isValid ?: false} textureAvailable=${textureView?.isAvailable ?: false} " +
            "outputGeneration=${outputReadiness.generation} outputReady=${outputReadiness.isReady} " +
            "outputBuffered=${outputReadiness.isBuffered} active=$active routeVisible=$routeVisible " +
            "activityVisible=$activityVisible inPip=$inPip released=$released$counters"
    }

    private fun playbackStateName(value: Int): String = when (value) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> value.toString()
    }
}

/** The existing platform decoder fallback, with playback independent of Surface lifetime. */
class NativeVideoPlayback(context: Context, uri: Uri, position: Long,
    onReady: () -> Unit, onComplete: () -> Unit, private val onInterrupted: () -> Unit, onFailure: () -> Unit) {
    private val media = MediaPlayer()
    private var ready = false
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(android.media.AudioManager::class.java)
    private var hasAudioFocus = false
    private val audioFocus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
        .setOnAudioFocusChangeListener { change ->
            if (change < 0) { hasAudioFocus = false; onInterrupted() }
        }.build()
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) { onInterrupted() }
    }
    val isPlaying get() = ready && runCatching { media.isPlaying }.getOrDefault(false)
    val currentPosition get() = if (ready) runCatching { media.currentPosition }.getOrDefault(0) else 0
    val duration get() = if (ready) runCatching { media.duration }.getOrDefault(0) else 0
    val width get() = if (ready) media.videoWidth else 0
    val height get() = if (ready) media.videoHeight else 0
    init {
        androidx.core.content.ContextCompat.registerReceiver(appContext, noisyReceiver,
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        media.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK)
        media.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
        media.setOnPreparedListener {
            ready = true
            val resume = if (position >= it.duration - 1000) 0 else position.coerceAtLeast(0).toInt()
            it.seekTo(resume)
            onReady()
        }
        media.setOnCompletionListener { onComplete() }
        media.setOnErrorListener { _, _, _ -> onFailure(); true }
        runCatching { media.setDataSource(context, uri); media.prepareAsync() }.onFailure { onFailure() }
    }
    fun setSurface(surface: Surface?) { runCatching { media.setSurface(surface) } }
    fun refreshPausedFrame() {
        if (ready && !isPlaying) runCatching { media.seekTo(currentPosition.toLong(), MediaPlayer.SEEK_CLOSEST) }
    }
    fun configure(muted: Boolean, speed: Float, loop: Boolean) {
        if (!ready) return
        media.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        media.isLooping = loop
        setSpeed(speed)
    }
    fun setSpeed(speed: Float): Boolean {
        if (!ready) return true
        val playing = isPlaying
        return runCatching {
            media.playbackParams = media.playbackParams.setSpeed(speed)
            if (!playing && media.isPlaying) media.pause()
        }.isSuccess
    }
    fun setPlaying(play: Boolean) { if (ready) { if (play) start() else pause() } }
    fun start() {
        if (!ready) return
        if (!hasAudioFocus) hasAudioFocus = audioManager.requestAudioFocus(audioFocus) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasAudioFocus) runCatching { media.start() } else onInterrupted()
    }
    fun pause() {
        if (ready) runCatching { media.pause() }
        if (hasAudioFocus) { hasAudioFocus = false; audioManager.abandonAudioFocusRequest(audioFocus) }
    }
    fun seekTo(position: Int) { if (ready) media.seekTo(position) }
    fun release() { pause(); ready = false; runCatching { appContext.unregisterReceiver(noisyReceiver) }; media.release() }
}
