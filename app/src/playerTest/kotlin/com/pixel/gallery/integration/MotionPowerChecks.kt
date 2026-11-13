package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pixel.gallery.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Real viewer input and passive window-frame callbacks; no recurring frame callback is installed. */
internal class MotionPowerChecks(private val runner: Instrumentation) {
    private val output = File(runner.targetContext.getExternalFilesDir(null), "animation-power-audit").apply { mkdirs() }
    private val frames = mutableListOf<JSONObject>()
    private val results = JSONArray()
    private lateinit var activity: MainActivity
    private var down = 0L

    private fun <T> main(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        runner.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Timeout: $label")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        runner.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(runner.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun image(): SubsamplingScaleImageView? = main {
        fun visit(view: View): SubsamplingScaleImageView? {
            if (view is SubsamplingScaleImageView && view.isShown && view.alpha > .9f) return view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it))?.let { result -> return result } }
            return null
        }
        visit(activity.window.decorView)
    }
    private fun zoom(): Float = main {
        image()?.snapshotViewState()?.let { return@main it.scale / it.baseFitScale }
        fun preview(view: View): android.widget.ImageView? {
            if (view is android.widget.ImageView && view.isShown && view.alpha > .9f && view.drawable != null) return view
            if (view is ViewGroup) repeat(view.childCount) { preview(view.getChildAt(it))?.let { result -> return result } }
            return null
        }
        preview(activity.window.decorView)?.scaleX ?: 0f
    }
    private fun event(action: Int, points: List<Pair<Float, Float>>) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        val properties = Array(points.size) { MotionEvent.PointerProperties().apply {
            id = it; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = Array(points.size) { MotionEvent.PointerCoords().apply {
            x = points[it].first; y = points[it].second; pressure = 1f; size = 1f
        } }
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).let {
            runner.sendPointerSync(it); it.recycle()
        }
    }
    private fun tap(x: Float, y: Float) {
        event(MotionEvent.ACTION_DOWN, listOf(x to y)); event(MotionEvent.ACTION_UP, listOf(x to y))
    }
    private fun pinch(factor: Float) {
        val cx = 540f; val cy = 1120f; val radius = 180f
        var points = listOf(cx - radius to cy, cx + radius to cy)
        event(MotionEvent.ACTION_DOWN, listOf(points.first()))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points)
        for (step in 1..24) {
            val r = radius * (1f + (factor - 1f) * step / 24f)
            points = listOf(cx - r to cy, cx + r to cy)
            event(MotionEvent.ACTION_MOVE, points); SystemClock.sleep(20)
        }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points)
        event(MotionEvent.ACTION_UP, listOf(points.first()))
    }
    private fun rotate(degrees: Float, cancel: Boolean = false) {
        val cx = 540f; val cy = 1120f; val radius = 185f
        var points = listOf(cx - radius to cy, cx + radius to cy)
        event(MotionEvent.ACTION_DOWN, listOf(points.first()))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points)
        for (step in 1..24) {
            val angle = Math.toRadians((degrees * step / 24f).toDouble())
            val dx = radius * cos(angle).toFloat(); val dy = radius * sin(angle).toFloat()
            points = listOf(cx - dx to cy - dy, cx + dx to cy + dy)
            event(MotionEvent.ACTION_MOVE, points); SystemClock.sleep(20)
        }
        if (cancel) event(MotionEvent.ACTION_CANCEL, points) else {
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points)
            event(MotionEvent.ACTION_UP, listOf(points.first()))
        }
    }
    private fun photoBounds(name: String): JSONObject {
        val bitmap = checkNotNull(runner.uiAutomation.takeScreenshot())
        var left = bitmap.width; var right = 0; var top = bitmap.height; var bottom = 0
        for (y in 100 until bitmap.height - 100 step 4) for (x in 0 until bitmap.width step 4) {
            val c = bitmap.getPixel(x, y)
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
            if (r < 100 && g > 120 && b > 120 && kotlin.math.abs(g - b) < 25) {
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        check(right > left && bottom > top) { "Fixture not visible: $name" }
        return JSONObject().put("left",left).put("right",right).put("top",top).put("bottom",bottom)
            .put("ratio",(right-left).toDouble()/(bottom-top))
    }
    private fun count() = synchronized(frames) { frames.size }
    private fun measure(name: String, action: () -> Unit, verify: () -> Unit = {}) {
        val startFrame = count(); val before = Process.getElapsedCpuTime(); val start = SystemClock.elapsedRealtime()
        // Include the viewer's intentional 3-second chrome auto-hide before measuring idle.
        action(); SystemClock.sleep(4500)
        val settledFrame = count(); val settledCpu = Process.getElapsedCpuTime()
        val idleStart = SystemClock.elapsedRealtime(); val idleCpu = Process.getElapsedCpuTime()
        SystemClock.sleep(3000)
        val idleEndCpu = Process.getElapsedCpuTime(); val idleEndFrame = count()
        val data = JSONObject().put("name",name).put("transition_cpu_ms",settledCpu-before)
            .put("transition_seconds",(idleStart-start)/1000.0).put("transition_frames",settledFrame-startFrame)
            .put("idle_seconds",(SystemClock.elapsedRealtime()-idleStart)/1000.0)
            .put("idle_new_frames",idleEndFrame-settledFrame).put("idle_cpu_ms",idleEndCpu-idleCpu)
            .put("zoom",zoom()).put("bounds",photoBounds(name))
        try { verify(); data.put("status","passed") } catch (e: Throwable) {
            data.put("status","failed").put("error",e.stackTraceToString()); throw e
        } finally { results.put(data); File(output,"results.json").writeText(results.toString(2)) }
    }
    fun exercise(): String {
        activity = runner.startActivitySync(Intent(runner.targetContext,MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/Pictures/ViewerOpeningQA20261113/portrait.jpg"),"image/jpeg")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) as MainActivity
        await("ready image or background prompt") { zoom()>0f || nodes().any { it.text?.toString()=="Cancel" } }
        nodes().firstOrNull { it.text?.toString()=="Cancel" }?.let {
            val rect=android.graphics.Rect().also(it::getBoundsInScreen); tap(rect.exactCenterX(),rect.exactCenterY())
        }
        await("ready preview or tile image") { zoom()>0f }
        // The startup prompt is posted after the image can already be visible.
        // Wait for and dismiss that real dialog before injecting media gestures.
        val promptDeadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < promptDeadline) {
            val cancel = nodes().firstOrNull { it.text?.toString()=="Cancel" }
            if (cancel != null) {
                if (!cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val rect=android.graphics.Rect().also(cancel::getBoundsInScreen)
                    tap(rect.exactCenterX(),rect.exactCenterY())
                }
                SystemClock.sleep(500)
            }
            SystemClock.sleep(200)
        }
        await("unobscured viewer") { main { activity.hasWindowFocus() } && nodes().none { it.text?.toString()=="Cancel" } }
        val listener = Window.OnFrameMetricsAvailableListener { _, value, dropped ->
            val row=JSONObject().put("time_ns",SystemClock.elapsedRealtimeNanos()).put("dropped_callbacks",dropped)
                .put("total_ns",value.getMetric(FrameMetrics.TOTAL_DURATION))
                .put("layout_ns",value.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
                .put("draw_ns",value.getMetric(FrameMetrics.DRAW_DURATION))
                .put("animation_ns",value.getMetric(FrameMetrics.ANIMATION_DURATION))
                .put("gpu_ns",value.getMetric(FrameMetrics.GPU_DURATION))
            synchronized(frames) { frames += row }
        }
        main { activity.window.addOnFrameMetricsAvailableListener(listener,Handler(Looper.getMainLooper())) }
        try {
            if (zoom()>1.1f) { tap(520f,1120f); SystemClock.sleep(80); tap(520f,1120f); SystemClock.sleep(1000) }
            val base=zoom(); check(base in .95f..1.05f) { "Expected fit before gestures: $base" }
            measure("double-tap-zoom",{ tap(520f,1120f); SystemClock.sleep(80); tap(520f,1120f) }) {
                // The fixture is 600 x 1200 and fits this 1080-wide viewport at 1.8x.
                // A double tap to original pixels should therefore use ~0.556 of fit.
                // Checking only that the scale changed accepts the 0.01 fallback bug.
                check(zoom() in 0.50f..0.65f) { "Double tap original-pixel scale: expected ~0.556, actual ${zoom()}" }
            }
            measure("double-tap-reset",{ tap(520f,1120f); SystemClock.sleep(80); tap(520f,1120f) }) {
                check(zoom() in .95f..1.05f) { "Double tap did not return to fit: ${zoom()}" }
            }
            measure("pinch-out",{pinch(1.6f)}) { check(zoom()>base*1.3f) }
            measure("pinch-reset",{pinch(1f/1.6f)}) { check(zoom() in .9f..1.1f) }
            measure("rotation-quarter-turn",{rotate(65f)}) {
                check(photoBounds("rotation-quarter-turn-verify").getDouble("ratio")>1.5)
            }
            measure("rotation-restore",{rotate(-65f)}) {
                check(photoBounds("rotation-restore-verify").getDouble("ratio")<.7)
            }
            measure("rotation-cancel",{rotate(25f,true)}) {
                check(photoBounds("rotation-cancel-verify").getDouble("ratio")<.7)
            }
            measure("dismiss-cancel",{
                event(MotionEvent.ACTION_DOWN,listOf(500f to 1000f))
                for (step in 1..12) { event(MotionEvent.ACTION_MOVE,listOf(510f to 1000f+step*15f)); SystemClock.sleep(20) }
                event(MotionEvent.ACTION_CANCEL,listOf(510f to 1180f))
            }) { check(main { activity.viewerRotationGesture!=null }) }
            return "Motion power gesture checks passed: ${results.length()}; evidence=$output"
        } finally {
            main { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            File(output,"frames.json").writeText(JSONArray(synchronized(frames) { frames.toList() }).toString(2))
        }
    }
}
