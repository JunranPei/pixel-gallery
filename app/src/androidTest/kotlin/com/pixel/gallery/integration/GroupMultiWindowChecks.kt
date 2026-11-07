package com.pixel.gallery.integration

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.AlbumGroup
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pixel.gallery.model.withOrder
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.Screen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.utils.GroupWorkProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import java.util.UUID

/** Real tasks/product composables; temporary media, with explicit route setup in the test host. */
internal class GroupMultiWindowChecks(private val runner: Instrumentation, private val run: String) {
    private val context = runner.targetContext
    private val activities = mutableListOf<MainActivity>()
    private val frames = Collections.synchronizedList(mutableListOf<Map<String, Any>>())
    private val life = Collections.synchronizedList(mutableListOf<String>())
    private val draws = Collections.synchronizedList(mutableListOf<Map<String, Any>>())
    private val rendererIds = mutableMapOf<Int, Int>()
    private val ssivReady = SubsamplingScaleImageView::class.java.getDeclaredField("isReady").apply { isAccessible = true }
    private val ssivLoaded = SubsamplingScaleImageView::class.java.getDeclaredField("isImageLoaded").apply { isAccessible = true }
    @Volatile private var phase = "setup"
    private val app get() = context.applicationContext as Application
    private val output = File(context.cacheDir, "group-multiwindow/$run").apply { mkdirs() }

