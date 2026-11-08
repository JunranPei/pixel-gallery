package com.pixel.gallery.integration

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.google.gson.Gson
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.local.dao.MediaDao
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.model.ConflictPolicy
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.TransferMode
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewer.VideoPlaybackSession
import com.pixel.gallery.ui.viewer.VideoPlayer
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real task recreation, independent window state, no screenshot inference. */
internal class ViewerWindowStateChecks(private val runner: Instrumentation) {
    fun exercise(transferCheck: Boolean = false): String {
        val context = runner.targetContext
        check(context.packageName == "com.pixel.gallery.memorypatched")
        val source = File(context.filesDir, "window-state-source.mp4")
        check(source.isFile)
        val app = context.applicationContext as Application
        val tasks = ConcurrentHashMap<Int, MainActivity>()
        val restored = ConcurrentHashMap.newKeySet<Int>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, state: Bundle?) {
                if (a is MainActivity) { tasks[a.taskId] = a; if (state != null) restored.add(a.hashCode()) }
            }
            override fun onActivityPostCreated(a: Activity, state: Bundle?) {
                if (a is MainActivity) {
                    @Suppress("UNCHECKED_CAST")
                    (MainActivity::class.java.getDeclaredField("showNotificationAccess").apply { isAccessible = true }
                        .get(a) as MutableState<Boolean>).value = false
                }
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        val uris = mutableListOf<android.net.Uri>()
        val anchors = mutableListOf<Activity>()
        val expected = linkedMapOf<Int, Triple<Long, Float, Boolean>>()
        val results = mutableListOf<Map<String, Any>>()
        val relativeRoot = "Movies/PixelWindowStateQA-${java.util.UUID.randomUUID()}/"
        val fixtureRoot = File(android.os.Environment.getExternalStorageDirectory(), relativeRoot).canonicalFile
        try {
            repeat(2) { index ->
                val uri = checkNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "window-state-${System.nanoTime()}.mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeRoot)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                uris += uri
                context.contentResolver.openOutputStream(uri)!!.use { output -> source.inputStream().use { it.copyTo(output) } }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                android.util.Log.i("WindowStateQA", "launch $index uri=$uri")
                if (index > 0) {
                    // singleTop reuses the focused viewer even with document flags.
                    // The same isolated anchor used by the 50-window test creates
                    // a real second task without altering the product manifest.
                    anchors += runner.startActivitySync(Intent().setClassName(context.packageName,
                        "com.pixel.gallery.integration.MemoryLaunchAnchorActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                }
                runner.runOnMainSync {
                    context.startActivity(Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                }
                await { tasks.values.any { it.intent.data == uri && it.hasWindowFocus() && it.videoSession?.firstFrame == true } }
                val activity = tasks.values.single { it.intent.data == uri }
                android.util.Log.i("WindowStateQA", "ready $index task=${activity.taskId}")
                await { activity.hasWindowFocus() && activity.videoSession?.firstFrame == true }
                lateinit var session: VideoPlaybackSession
                var position = 0L
                val speed = if (index == 0) 0.75f else 1.25f
                val muted = index == 0
                runner.runOnMainSync {
                    session = checkNotNull(activity.videoSession)
                    check(session.settings.autoplay && !session.settings.rememberPosition && !session.settings.rememberSpeed)
                    position = (session.player.duration * (index + 1) / 3).coerceAtLeast(200)
                    session.pause()
                    session.player.seekTo(position)
                    check(session.setSpeed(speed))
                    if (session.muted != muted) session.toggleMute()
                }
                await { !session.player.playWhenReady && kotlin.math.abs(session.player.currentPosition - position) < 100 }
                // The normal UI timer hides the bars, exactly as during viewing.
                SystemClock.sleep(3700)
                android.util.Log.i("WindowStateQA", "inspect toolbar $index")
                check(!hasBackButton()) { "Preparation did not hide the toolbar" }
                expected[activity.taskId] = Triple(position, speed, muted)
            }
            if (transferCheck) {
                val originalActivities = expected.keys.associateWith { tasks.getValue(it) }
                anchors += runner.startActivitySync(Intent().setClassName(context.packageName,
                    "com.pixel.gallery.integration.MemoryLaunchAnchorActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                val gridUri = android.net.Uri.parse("memory-check://transfer-grid/${System.nanoTime()}")
                runner.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN).setData(gridUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) }
                await { tasks.values.any { it.intent.data == gridUri && it.hasWindowFocus() } }
                val grid = tasks.values.single { it.intent.data == gridUri }
                lateinit var model: PhotosViewModel
                runner.runOnMainSync { model = ViewModelProvider(grid)[PhotosViewModel::class.java] }
                val repo = PhotosViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }
                    .get(model) as MediaRepository
                val dao = MediaRepository::class.java.getDeclaredField("mediaDao").apply { isAccessible = true }
                    .get(repo) as MediaDao
                val id = uris.first().lastPathSegment!!.toLong()
                fun hash(file: File): String = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                runBlocking { withTimeout(30_000) { repo.syncWhenMediaMutationsIdle(force = true) } }
                for (restoring in listOf(false, true)) {
                    shell("am task focus ${grid.taskId}")
                    await { grid.hasWindowFocus() }
                    val entry = runBlocking { dao.getEntriesByIds(listOf(id)).single() }
                    val sourceFile = File(entry.path).canonicalFile
                    check(sourceFile.path.startsWith(fixtureRoot.path + File.separator))
                    val targetDir = if (restoring) fixtureRoot else File(fixtureRoot, "Moved").apply { mkdirs() }
                    val target = File(targetDir, sourceFile.name)
                    val digest = hash(sourceFile)
                    val summary = runBlocking { withTimeout(30_000) { repo.transferMedia(listOf(entry),
                        TransferDestination(targetDir.path, targetDir.name, targetDir.path),
                        TransferMode.MOVE, ConflictPolicy.KEEP_BOTH) {} } }
                    check(summary.succeeded == 1 && summary.failed == 0 && summary.skipped == 0) { summary.toString() }
                    check(!sourceFile.exists() && target.isFile && hash(target) == digest)
                    val moved = runBlocking { dao.getEntriesByIds(listOf(id)).single() }
                    check(File(moved.path).canonicalFile == target.canonicalFile)
                    val providerPath = context.contentResolver.query(uris.first(),
                        arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use { cursor ->
                        check(cursor.moveToFirst()); File(cursor.getString(0)).canonicalFile
                    }
                    check(providerPath == target.canonicalFile)
                    SystemClock.sleep(500)
                    for ((task, wanted) in expected) {
                        val original = originalActivities.getValue(task)
                        shell("am task focus $task")
                        await { original.hasWindowFocus() && original.videoSession?.firstFrame == true }
                        val barsVisible = hasBackButton()
                        SystemClock.sleep(600)
                        runner.runOnMainSync {
                            val session = checkNotNull(original.videoSession)
                            results += mapOf("phase" to if (restoring) "real-transfer-restored" else "real-transfer-moved",
                                "task" to task, "sameActivity" to (tasks[task] === original && !original.isDestroyed),
                                "movedVideo" to (original.intent.data == uris.first()), "barsVisible" to barsVisible,
                                "playing" to session.player.playWhenReady, "position" to session.player.currentPosition,
                                "expectedPosition" to wanted.first, "speed" to session.speed, "expectedSpeed" to wanted.second,
                                "muted" to session.muted, "expectedMuted" to wanted.third,
                                "fileVerified" to true, "databasePathVerified" to true, "providerPathVerified" to true)
                        }
                    }
                }
                val report = Gson().toJson(results)
                File(context.cacheDir, "window-state-result.json").writeText(report)
                return report
            }
            for ((task, wanted) in expected) {
                android.util.Log.i("WindowStateQA", "return task=$task")
                shell("am task focus $task")
                val original = checkNotNull(tasks[task])
                await { original.hasWindowFocus() && original.videoSession?.firstFrame == true }
                check(!hasBackButton()) { "Switching task already reset the toolbar" }
                runner.runOnMainSync { original.recreate() }
                android.util.Log.i("WindowStateQA", "recreate task=$task")
                await {
                    val a = tasks[task]
                    a != null && a !== original && a.hasWindowFocus() && a.videoSession?.firstFrame == true
                }
                val next = checkNotNull(tasks[task])
                // Read before the default 3-second hide timer can conceal a reset.
                val barsVisible = hasBackButton()
                var row: Map<String, Any> = emptyMap()
                runner.runOnMainSync {
                    val session = checkNotNull(next.videoSession)
                    row = mapOf("phase" to "task-recreation", "task" to task, "saved" to restored.contains(next.hashCode()),
                        "barsVisible" to barsVisible, "playing" to session.player.playWhenReady,
                        "position" to session.player.currentPosition, "expectedPosition" to wanted.first,
                        "speed" to session.speed, "expectedSpeed" to wanted.second,
                        "muted" to session.muted, "expectedMuted" to wanted.third)
                }
                SystemClock.sleep(600)
                runner.runOnMainSync {
                    val session = checkNotNull(next.videoSession)
                    row = row + mapOf("laterPosition" to session.player.currentPosition,
                        "laterPlaying" to session.player.playWhenReady)
                }
                results += row
                android.util.Log.i("WindowStateQA", Gson().toJson(row))
            }
            // Exercise the real VideoPlayer with the same URI and changing source
            // revision. This isolates the decoder replacement caused by a changed
            // MediaEntry; it does not substitute a fake player or a new Activity.
            val host = tasks.getValue(expected.keys.last())
            val sourceVersion = mutableLongStateOf(1L)
            val foreground = mutableStateOf(true)
            val previousPageSession = host.videoSession
            runner.runOnMainSync { host.setContent {
                PixelGalleryTheme(dynamicColor = false) {
                    VideoPlayer(uris.last().toString(), modifier = Modifier.fillMaxSize(),
                        sourceVersion = sourceVersion.longValue, showUI = false,
                        isForeground = foreground.value)
                }
            } }
            await { host.videoSession != null && host.videoSession !== previousPageSession &&
                host.videoSession?.firstFrame == true }
            lateinit var originalSession: VideoPlaybackSession
            runner.runOnMainSync {
                originalSession = checkNotNull(host.videoSession)
                originalSession.pause()
                originalSession.player.seekTo(900)
                check(originalSession.setSpeed(.5f))
                if (!originalSession.muted) originalSession.toggleMute()
            }
            await { originalSession.player.currentPosition == 900L }
            val retiredSessions = mutableListOf<java.lang.ref.WeakReference<VideoPlaybackSession>>()
            for (background in listOf(false, true)) {
                retiredSessions += java.lang.ref.WeakReference(originalSession)
                if (background) runner.runOnMainSync { foreground.value = false }
                SystemClock.sleep(100)
                runner.runOnMainSync { sourceVersion.longValue++ }
                SystemClock.sleep(150)
                if (background) runner.runOnMainSync { foreground.value = true }
                await { host.videoSession != null && host.videoSession !== originalSession && host.videoSession?.firstFrame == true }
                SystemClock.sleep(600)
                var row: Map<String, Any> = emptyMap()
                runner.runOnMainSync {
                    val session = checkNotNull(host.videoSession)
                    row = mapOf("phase" to if (background) "background-source-revision" else "foreground-source-revision",
                        "sameActivity" to true, "sessionReplaced" to (session !== originalSession),
                        "playing" to session.player.playWhenReady, "position" to session.player.currentPosition,
                        "expectedPosition" to 900L, "speed" to session.speed, "expectedSpeed" to .5f,
                        "muted" to session.muted, "expectedMuted" to true)
                    originalSession = session
                }
                results += row
                android.util.Log.i("WindowStateQA", Gson().toJson(row))
            }
            // The new saved state must not keep disposed players/Activities alive.
            // Do not dereference a WeakReference immediately before GC: ART can
            // keep that temporary referent live in this method's stack registers.
            repeat(4) {
                System.gc()
                System.runFinalization()
                SystemClock.sleep(200)
            }
            val retainedSessions = retiredSessions.count { it.get() != null }
            if (retainedSessions > 0) {
                retiredSessions.forEachIndexed { index, reference -> android.util.Log.e("WindowStateQA",
                    "retired[$index] retained=${reference.get()?.id}") }
                android.os.Debug.dumpHprofData(File(context.cacheDir, "video-session-lifetime.hprof").path)
            }
            check(retainedSessions == 0) { "Still retaining $retainedSessions replaced component sessions" }
            results[results.lastIndex] = results.last() + mapOf("retiredSessions" to retiredSessions.size,
                "retainedDisposedSessions" to retainedSessions)
            val report = Gson().toJson(results)
            File(context.cacheDir, "window-state-result.json").writeText(report)
            return report
        } catch (error: Throwable) {
            runner.runOnMainSync {
                tasks.values.forEach { activity ->
                    android.util.Log.e("WindowStateQA", "failure task=${activity.taskId} uri=${activity.intent.data} " +
                        "focus=${activity.hasWindowFocus()} destroyed=${activity.isDestroyed} " +
                        "session=${activity.videoSession?.diagnosticsSnapshot()}")
                }
            }
            fun describe(node: AccessibilityNodeInfo, output: StringBuilder) {
                output.appendLine("${node.className} visible=${node.isVisibleToUser} text=${node.text} desc=${node.contentDescription}")
                repeat(node.childCount) { node.getChild(it)?.let { child -> describe(child, output) } }
            }
            val nodes = StringBuilder()
            runner.uiAutomation.rootInActiveWindow?.let { describe(it, nodes) }
            android.util.Log.e("WindowStateQA", nodes.toString())
            throw error
        } finally {
            runner.runOnMainSync { tasks.values.forEach { if (!it.isDestroyed) it.finish() } }
            runner.runOnMainSync { anchors.forEach { if (!it.isDestroyed) it.finish() } }
            app.unregisterActivityLifecycleCallbacks(callbacks)
            uris.forEach { context.contentResolver.delete(it, null, null) }
            // Only these UUID-owned, now-empty folders can be removed.
            File(fixtureRoot, "Moved").delete()
            fixtureRoot.delete()
        }
    }

    private fun hasBackButton(): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.contentDescription?.toString() == "Back") return true
            return (0 until node.childCount).any { node.getChild(it)?.let(::visit) == true }
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit) == true
    }
    private fun shell(command: String) {
        runner.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            runner.runOnMainSync { ready = predicate() }
            if (ready) return
            SystemClock.sleep(25)
        }
        error("Viewer window state timed out")
    }
}
