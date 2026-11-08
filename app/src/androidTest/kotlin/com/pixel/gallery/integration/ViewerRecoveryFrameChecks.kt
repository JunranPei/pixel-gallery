package com.pixel.gallery.integration

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.pixel.gallery.MainActivity
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewmodel.LabelsViewModel
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.LabelRepository
import com.pixel.gallery.model.LabelEditRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.IdentityHashMap

/** First-draw listener through real Activity recovery; no screenshots or route reinjection. */
internal class ViewerRecoveryFrameChecks(private val runner: Instrumentation) {
    private val rows = Collections.synchronizedList(mutableListOf<Map<String, Any>>())
    @Volatile private var current: MainActivity? = null
    @Volatile private var phase = "initial"
    private data class Observer(val decor: View, val draw: ViewTreeObserver.OnDrawListener)
    private val observers = IdentityHashMap<Activity, Observer>()
    private val ready = SubsamplingScaleImageView::class.java.getDeclaredField("isReady").apply { isAccessible = true }
    fun exercise(missingOnRestore: Boolean = false, source: String = "external", prepareProcess: Boolean = false, mediaCount: Int = 1): String {
        val context = runner.targetContext
        check(source in setOf("external", "album", "label", "photos", "albums"))
        val gridMode = source in setOf("photos", "albums")
        check(mediaCount in 1..32 && (mediaCount == 1 || (gridMode && !missingOnRestore)))
        val gridDraw = GlideGridDrawSnapshot()
        val stopped = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        if (missingOnRestore) check(context.packageName == "com.pixel.gallery.memorypatched")
        val app = context.applicationContext as Application
        val token = UUID.randomUUID().toString().take(8)
        val albumName = "000_Recovery_$token"
        val labelName = "Recovery_Label_$token"
        var fixtureLabelId: Long? = null
        var fixtureLabels: LabelRepository? = null
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "recovery-$token.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$albumName/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff346f88.toInt())
            context.contentResolver.openOutputStream(uri)!!.use { compress(Bitmap.CompressFormat.JPEG, 95, it) }
            recycle()
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val extraUris = mutableListOf<android.net.Uri>()
        repeat(mediaCount - 1) { index ->
            val extra = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "recovery-$token-$index.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/${albumName}_$index/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            extraUris += extra
            context.contentResolver.openInputStream(uri)!!.use { input ->
                context.contentResolver.openOutputStream(extra)!!.use { output -> input.copyTo(output) }
            }
            context.contentResolver.update(extra, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, state: Bundle?) {
                if (a !is MainActivity || a.intent.data != uri) return
                current = a
                val decor = a.window.decorView
                val listener = ViewTreeObserver.OnDrawListener {
                    var renderers = 0
                    var displayed = 0
                    fun visit(v: View, alpha: Float) {
                        if (v.visibility != View.VISIBLE) return
                        val opacity = alpha * v.alpha
                        if (v.width >= 400 && v.height >= 400 && opacity > .01f) {
                            if (v is ImageView) { renderers++; if (v.drawable != null) displayed++ }
                            if (v is SubsamplingScaleImageView) { renderers++; if (ready.getBoolean(v)) displayed++ }
                        }
                        if (v is ViewGroup) repeat(v.childCount) { visit(v.getChildAt(it), opacity) }
                    }
                    var gridNodes = emptyList<Map<String, Any>>()
                    var probeError = ""
                    if (gridMode) {
                        try {
                            gridNodes = gridDraw.read(decor)
                            renderers = gridNodes.size
                            displayed = gridNodes.count { it["ready"] == true }
                        } catch (error: Exception) { probeError = error.stackTraceToString() }
                    } else visit(a.window.decorView, 1f)
                    rows += mapOf("phase" to phase, "time" to SystemClock.uptimeMillis(), "activity" to a.hashCode(),
                        "focus" to a.hasWindowFocus(), "shown" to a.window.decorView.isShown,
                        "renderers" to renderers, "displayed" to displayed, "gridNodes" to gridNodes, "probeError" to probeError)
                }
                observers[a] = Observer(decor, listener)
                decor.viewTreeObserver.addOnDrawListener(listener)
            }
            override fun onActivityPostCreated(a: Activity, state: Bundle?) {
                if (a !is MainActivity || a.intent.data != uri) return
                // onActivityCreated is dispatched from super.onCreate: the app's
                // permission check comes afterwards. Suppress the unrelated dialog
                // after that check so the image window can actually gain focus.
                @Suppress("UNCHECKED_CAST")
                (MainActivity::class.java.getDeclaredField("showNotificationAccess").apply { isAccessible = true }
                    .get(a) as androidx.compose.runtime.MutableState<Boolean>).value = false
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) { stopped.add(a.hashCode()) }
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                observers.remove(a)?.let { observer ->
                    if (observer.decor.viewTreeObserver.isAlive) {
                        observer.decor.viewTreeObserver.removeOnDrawListener(observer.draw)
                    }
                }
            }
            override fun onActivityPostDestroyed(a: Activity) {
                if (missingOnRestore && a is MainActivity && a.intent.data == uri) {
                    // Active Glide resources are released during destruction; an
                    // earlier clearMemory cannot evict those still-in-use images.
                    Glide.get(context).clearMemory()
                }
            }
        }
        app.registerActivityLifecycleCallbacks(callback)
        val result = mutableListOf<Map<String, Any>>()
        try {
            val launch = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (source == "external") launch.setAction(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
            else launch.setAction(Intent.ACTION_MAIN).setData(uri)
            runner.startActivitySync(launch)
            dismissPrompt()
            if (source != "external") {
                lateinit var model: PhotosViewModel
                lateinit var labelModel: LabelsViewModel
                runner.runOnMainSync {
                    model = ViewModelProvider(current!!)[PhotosViewModel::class.java]
                    labelModel = ViewModelProvider(current!!)[LabelsViewModel::class.java]
                }
                val media = PhotosViewModel::class.java.getDeclaredField("repository")
                    .apply { isAccessible = true }.get(model) as MediaRepository
                val labels = LabelsViewModel::class.java.getDeclaredField("repository")
                    .apply { isAccessible = true }.get(labelModel) as LabelRepository
                runBlocking {
                    withTimeout(30_000) {
                        media.syncWhenMediaMutationsIdle(force = true)
                        media.allEntries.first { rows -> rows.any { it.contentId == android.content.ContentUris.parseId(uri) } }
                        if (source == "label") {
                            val id = labels.createLabel(labelName)
                            fixtureLabelId = id
                            fixtureLabels = labels
                            labels.applyEdits(setOf(android.content.ContentUris.parseId(uri)), LabelEditRequest(changes = mapOf(id to true)))
                            labelModel.labels.first { rows -> rows.any { it.label.id == id && it.count > 0 } }
                        }
                    }
                }
                clickText(if (source == "photos") "Photos" else "Albums")
                if (!gridMode) {
                    if (source == "label") clickText("Labels")
                    clickText(if (source == "label") labelName else albumName)
                    val tile = awaitSquareTile()
                    tap(tile.centerX().toFloat(), tile.centerY().toFloat())
                }
            }
            await { snapshotRows().any { it["displayed"] as Int > 0 } }
            awaitFocus()
            if (prepareProcess) {
                check(context.packageName == "com.pixel.gallery.memorypatched")
                val activity = checkNotNull(current)
                if (missingOnRestore) {
                    check(gridMode)
                    check(context.contentResolver.delete(uri, null, null) == 1)
                    lateinit var model: PhotosViewModel
                    runner.runOnMainSync { model = ViewModelProvider(activity)[PhotosViewModel::class.java] }
                    val media = PhotosViewModel::class.java.getDeclaredField("repository")
                        .apply { isAccessible = true }.get(model) as MediaRepository
                    runBlocking {
                        withTimeout(30_000) {
                            media.syncWhenMediaMutationsIdle(force = true)
                            media.allEntries.first { it.isEmpty() }
                        }
                    }
                }
                runner.uiAutomation.performGlobalAction(2)
                await { activity.hashCode() in stopped }
                val prepared = mapOf("prepared" to true, "source" to source, "uri" to uri.toString(),
                    "albumName" to albumName, "labelName" to labelName, "labelId" to fixtureLabelId,
                    "task" to activity.taskId, "pid" to android.os.Process.myPid(), "empty" to missingOnRestore,
                    "extraUris" to extraUris.map { it.toString() })
                File(context.cacheDir, "process-recovery-target.json").writeText(Gson().toJson(prepared))
                return Gson().toJson(listOf(prepared))
            }
            if (missingOnRestore) {
                check(context.contentResolver.delete(uri, null, null) == 1)
                check(runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() } }.isFailure) {
                    "Removed fixture can still be opened; load-failure precondition is invalid"
                }
                Glide.get(context).clearDiskCache()
            }
            repeat(if (missingOnRestore) 1 else 3) { index ->
                val old = checkNotNull(current)
                phase = "recreate-$index"
                val recoveryStarted = SystemClock.uptimeMillis()
                runner.runOnMainSync {
                    Glide.get(context).clearMemory()
                    old.recreate()
                }
                await { current !== old }
                dismissPrompt()
                await { snapshotRows().any {
                    it["phase"] == phase && it["activity"] == current!!.hashCode() &&
                        it["shown"] == true && (missingOnRestore || it["displayed"] as Int > 0)
                } }
                awaitFocus()
                runner.runOnMainSync { current!!.window.decorView.invalidate() }
                SystemClock.sleep(400)
                // A newly shown Activity can submit its first frame before focus arrives.
                // Filtering those frames out can turn a real blank into a false pass.
                val samples = snapshotRows().filter { it["phase"] == phase && it["activity"] == current!!.hashCode() && it["shown"] == true }
                check(samples.isNotEmpty()) { "No shown recovery draws; trial cannot establish frame continuity" }
                check(samples.none { it["probeError"] != "" }) { "Draw probe failed: ${samples.firstOrNull { it["probeError"] != "" }}" }
                val empty = samples.takeWhile { it["displayed"] == 0 }
                val firstReady = samples.firstOrNull { it["displayed"] as Int > 0 }
                result += mapOf("phase" to phase, "source" to source, "shownDraws" to samples.size,
                    "focusedDraws" to samples.count { it["focus"] == true }, "initialEmptyDraws" to empty.size,
                    "imageReady" to (firstReady != null), "missingExpected" to missingOnRestore,
                    "firstDrawAfterRecreateMs" to (samples.first()["time"] as Long) - recoveryStarted,
                    "emptySpanMs" to if (empty.isEmpty() || firstReady == null) 0 else (firstReady["time"] as Long) - (empty.first()["time"] as Long))
            }
            if (missingOnRestore) {
                runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                SystemClock.sleep(600)
            }
            return Gson().toJson(result)
        } finally {
            File(context.cacheDir, "viewer-recovery-frames.json").writeText(Gson().toJson(mapOf("result" to result, "frames" to snapshotRows())))
            app.unregisterActivityLifecycleCallbacks(callback)
            runner.runOnMainSync {
                observers.values.forEach { if (it.decor.viewTreeObserver.isAlive) it.decor.viewTreeObserver.removeOnDrawListener(it.draw) }
                observers.clear()
                if (!prepareProcess) current?.finish()
            }
            if (!prepareProcess) {
                context.contentResolver.delete(uri, null, null)
                extraUris.forEach { context.contentResolver.delete(it, null, null) }
                fixtureLabelId?.let { id -> runBlocking { fixtureLabels!!.deleteLabel(id) } }
            }
        }
    }
    private fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        runner.uiAutomation.clearCache()
        val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(runner.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun clickText(text: String) {
        var target: android.view.accessibility.AccessibilityNodeInfo? = null
        await {
            target = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
            target != null
        }
        var click = target!!
        while (!click.isClickable && click.parent != null) click = click.parent
        if (click.isClickable && click.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            SystemClock.sleep(500)
        } else {
            val bounds = Rect().also(target!!::getBoundsInScreen)
            tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
    }
    private fun awaitSquareTile(): Rect {
        var bounds: Rect? = null
        await {
            bounds = nodes().filter { it.isClickable && it.isVisibleToUser }
                .map { Rect().also(it::getBoundsInScreen) }
                .firstOrNull { it.width() > 120 && kotlin.math.abs(it.width() - it.height()) < 10 && it.top > 200 }
            bounds != null
        }
        return bounds!!
    }
    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                runner.sendPointerSync(this)
                recycle()
            }
        }
        SystemClock.sleep(500)
    }
    private fun await(f: () -> Boolean) {
        repeat(200) { if (f()) return; SystemClock.sleep(50) }
        error("Recovery listener timeout: ${snapshotRows().takeLast(3)}")
    }
    private fun snapshotRows(): List<Map<String, Any>> = synchronized(rows) { rows.toList() }
    private fun awaitFocus() = await {
        var focused = false
        runner.runOnMainSync { focused = current?.hasWindowFocus() == true }
        focused
    }
    private fun dismissPrompt() {
        repeat(12) {
            runner.uiAutomation.clearCache()
            val root = runner.uiAutomation.rootInActiveWindow
            val nodes = root?.findAccessibilityNodeInfosByText("Cancel").orEmpty() + root?.findAccessibilityNodeInfosByText("取消").orEmpty()
            nodes.firstOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(50)
        }
    }
}