    fun exercise(): String = runBlocking {
        val settings = SettingsRepository(context)
        if (run.startsWith("cleanup-")) {
            val token = run.removePrefix("cleanup-")
            require(token.matches(Regex("[0-9a-f]{6}")))
            val id = "probe-$token"
            fun owned(key: String) = key == "group:$id" || key.startsWith("album:/storage/emulated/0/Pictures/GroupWindow_$token/")
            settings.updateAlbumGrouping { state -> state.copy(
                groups = state.groups.filterNot { it.id == id }, collapsedIds = state.collapsedIds - id,
                ordering = state.ordering.orEmpty().filterKeys { it != id }.mapValues { (_, order) ->
                    order.copy(custom = order.custom.filterNot(::owned), pinned = order.pinned.filterNot(::owned))
                },
            ) }
            context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/GroupWindow_$token/%"))
            return@runBlocking "Removed only owned interrupted fixture $token and its saved order references."
        }
        val original = settings.albumGrouping.first()
        val enabled = settings.groupingEnabled.first()
        val token = UUID.randomUUID().toString().take(6)
        val groupId = "probe-$token"
        val groupName = "AA Probe $token"
        val uris = mutableListOf<android.net.Uri>()
        val paths = mutableListOf<String>()
        val records = mutableListOf<Map<String, Any>>()
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { life += "$phase create task=${a.taskId} hash=${a.hashCode()} saved=${b != null}" }
            override fun onActivityDestroyed(a: Activity) { life += "$phase destroy task=${a.taskId} hash=${a.hashCode()} finishing=${a.isFinishing}" }
            override fun onActivityStarted(a: Activity) { life += "$phase start task=${a.taskId} hash=${a.hashCode()}" }
            override fun onActivityResumed(a: Activity) { life += "$phase resume task=${a.taskId} hash=${a.hashCode()}" }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        }
        app.registerActivityLifecycleCallbacks(callback)
        try {
            repeat(12) { album ->
                val relative = "Pictures/GroupWindow_$token/A${album.toString().padStart(2, '0')}/"
                repeat(4) { picture ->
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "probe-$picture.jpg")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    })!!
                    uris += uri
                    val bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply {
                        drawColor(0xff447799.toInt() + album * 0x030100 + picture * 0x020003)
                        val paint = Paint().apply { color = 0xffffcc66.toInt(); textSize = 120f }
                        drawCircle(512f, 384f, 180f, paint)
                        paint.color = 0xff222222.toInt()
                        drawText("$album / $picture", 320f, 420f, paint)
                    }
                    context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bitmap.recycle()
                    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    if (picture == 0) context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
                        check(it.moveToFirst()); paths += File(it.getString(0)).parent!!
                    }
                }
            }
            settings.setGroupingEnabled(true)
            settings.updateAlbumGrouping { state ->
                state.copy(groups = listOf(AlbumGroup(groupId, groupName, paths)) + state.groups,
                    collapsedIds = state.collapsedIds + groupId)
                    .withOrder("groups:albums") { it.copy(custom = listOf("group:$groupId") + it.custom) }
            }
            val viewers = (0..2).map { index -> launch(Screen.Viewer(uris[index * 4].lastPathSegment!!.toLong(), Screen.ViewerSource.All)) }
            check(viewers.map { it.taskId }.distinct().size == 3) { "Tasks were reused" }
            val grid = launch(Screen.Home)
            await { find(groupName) != null }
            GroupWorkProbe.enabled = true
            fun windowPass(label: String) {
                viewers.forEachIndexed { i, a ->
                    phase = "$label-viewer-$i"
                    front(a)
                    SystemClock.sleep(850)
                    check(!a.isDestroyed) { "Viewer task ${a.taskId} destroyed" }
                }
                front(grid); await { find(groupName) != null }
            }
            windowPass("control")
            phase = "idle-control"; GroupWorkProbe.reset(); SystemClock.sleep(1200)
            records += mapOf("phase" to phase, "work" to GroupWorkProbe.snapshot(), "heap" to heap())
            val batches = if (run.startsWith("listen")) 6 else 3
            val steps = if (run.startsWith("listen")) 16 else 8
            repeat(batches) { batch ->
                repeat(steps) { step ->
                    phase = "toggle-$batch-$step"
                    GroupWorkProbe.reset()
                    val before = settings.albumGrouping.first().collapsedIds.contains(groupId)
                    val gc = Debug.getRuntimeStat("art.gc.gc-count")
                    val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                    val started = SystemClock.elapsedRealtime()
                    tap(groupName)
                    await { runBlocking { settings.albumGrouping.first().collapsedIds.contains(groupId) != before } }
                    val responseMs = SystemClock.elapsedRealtime() - started
                    SystemClock.sleep(if (run.startsWith("listen")) listOf(500L, 850L, 1600L, 600L)[step % 4] else 750L)
                    records += mapOf("phase" to phase, "elapsedMs" to SystemClock.elapsedRealtime() - started,
                        "atElapsedMs" to started, "responseMs" to responseMs,
                        "allocatedBytes" to Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated,
                        "work" to GroupWorkProbe.snapshot(), "heap" to heap(), "gcBefore" to gc,
                        "gcAfter" to Debug.getRuntimeStat("art.gc.gc-count"))
                    File(output, "progress.json").writeText(Gson().toJson(records.last()))
                    if (run.contains("retained")) {
                        val work = GroupWorkProbe.snapshot()
                        check(work["group_grid_rows"] == 1L) { "Covered grids rebuilt: $work" }
                        check(listOf("album_covers_rebuild", "labels_rebuild", "label_visibility_filter", "media_exclusion_filter")
                            .all { (work[it] ?: 0L) == 0L }) { "Group toggle rebuilt media indexes: $work" }
                    }
                }
                windowPass("batch-$batch")
            }
            if (run.contains("retained")) {
                phase = "return-to-albums-sync"
                check(groupId in settings.albumGrouping.first().collapsedIds)
                tap(groupName)
                await { runBlocking { groupId !in settings.albumGrouping.first().collapsedIds } }
                front(viewers.first())
                runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await { find(groupName) != null && find("A00") != null }
                // A Home route stopped in another task must also defer presentation.
                front(grid)
                SystemClock.sleep(500)
                GroupWorkProbe.reset()
                tap(groupName)
                await { runBlocking { groupId in settings.albumGrouping.first().collapsedIds } }
                SystemClock.sleep(600)
                check(GroupWorkProbe.snapshot()["group_grid_rows"] == 1L)
                front(viewers.first())
                await { find(groupName) != null && find("A00") == null }
                records += mapOf("phase" to phase, "expandedOnReturn" to true,
                    "collapsedOnReturn" to true, "stoppedHomeIgnoredRemoteToggle" to true)
            }
            "Completed 3 viewer tasks + album task, ${batches * steps} measured real group taps; evidence=$output"
        } finally {
            GroupWorkProbe.enabled = false
            File(output, "result.json").writeText(Gson().toJson(mapOf("run" to run, "records" to records,
                "frames" to frames.toList(), "lifecycle" to life.toList(), "draws" to draws.toList())))
            app.unregisterActivityLifecycleCallbacks(callback)
            runner.runOnMainSync { activities.forEach { if (!it.isDestroyed) it.finish() } }
            settings.updateAlbumGrouping { original }; settings.setGroupingEnabled(enabled)
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun heap() = mapOf("java" to Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), "native" to Debug.getNativeHeapAllocatedSize())
    private fun launch(screen: Screen): MainActivity {
        // MainActivity is singleTop: leave its task before launching a distinct document.
        runner.uiAutomation.performGlobalAction(2)
        SystemClock.sleep(400)
        val a = runner.startActivitySync(Intent(context, MainActivity::class.java)
            .setData(android.net.Uri.parse("pixel-gallery-test://group-window/${UUID.randomUUID()}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
        activities += a
        runner.runOnMainSync {
            val model = ViewModelProvider(a)[PhotosViewModel::class.java]
            a.setContent { PixelGalleryTheme(dynamicColor = false) { MainScaffold(initialScreen = screen, initialHomeTab = 1, photosViewModel = model) } }
            model.refresh()
            a.window.addOnFrameMetricsAvailableListener({ _, f, dropped ->
                frames += mapOf("phase" to phase, "task" to a.taskId, "totalMs" to f.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6,
                    "layoutMs" to f.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1e6, "drawMs" to f.getMetric(FrameMetrics.DRAW_DURATION) / 1e6,
                    "gpuMs" to f.getMetric(FrameMetrics.GPU_DURATION) / 1e6,
                    "uiMs" to listOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
                        FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION).sumOf { f.getMetric(it) } / 1e6,
                    "atElapsedMs" to SystemClock.elapsedRealtime(), "droppedReports" to dropped)
            }, Handler(Looper.getMainLooper()))
            if (screen is Screen.Viewer) {
                a.window.decorView.viewTreeObserver.addOnDrawListener {
                    if (a.hasWindowFocus()) {
                        val renderer = imageView(a.window.decorView)
                        draws += mapOf("phase" to phase, "task" to a.taskId, "view" to (renderer?.hashCode() ?: 0),
                            "drawable" to ready(renderer), "atElapsedMs" to SystemClock.elapsedRealtime(),
                            "alpha" to (renderer?.alpha ?: -1f))
                    }
                }
            }
        }
        if (screen is Screen.Viewer) {
            await { var loaded = false; runner.runOnMainSync { loaded = ready(imageView(a.window.decorView)) }; loaded }
            runner.runOnMainSync { rendererIds[a.taskId] = imageView(a.window.decorView)!!.hashCode() }
        }
        SystemClock.sleep(600)
        return a
    }
    private fun ready(v: View?) = when (v) {
        is SubsamplingScaleImageView -> ssivReady.getBoolean(v) && ssivLoaded.getBoolean(v)
        is ImageView -> v.drawable != null
        else -> false
    }
    private fun imageView(v: View): View? {
        if ((v is ImageView || v is SubsamplingScaleImageView) && v.width > 300 && v.height > 300 && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) repeat(v.childCount) { imageView(v.getChildAt(it))?.let { return it } }
        return null
    }
    private fun front(a: Activity) {
        val manager = context.getSystemService(ActivityManager::class.java)
        manager.appTasks.first { it.taskInfo.taskId == a.taskId }.moveToFront()
        await { a.hasWindowFocus() }
    }
    private fun find(name: String): AccessibilityNodeInfo? {
        fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.text?.toString() == name || n.contentDescription?.toString() == name) return n
            repeat(n.childCount) { n.getChild(it)?.let { child -> visit(child)?.let { return it } } }
            return null
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun tap(name: String) {
        var node = find(name) ?: error("Missing group $name")
        while (!node.isClickable && node.parent != null) node = node.parent
        val bounds = Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            runner.sendPointerSync(event); event.recycle()
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(100) }
        error("Timed out during $phase")
    }
}
