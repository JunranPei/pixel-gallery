package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.VideoBackgroundAction
import com.pixel.gallery.ui.viewer.VideoPlaybackSession

/** Real viewer, system PiP and touch resize; no screenshot classification. */
internal class VideoPipDeviceChecks(private val instrumentation: Instrumentation, private val fixed: Boolean,
    private val mediaId: String = "1047") {
    fun exercise(): String {
        val context = instrumentation.targetContext
        check(context.packageName == "com.pixel.gallery.viewertrace")
        val results = mutableListOf<String>()
        for (entry in listOf("manual", "home")) {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://media/external/video/media/$mediaId"), "video/mp4")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)) as MainActivity
            lateinit var session: VideoPlaybackSession
            try {
                await { activity.videoSession?.let { it.firstFrame && it.outputReadiness.isBuffered } == true }
                var outputWidth = 0
                var outputHeight = 0
                instrumentation.runOnMainSync {
                    session = checkNotNull(activity.videoSession)
                    session.updateSettings(session.settings.copy(backgroundAction = VideoBackgroundAction.PIP))
                    session.player.play()
                    outputWidth = checkNotNull(session.surface).width
                    outputHeight = checkNotNull(session.surface).height
                }
                SystemClock.sleep(500)
                mark("BEGIN_${entry}_entry")
                when (entry) {
                    "manual" -> instrumentation.runOnMainSync { check(session.enterPip()) }
                    "home" -> shell("input keyevent KEYCODE_HOME")
                    else -> { shell("input keyevent KEYCODE_APP_SWITCH"); SystemClock.sleep(700); shell("input tap 20 1100") }
                }
                await { activity.isVideoPip }
                SystemClock.sleep(2500)
                checkSession(activity, session)
                mark("END_${entry}_entry")
                repeat(4) { index ->
                    var oldWidth = 0
                    var x = 0f
                    var y = 0f
                    instrumentation.runOnMainSync {
                        val bounds = activity.windowManager.currentWindowMetrics.bounds
                        oldWidth = bounds.width()
                        // Avoid the system PiP menu's central play/pause control.
                        x = bounds.left + bounds.width() * .25f
                        y = bounds.top + bounds.height() * .25f
                    }
                    mark("BEGIN_${entry}_resize_$index")
                    tap(x, y); SystemClock.sleep(65); tap(x, y)
                    await { activity.windowManager.currentWindowMetrics.bounds.width() != oldWidth }
                    SystemClock.sleep(2200)
                    checkSession(activity, session)
                    instrumentation.runOnMainSync {
                        if (fixed) {
                            check(activity.isVideoPipLayoutLocked)
                            check(session.surface?.width == outputWidth && session.surface?.height == outputHeight) {
                                "Video output resized: ${session.surface?.width}x${session.surface?.height}, expected ${outputWidth}x$outputHeight"
                            }
                        }
                    }
                    mark("END_${entry}_resize_$index")
                }
                for (grow in listOf(true, false)) {
                    var bounds = android.graphics.Rect()
                    instrumentation.runOnMainSync { bounds = activity.windowManager.currentWindowMetrics.bounds }
                    val label = "${entry}_pinch_${if (grow) "grow" else "shrink"}"
                    mark("BEGIN_$label")
                    pinch(bounds.exactCenterX(), bounds.exactCenterY(), bounds.width() * .22f, if (grow) 1.45f else .7f)
                    await { activity.windowManager.currentWindowMetrics.bounds.width() != bounds.width() }
                    SystemClock.sleep(2200)
                    checkSession(activity, session)
                    mark("END_$label")
                }
                instrumentation.runOnMainSync { session.player.pause() }
                await { !session.player.isPlaying }
                repeat(2) { index ->
                    var bounds = android.graphics.Rect()
                    instrumentation.runOnMainSync { bounds = activity.windowManager.currentWindowMetrics.bounds }
                    mark("BEGIN_${entry}_paused_resize_$index")
                    val x = bounds.left + bounds.width() * .25f
                    val y = bounds.top + bounds.height() * .25f
                    tap(x, y); SystemClock.sleep(65); tap(x, y)
                    await { activity.windowManager.currentWindowMetrics.bounds.width() != bounds.width() }
                    SystemClock.sleep(700)
                    checkSession(activity, session, playing = false)
                    mark("END_${entry}_paused_resize_$index")
                }
                instrumentation.runOnMainSync { session.player.play() }
                await { session.player.isPlaying }
                mark("BEGIN_${entry}_return")
                shell("am start -W --task ${activity.taskId} --windowingMode 1 --activity-reorder-to-front -n ${context.packageName}/com.pixel.gallery.MainActivity")
                await { !activity.isVideoPip && (!fixed || !activity.isVideoPipLayoutLocked) }
                SystemClock.sleep(2500)
                checkSession(activity, session)
                mark("END_${entry}_return")
                results += "$entry: entry, four double-tap resizes, two pinches, two paused resizes, same-session return passed (media=$mediaId)"
            } finally {
                instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
                SystemClock.sleep(500)
            }
        }
        return results.joinToString("\n")
    }

    private fun checkSession(activity: MainActivity, session: VideoPlaybackSession, playing: Boolean = true) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            failure = runCatching {
            check(activity.videoSession === session)
            check(session.player.playWhenReady == playing && session.player.isPlaying == playing)
            check(session.outputReadiness.isBuffered)
            val visible = android.graphics.Rect()
            check(session.surface?.getGlobalVisibleRect(visible) == true && !visible.isEmpty) {
                "Playing surface is clipped outside the window: $visible"
            }
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }

    private fun mark(value: String) { Log.i("VideoPipQA", "$value clockNanos=${System.nanoTime()}") }
    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(20)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(event, false))
            event.recycle()
        }
    }
    private fun pinch(x: Float, y: Float, radius: Float, scale: Float) {
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int, count: Int, distance: Float) {
            val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                this.x=x + if(i==0) -distance else distance; this.y=y; pressure=1f; size=1f
            } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            check(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN,1,radius)
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,radius)
        for(i in 1..20) { SystemClock.sleep(20); inject(MotionEvent.ACTION_MOVE,2,radius*(1+(scale-1)*i/20f)) }
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,radius*scale)
        inject(MotionEvent.ACTION_UP,1,radius*scale)
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var result = false
            instrumentation.runOnMainSync { result = predicate() }
            if (result) return
            SystemClock.sleep(25)
        }
        error("PiP state did not settle")
    }
}
