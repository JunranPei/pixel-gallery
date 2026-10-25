package com.pixel.gallery.ui.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.services.VideoPlaybackService
import java.util.UUID

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
    var native by mutableStateOf<NativeVideoPlayback?>(null)
        private set
    var surface: SurfaceView? = null
    var texture: android.view.TextureView? = null
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
    private var wantsPlay = motionPhoto || initialSettings.autoplay
    private var applyingPolicy = false
    private var audioRegistered = false
    private var initialPosition = if (!motionPhoto && initialSettings.rememberPosition) repository.position(sourceKey) else 0L

    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_MOTION_PHOTO_METADATA)))
        .build()

    init {
        speed = speed.takeIf { it in VideoPlaybackSpeeds } ?: 1f
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true; updatePipParams() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!applyingPolicy && native == null) {
                    wantsPlay = playWhenReady
                    checkpoint()
                    refreshAudioService()
                    updatePipParams()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
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
            override fun onVideoSizeChanged(videoSize: VideoSize) { updatePipParams() }
            override fun onPlayerError(error: PlaybackException) {
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

    fun updateSettings(value: VideoSettings) {
        settings = value
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
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
        applyingPolicy = false
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
        pipEntering = true
        val entered = runCatching { host.enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
        if (!entered) pipEntering = false
        return entered
    }

    fun onUserLeaveHint() {
        if (active && routeVisible) onPresentationCheckpoint?.invoke()
        if (settings.backgroundAction == VideoBackgroundAction.PIP && wantsPlay && active && routeVisible && firstFrame) {
            pipEntering = true
            if (Build.VERSION.SDK_INT < 31) enterPip()
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val width = native?.width ?: player.videoSize.width
        val height = native?.height ?: player.videoSize.height
        val rawRatio = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
        val ratio = (if (contentRotationDegrees % 180 != 0) 1f / rawRatio else rawRatio).coerceIn(1f / 2.39f, 2.39f)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 10000).toInt(), 10000))
        (texture ?: surface)?.let { view -> Rect().also { if (view.getGlobalVisibleRect(it)) builder.setSourceRectHint(it) } }
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(
            !motionPhoto && active && routeVisible && wantsPlay && firstFrame && settings.backgroundAction == VideoBackgroundAction.PIP)
        return builder.build()
    }

    fun updatePipParams() {
        if (activity?.videoSession === this && pipSupported) runCatching { activity?.setPictureInPictureParams(pipParams()) }
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
        checkpoint()
        onPresentationCheckpoint?.invoke()
        onPresentationCheckpoint = null
        released = true
        VideoPlaybackService.drop(appContext, id)
        native?.release()
        player.release()
        surface = null
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
