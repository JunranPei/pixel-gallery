package com.pixel.gallery.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.pixel.gallery.MainActivity
import com.pixel.gallery.R
import com.pixel.gallery.ui.viewer.VideoPlaybackSession

/** Keeps explicitly selected background audio alive and exposes a system stop action. */
class VideoPlaybackService : Service() {
    private var mediaSession: MediaSession? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.video_settings_title), NotificationManager.IMPORTANCE_LOW))
        mediaSession = MediaSession(this, "Gallery video").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPause() { sessions.values.toList().forEach { it.pause() }; updateNotification() }
                override fun onStop() { onPause() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) sessions.values.toList().forEach { it.pause() }
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
        val stop = PendingIntent.getService(this, 0, Intent(this, VideoPlaybackService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = session.activity?.let { host ->
            PendingIntent.getActivity(this, host.taskId, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        mediaSession?.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(PlaybackState.STATE_PLAYING, session.player.currentPosition, session.speed).build())
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.video_background_audio))
            .setContentText(UriName(session.uri))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, getString(R.string.video_background_pause), stop).build())
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
        private const val STOP = "com.pixel.gallery.PAUSE_VIDEO"
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
    }
}
