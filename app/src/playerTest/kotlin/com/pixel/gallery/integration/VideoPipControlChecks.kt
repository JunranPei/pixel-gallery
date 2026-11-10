package com.pixel.gallery.integration

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.C
import com.pixel.gallery.MainActivity
import com.pixel.gallery.R
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.ui.viewer.VideoPlaybackSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Clicks actual SystemUI RemoteActions and checks the production player/session. */
internal class VideoPipControlChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val repository = VideoSettingsRepository(context)
    private val output = File(context.getExternalFilesDir(null), "pip-controls-20261110").apply { mkdirs() }
    private lateinit var activity: MainActivity
    private lateinit var session: VideoPlaybackSession
    private val report = mutableListOf<String>()
    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return value!!.getOrThrow()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        dump("timeout-$label")
        error("Timed out: $label")
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            add(node)
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        instrumentation.uiAutomation.windows.forEach { visit(it.root) }
    }
    private fun dump(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(output, "$name.txt").writeText(nodes().joinToString("\n") {
            "${it.packageName} id=${it.viewIdResourceName} text=${it.text} desc=${it.contentDescription} bounds=${Rect().also(it::getBoundsInScreen)}"
        })
    }
    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(30)
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(instrumentation.uiAutomation.injectInputEvent(it, true)); it.recycle()
            }
        }
    }
    private fun matches(node: AccessibilityNodeInfo, label: String) = node.isVisibleToUser &&
        (node.text?.toString() == label || node.contentDescription?.toString() == label)
    private fun click(label: String) {
        if (main { activity.isInPictureInPictureMode }) menu()
        await("button-$label") { nodes().any { matches(it, label) } }
        val node = nodes().first { matches(it, label) }
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(bounds.exactCenterX(), bounds.exactCenterY())
        SystemClock.sleep(250)
    }
    private fun shell(value: String) {
        shellOutput(value)
    }
    private fun shellOutput(value: String): String {
        instrumentation.uiAutomation.executeShellCommand(value).use { fd ->
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
    private fun menu() {
        val label = context.getString(R.string.video_pip_listen)
        if (nodes().any { matches(it, label) }) return
        SystemClock.sleep(800)
        repeat(3) {
            if (nodes().any { matches(it, label) }) return
            val bounds = screenBounds()
            File(output, "pip-bounds.txt").appendText("screen=$bounds metrics=${main { activity.windowManager.currentWindowMetrics.bounds }}\n")
            tap(bounds.left + bounds.width() * .25f, bounds.top + bounds.height() * .25f)
            SystemClock.sleep(400)
        }
        await("pip-menu") { nodes().any { matches(it, label) } }
    }
    private fun screenBounds(): Rect {
        val shellState = shellOutput("dumpsys activity service com.android.systemui/.SystemUIService WMShell")
        val match = Regex("""PipBoundsState\s+mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""").find(shellState)
        if (match != null) {
            val values = match.groupValues.drop(1).map(String::toInt)
            return Rect(values[0], values[1], values[2], values[3])
        }
        instrumentation.uiAutomation.windows.firstOrNull {
            it.root?.packageName?.toString() == context.packageName
        }?.let { return Rect().also(it::getBoundsInScreen) }
        return main {
            val decor = activity.window.decorView
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            Rect(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
        }
    }
    private fun foreground() {
        if (main { activity.isInPictureInPictureMode }) {
            menu()
            val expand = nodes().firstOrNull { it.viewIdResourceName?.endsWith("id/expand_button") == true ||
                it.contentDescription?.toString() in listOf("Full screen", "Expand", "全屏", "展开") }
                ?: error("System expand button missing")
            check(expand.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        await("foreground") { main { !activity.isInPictureInPictureMode && !activity.isVideoPipLayoutLocked && activity.hasWindowFocus() } }
        check(main { activity.videoSession === session })
    }

    fun exercise(): String = runBlocking {
        check(context.packageName == "com.pixel.gallery.viewertrace")
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val previous = repository.settings.filterNotNull().first()
        val settings = VideoSettings(autoplay = true, endAction = VideoEndAction.LOOP,
            rememberPosition = true, backgroundAction = VideoBackgroundAction.PIP)
        repository.update { settings }
        await("preferences") { repository.settings.value == settings }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "pip-controls-${UUID.randomUUID()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PixelPipControlsQA/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        })!!
        val original = instrumentation.context.assets.open("player/capture-fixture.mp4").use { it.readBytes() }
        context.contentResolver.openOutputStream(uri)!!.use { it.write(original) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) as MainActivity
            await("video-first-frame") { main { activity.videoSession?.firstFrame == true } }
            session = main { checkNotNull(activity.videoSession) }
            SystemClock.sleep(800)
            for (label in listOf(context.getString(R.string.dialog_cancel), "Cancel")) {
                if (nodes().any { matches(it, label) }) click(label)
            }
            main { check(session.enterPip()) }
            await("manual-entry") { main { activity.isVideoPip && session.player.isPlaying } }
            menu()
            val buttons = listOf(R.string.video_pip_listen, R.string.video_background_pause, R.string.video_mute)
                .map { id -> Rect().also(nodes().first { matches(it, context.getString(id)) }::getBoundsInScreen) }
            check(buttons[0].centerX() < buttons[1].centerX() && buttons[1].centerX() < buttons[2].centerX())
            check(buttons.map { it.centerY() }.toSet().size == 1)
            dump("01-three-system-controls")
            report += "Production enterPip entry; SystemUI shows background / pause / mute left to right on the same row"
            click(context.getString(R.string.video_background_pause))
            await("paused") { main { !session.playbackRequested && !session.player.isPlaying } }
            val pausedPosition = main { session.player.currentPosition }
            SystemClock.sleep(500)
            check(main { kotlin.math.abs(session.player.currentPosition - pausedPosition) < 100 })
            menu(); dump("02-paused"); click(context.getString(R.string.video_resume))
            await("resumed") { main { session.player.isPlaying } }
            menu(); click(context.getString(R.string.video_mute))
            await("muted") { main { session.muted && session.player.volume == 0f && session.player.isPlaying } }
            menu(); dump("03-muted"); click(context.getString(R.string.video_unmute))
            await("unmuted") { main { !session.muted && session.player.volume == 1f } }
            report += "Actual system pause/resume and mute/unmute buttons update player state, volume and icons"
            // The menu consumes taps on its overlay; resize gestures begin after it dismisses.
            SystemClock.sleep(3500)
            val before = screenBounds()
            val outputSize = main { session.surface?.width to session.surface?.height }
            val x = before.left + before.width() * .25f
            val y = before.top + before.height() * .25f
            tap(x, y); SystemClock.sleep(65); tap(x, y)
            await("double-tap-resize") { main { activity.windowManager.currentWindowMetrics.bounds.width() != before.width() } }
            SystemClock.sleep(700)
            check(main { session.player.isPlaying && outputSize == (session.surface?.width to session.surface?.height) &&
                session.surface?.getGlobalVisibleRect(Rect()) == true })
            report += "Actual double-tap resize preserves playback, video output size and visible surface"
            foreground()
            await("return-output") { main { session.outputReadiness.isBuffered && session.player.isPlaying } }
            dump("04-same-session-return")
            shell("input keyevent KEYCODE_HOME")
            await("home-entry") { main { activity.isVideoPip && session.player.isPlaying } }
            menu()
            dump("04-home-system-controls")
            click(context.getString(R.string.video_pip_listen))
            await("background-audio") { main { !activity.isInPictureInPictureMode && session.player.isPlaying &&
                session.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) } }
            check(main { session.settings.backgroundAction == VideoBackgroundAction.PIP })
            report += "HOME entry; background button hides PiP, keeps same session/audio, disables video track and preserves saved PiP policy"
            shell("cmd statusbar expand-notifications")
            SystemClock.sleep(700)
            dump("05-background-notification")
            click(context.getString(R.string.video_background_pause))
            await("notification-paused") { main { !session.playbackRequested && !session.player.isPlaying } }
            click(context.getString(R.string.video_resume))
            await("notification-resumed") { main { session.player.isPlaying } }
            click(context.getString(R.string.video_background_audio))
            foreground()
            await("video-enabled") { main { !session.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) && session.outputReadiness.isBuffered } }
            check(main { session.player.isPlaying })
            report += "Notification pause/resume and return restore the same video session and output"
            shell("input keyevent KEYCODE_HOME")
            await("home-entry-again") { main { activity.isVideoPip } }
            menu()
            val close = nodes().firstOrNull { it.viewIdResourceName?.endsWith("id/dismiss") == true ||
                it.contentDescription?.toString() in listOf("Close", "关闭", "Dismiss") } ?: error("System close button missing")
            close.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await("closed") { main { !session.player.isPlaying } }
            dump("06-closed")
            report += "System close stops audio after returning from background mode"
            check(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(original))
            report += "Source video bytes unchanged"
        } catch (error: Throwable) {
            dump("failure")
            File(output, "failure.txt").writeText(error.stackTraceToString())
            throw error
        } finally {
            if (::activity.isInitialized) main { activity.videoSession?.stopBackgroundPlayback(); activity.finishAndRemoveTask() }
            repository.update { previous }
            await("restore-preferences") { repository.settings.value == previous }
            context.contentResolver.delete(uri, null, null)
            File(output, "report.txt").writeText(report.joinToString("\n"))
        }
        report.joinToString("\n")
    }
}
