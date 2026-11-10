package com.pixel.gallery.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.pixel.gallery.R
import com.pixel.gallery.ui.viewer.VideoPlaybackSession

/** Keeps explicitly selected background audio alive with matching notification controls. */
class VideoPlaybackService : Service() {
    private var mediaSession: MediaSession? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.video_settings_title), NotificationManager.IMPORTANCE_LOW))
        mediaSession = MediaSession(this, "Gallery video").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { sessions.values.lastOrNull()?.play(); updateNotification() }
                override fun onPause() { sessions.values.lastOrNull()?.pause(); updateNotification() }
                override fun onStop() { sessions.values.lastOrNull()?.stopBackgroundPlayback(); updateNotification() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = intent?.getStringExtra("session")?.let(sessions::get)
        when (intent?.action) {
            TOGGLE -> session?.let { if (it.playbackRequested) it.pause() else it.play() }
            STOP -> session?.stopBackgroundPlayback()
        }
        updateNotification()
        return START_NOT_STICKY
    }

    private fun updateNotification() {
        val session = sessions.values.lastOrNull()
        if (session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        fun control(action: String) = PendingIntent.getService(this, session.id.hashCode(),
            Intent(this, VideoPlaybackService::class.java).setAction(action).putExtra("session", session.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playing = session.playbackRequested
        val open = session.activity?.let { host ->
            PendingIntent.getActivity(this, host.taskId, Intent(this, VideoPlaybackReturnActivity::class.java)
                .putExtra("taskId", host.taskId).putExtra("uri", session.uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        mediaSession?.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                session.native?.currentPosition?.toLong() ?: session.player.currentPosition,
                if (playing) session.speed else 0f).build())
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.video_background_audio))
            .setContentText(UriName(session.uri))
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
                getString(if (playing) R.string.video_background_pause else R.string.video_resume), control(TOGGLE)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.video_stop), control(STOP)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        sessions.values.toList().filter { it.activity?.isFinishing == true }.forEach { it.pause() }
        updateNotification()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "video_playback"
        private const val ID = 702
        private const val STOP = "com.pixel.gallery.STOP_VIDEO"
        private const val TOGGLE = "com.pixel.gallery.TOGGLE_VIDEO"
        private val sessions = linkedMapOf<String, VideoPlaybackSession>()
        private fun UriName(uri: String) = android.net.Uri.decode(uri.substringAfterLast('/'))
        fun retain(context: Context, session: VideoPlaybackSession): Boolean {
            sessions[session.id] = session
            return runCatching { context.startForegroundService(Intent(context, VideoPlaybackService::class.java)); true }
                .getOrElse { sessions.remove(session.id); false }
        }
        fun drop(context: Context, id: String) {
            if (sessions.remove(id) != null) {
                if (sessions.isEmpty()) context.stopService(Intent(context, VideoPlaybackService::class.java))
                else runCatching { context.startService(Intent(context, VideoPlaybackService::class.java)) }
            }
        }
        fun refresh(context: Context, id: String) {
            if (sessions.containsKey(id)) runCatching {
                context.startService(Intent(context, VideoPlaybackService::class.java))
            }
        }
    }
}
