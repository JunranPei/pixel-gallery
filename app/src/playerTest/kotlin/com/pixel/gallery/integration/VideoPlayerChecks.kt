package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.C
import androidx.media3.common.Player
import com.pixel.gallery.MainActivity
import com.pixel.gallery.R
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.ui.viewer.VideoPlaybackSession
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Exercises the real ACTION_VIEW route and production controls against owned video files. */
internal class VideoPlayerChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val repository = VideoSettingsRepository(context)
    private val output = File(context.getExternalFilesDir(null), "video-player-qa").apply { mkdirs() }
    private lateinit var activity: MainActivity
    private lateinit var source: Uri
    private fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(label: String, timeout: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val event = MotionEvent.obtain(now, now + i * 60, action, x, y, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
    }
    private fun click(label: String) {
        if (label in listOf(R.string.video_mute, R.string.video_unmute, R.string.video_speed, R.string.video_capture, R.string.video_pip).map(context::getString)) showControls()
        await(label) { nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label } }
        val matches = nodes().filter { it.text?.toString() == label || it.contentDescription?.toString() == label }
        val node = matches.firstOrNull { it.isClickable } ?: matches.first()
        val rect = Rect().also(node::getBoundsInScreen)
        File(output, "clicks.txt").appendText("$label $rect clickable=${node.isClickable}\n")
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(rect.centerX().toFloat(), rect.centerY().toFloat())
        SystemClock.sleep(180)
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun session(): VideoPlaybackSession = main { activity.videoSession } ?: error("No active video session")
    private fun showControls() {
        if (nodes().none { it.contentDescription?.toString() == context.getString(R.string.video_speed) }) {
            tap(500f, 650f)
            SystemClock.sleep(250)
        }
    }
    private fun foreground() {
        main {
            context.getSystemService(android.app.ActivityManager::class.java).appTasks
                .first { it.taskInfo.taskId == activity.taskId }.moveToFront()
        }
    }
    private fun open() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setDataAndType(source, "video/mp4")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) as MainActivity
        await("active video") { main { activity.videoSession?.firstFrame == true } }
        SystemClock.sleep(900)
        repeat(3) {
            if (nodes().any { it.text?.toString() == "Background support" }) click("Cancel")
            SystemClock.sleep(200)
        }
        showControls()
    }
    private fun capturedIds(): Set<Long> = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.IS_PENDING}=0", arrayOf("Pictures/Video captures/"), null,
    )!!.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    fun exercise(): String = runBlocking {
        val previous = repository.settings.filterNotNull().first()
        val initial = VideoSettings(autoplay = false, muted = true, rememberPosition = true,
            rememberSpeed = true, lastSpeed = 1.5f)
        repository.update { initial }
        await("saved preferences") { repository.settings.value == initial }
        val token = UUID.randomUUID().toString()
        source = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "player-$token.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PlayerQA_$token/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        })!!
        context.contentResolver.openOutputStream(source)!!.use { output ->
            instrumentation.context.assets.open("player/capture-fixture.mp4").use { it.copyTo(output) }
        }
        context.contentResolver.update(source, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        val original = context.contentResolver.openInputStream(source)!!.use { it.readBytes() }
        val capturesBefore = capturedIds()
        val report = mutableListOf<String>()
        val ownedCaptures = mutableSetOf<Long>()
        try {
            open()
            check(main { !session().player.playWhenReady && session().muted && session().player.volume == 0f && session().speed == 1.5f })
            report += "V01: cold opening respects autoplay off, mute and remembered 1.5x"
            screenshot("01-original-slider-and-toolbar")
            click(context.getString(R.string.video_unmute))
            await("unmuted volume") { main { session().player.volume == 1f } }
            click(context.getString(R.string.video_speed)); click("2×"); click(context.getString(android.R.string.ok))
            check(main { session().player.playbackParameters.speed == 2f })
            click(context.getString(R.string.video_speed)); screenshot("02-speed-dialog")
            click("0.5×"); click(context.getString(R.string.dialog_cancel))
            check(main { session().speed == 2f })
            report += "V02: toolbar mute; confirmed 2x; cancelled 0.5x preserves 2x"

            main { session().player.seekTo(1800) }
            SystemClock.sleep(500)
            showControls(); click(context.getString(R.string.video_capture))
            await("capture saved") { capturedIds().size == capturesBefore.size + 1 }
            val capture = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, (capturedIds() - capturesBefore).single())
            ownedCaptures += android.content.ContentUris.parseId(capture)
            val bitmap = context.contentResolver.openInputStream(capture)!!.use(BitmapFactory::decodeStream)!!
            check(bitmap.width == 320 && bitmap.height == 180)
            val red = bitmap.getPixel(60, 90); val blue = bitmap.getPixel(260, 90)
            check(Color.red(red) > 180 && Color.blue(red) < 70 && Color.blue(blue) > 180 && Color.red(blue) < 70)
            File(output, "captured-frame.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            check(context.contentResolver.openInputStream(source)!!.use { it.readBytes() }.contentEquals(original))
            report += "V03: real PixelCopy/save produced red-left blue-right 320x180 PNG; source bytes unchanged"

            repository.update { it.copy(captureTree = "content://missing.qa.provider/tree/missing") }
            await("bad folder applied") { main { session().settings.captureTree.isNotBlank() } }
            showControls(); click(context.getString(R.string.video_capture))
            await("capture failure settled") { main { !session().captureBusy } }
            check(capturedIds().size == capturesBefore.size + 1)
            repository.update { it.copy(captureTree = "") }
            report += "V04: inaccessible destination fails without extra MediaStore image"

            main { session().player.seekTo(2200); session().checkpoint() }
            main { activity.finish() }
            SystemClock.sleep(400)
            open()
            check(main { session().player.currentPosition in 1800..2600 && session().speed == 2f && !session().player.playWhenReady })
            report += "V05: position and speed survive viewer close/reopen"

            main { session().togglePlay() }
            await("playing") { main { session().player.isPlaying } }
            main { session().player.seekTo(5600) }
            await("stop at end") { main { session().player.playbackState == Player.STATE_ENDED } }
            repository.update { it.copy(endAction = VideoEndAction.LOOP) }
            await("loop configured") { main { session().player.repeatMode == Player.REPEAT_MODE_ONE } }
            main { session().togglePlay(); session().player.seekTo(5600) }
            await("loop to beginning") { main { session().player.currentPosition < 2500 && session().player.isPlaying } }
            report += "V06: stop reaches STATE_ENDED; loop restarts without changing media"

            main { session().pause() }
            repository.update { it.copy(backgroundAction = VideoBackgroundAction.AUDIO) }
            await("audio configured") { main { session().settings.backgroundAction == VideoBackgroundAction.AUDIO } }
            main { session().togglePlay() }
            await("audio playing") { main { session().player.isPlaying } }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            await("background audio") { main { session().player.isPlaying && session().player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) } }
            screenshot("03-background-audio")
            report += "V07: HOME keeps audio playing and disables video track"
            foreground()
            await("video track restored") { main { !session().player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) } }
            main { session().pause() }
            repository.update { it.copy(backgroundAction = VideoBackgroundAction.PAUSE) }
            await("pause configured") { main { session().settings.backgroundAction == VideoBackgroundAction.PAUSE } }
            main { session().togglePlay() }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            await("background paused") { main { !session().player.isPlaying && !session().player.playWhenReady } }
            foreground()
            await("foreground playback restored") { main { session().player.isPlaying } }
            showControls(); click(context.getString(R.string.video_pip))
            await("manual PiP") { main { activity.isInPictureInPictureMode } }
            screenshot("04-picture-in-picture")
            report += "V08: manual PiP entered through real toolbar"
            foreground()
            await("PiP expanded") { main { !activity.isInPictureInPictureMode } }
            repository.update { it.copy(backgroundAction = VideoBackgroundAction.PIP) }
            await("auto PiP configured") { main { session().settings.backgroundAction == VideoBackgroundAction.PIP } }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            await("automatic PiP") { main { activity.isInPictureInPictureMode } }
            report += "V08: HOME automatically enters PiP; expanding restores the same task"
            foreground()
            await("PiP expanded again") { main { !activity.isInPictureInPictureMode } }
            main {
                session().player.setMediaItem(androidx.media3.common.MediaItem.fromUri("file:///missing-player-qa-$token.mp4"))
                session().player.prepare()
            }
            await("platform fallback") { main { session().native?.width == 320 && session().firstFrame } }
            main { session().pause() }
            check(main { session().setSpeed(1.25f) && session().speed == 1.25f })
            showControls(); screenshot("05-platform-fallback")
            val surfaceSize = main { session().surface!!.width to session().surface!!.height }
            check(kotlin.math.abs(surfaceSize.first.toFloat() / surfaceSize.second - 320f / 180) < .03f)
            report += "V10: injected primary decoder failure uses actual platform fallback; speed and aspect preserved"
            repository.update { it.copy(rememberPosition = false, rememberSpeed = false, autoplay = false) }
            await("memory disabled") { main { !session().settings.rememberSpeed && !session().settings.rememberPosition } }
            main { activity.finish() }
            SystemClock.sleep(400)
            open()
            check(main { session().speed == 1f && session().player.currentPosition < 300 && !session().player.playWhenReady })
            report += "V05: disabling memory opens at 0 and 1x without autoplay"
        } catch (error: Throwable) {
            screenshot("failure")
            File(output, "failure.txt").writeText(error.stackTraceToString())
            throw error
        } finally {
            if (::activity.isInitialized) main { activity.videoSession?.pause(); activity.finish() }
            repository.update { previous }
            await("settings restored") { repository.settings.value == previous }
            context.contentResolver.delete(source, null, null)
            ownedCaptures.forEach { id ->
                context.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null)
            }
        }
        report.joinToString("\n").also { File(output, "report.txt").writeText(it) }
    }
}
