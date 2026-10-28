package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.VideoBackgroundAction
import com.pixel.gallery.ui.viewer.VideoPlaybackSession

/** Real viewer/task lifecycle. Fixed seek positions make codec comparisons reproducible. */
internal class VideoReturnDeviceChecks(private val instrumentation: Instrumentation) {
    fun exercise(): String {
        val context = instrumentation.targetContext
        check(context.packageName == "com.pixel.gallery.viewertrace")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://media/external/video/media/1047"), "video/mp4")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)) as MainActivity
        lateinit var session: VideoPlaybackSession
        val report = mutableListOf<String>()
        try {
            await { activity.videoSession?.let { it.firstFrame && it.outputReadiness.isBuffered } == true }
            instrumentation.runOnMainSync {
                session = checkNotNull(activity.videoSession)
                check(session.settings.backgroundAction == VideoBackgroundAction.AUDIO)
            }
            for (paused in listOf(false, true)) {
                for (position in listOf(2_000L, 7_000L, 14_000L, 28_000L)) {
                    instrumentation.runOnMainSync { session.player.pause(); session.player.seekTo(position) }
                    await { session.player.playbackState == Player.STATE_READY &&
                        kotlin.math.abs(session.player.currentPosition - position) < 60 }
                    SystemClock.sleep(600)
                    if (!paused) instrumentation.runOnMainSync { session.player.play() }
                    val label = "fixed-${if (paused) "paused" else "playing"}-${position}_0"
                    Log.i("VideoQA", "LISTENER_BEGIN_$label")
                    shell("input keyevent KEYCODE_HOME")
                    await { session.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) }
                    SystemClock.sleep(300)
                    val start = SystemClock.elapsedRealtime()
                    shell("am task focus ${activity.taskId}")
                    await { session.retainedFrameMayClear && session.outputReadiness.isBuffered &&
                        session.retainedFrame == null && !session.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) }
                    instrumentation.runOnMainSync {
                        check(activity.videoSession === session)
                        check(session.player.playWhenReady == !paused)
                        if (paused) check(kotlin.math.abs(session.player.currentPosition - position) < 60)
                    }
                    report += "$label handoffMs=${SystemClock.elapsedRealtime() - start}"
                    SystemClock.sleep(3000)
                    Log.i("VideoQA", "LISTENER_END_$label")
                }
            }
            return report.joinToString("\n")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            SystemClock.sleep(20)
        }
        error("Video return state timed out")
    }
}
