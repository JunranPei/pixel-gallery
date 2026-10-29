package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.ui.viewer.*
import java.io.File

/** Isolated visual host with the production session, output host and cover handoff. */
internal class VideoHandoffDeviceChecks(private val instrumentation: Instrumentation) {
    fun exercise(): String {
        val context = instrumentation.targetContext
        val clip = File(context.cacheDir, "handoff-test.mp4")
        instrumentation.context.assets.open("media-type-clip.mp4").use { input -> clip.outputStream().use { output -> input.copyTo(output) } }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var session: VideoPlaybackSession
        var transition: VideoSurfaceTransition? = null
        val texture = mutableStateOf(false)
        val report = mutableListOf<String>()
        try {
            instrumentation.runOnMainSync {
                session = VideoPlaybackSession(activity, context, clip.toURI().toString(), "handoff-test",
                    VideoSettingsRepository(context), VideoSettings(muted = true, endAction = VideoEndAction.LOOP), false, {})
                session.updateVisibility(true, true, true, false)
                activity.setContent {
                    val state = rememberVideoSurfaceTransition(session, texture.value)
                    transition = state
                    Box(Modifier.fillMaxSize()) {
                        VideoOutputSurface(session, 4f / 3f, state.texture, true, Modifier.fillMaxSize())
                        state.cover?.let { bitmap ->
                            Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().drawWithContent { drawContent(); state.drawnCover = bitmap })
                        }
                    }
                }
            }
            await(10_000) { session.outputReadiness.readyAfter(0, false) }
            SystemClock.sleep(500)
            val originalSurface = session.surface
            for (paused in listOf(false, true)) {
                instrumentation.runOnMainSync { if (paused) session.player.pause() else session.player.play() }
                repeat(4) { round ->
                    for (target in listOf(true, false)) {
                        var generation = 0
                        val started = SystemClock.elapsedRealtime()
                        instrumentation.runOnMainSync { generation = session.outputReadiness.generation; texture.value = target }
                        await(5000) { transition?.cover == null && transition?.texture == target && session.outputReadiness.readyAfter(generation, target) }
                        val elapsed = SystemClock.elapsedRealtime() - started
                        check(session.surface === originalSurface) { "Ordinary surface was recreated" }
                        report += "paused=$paused round=$round texture=$target durationMs=$elapsed"
                        check(elapsed < 1500) { "Handoff reached the previous 1.5s stall: $elapsed ms" }
                        SystemClock.sleep(120)
                    }
                }
            }
            return report.joinToString("\n").also { File(context.filesDir, "video-handoff-device.txt").writeText(it) }
        } finally {
            instrumentation.runOnMainSync { activity.setContent { }; session.release(); activity.finish() }
        }
    }

    private fun await(timeout: Long, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            SystemClock.sleep(16)
        }
        error("Video handoff did not become ready")
    }
}
