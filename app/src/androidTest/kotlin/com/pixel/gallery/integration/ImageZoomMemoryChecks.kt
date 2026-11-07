package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.gson.Gson
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewer.SimpleSubsamplingImageView
import com.pixel.gallery.ui.viewer.ViewerTransformStateStore
import com.pixel.gallery.ui.viewer.rememberedImageTransforms
import com.pixel.gallery.data.repository.VideoSettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Production transform store and real pinch gestures on the production image renderer. */
internal class ImageZoomMemoryChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString().take(8)
    private val output = File(context.filesDir, "image-zoom-memory/$token").apply { mkdirs() }
    private val gson = Gson()
    private val preferences = context.getSharedPreferences("zoom-memory-$token", 0)
    private lateinit var activity: MainActivity
    private lateinit var store: ViewerTransformStateStore
    private var generation = 0
    private var down = 0L
    private val report = mutableListOf<String>()
    private fun record(line: String) { report += line; File(output, "report.txt").appendText("$line\n") }
    private fun freshStore() = ViewerTransformStateStore(
        readSaved = { name -> preferences.getString(name, null)?.let { gson.fromJson(it, SubsamplingScaleImageView.ViewState::class.java) } },
        writeSaved = { name, state -> preferences.edit().putString(name, gson.toJson(state)).commit(); Unit },
    )
    fun exercise(): String {
        val probe = freshStore()
        fun state(scale: Float) = SubsamplingScaleImageView.ViewState(scale, 1f, PointF(1024f, 768f), 2048, 1536, 0.0)
        probe.save("probe", state(3f), "composition-dispose")
        probe.save("probe", state(1f), "preview-gesture")
        check(probe.get("probe") == null) { "Fit reset resurrected persisted zoom" }
        probe.checkpoint()
        check(freshStore().get("probe") == null)
        probe.save("probe", state(.6f), "composition-dispose")
        check(abs(freshStore().get("probe")!!.scale - .6f) < .001f)
        probe.save("probe", state(3f), "preview-gesture")
        probe.save("probe", state(1.4f), "composition-dispose")
        check(abs(freshStore().get("probe")!!.scale - 1.4f) < .001f)
        val restored = ViewerTransformStateStore.fromSaveableSnapshot(probe.saveableSnapshot())
        check(abs(restored.get("probe")!!.scale - 1.4f) < .001f)
        record("PASS store: zoom -> fit clears stale disk zoom; below-fit and reduced above-fit replace old size; saved-state reload")

        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(output, "repository").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$token-$name", mode)
        }
        val repository = VideoSettingsRepository(isolated)
        runBlocking { repository.imageSettings.first { it != null } }
        fun reopened() = rememberedImageTransforms(repository, ViewerTransformStateStore()) { true }
        var session = reopened()
        for (scale in listOf(3f, 1.4f, 1f, .65f)) {
            session.save("exit-and-reopen", state(scale), "preview-gesture")
            session.checkpoint()
            session = reopened()
            val actual = session.get("exit-and-reopen")?.scale ?: 1f
            check(abs(actual - scale) < .001f) { "Real repository reopen expected=$scale actual=$actual" }
            check(abs(repository.zoom(false, "exit-and-reopen")!!.scale - scale) < .001f)
        }
        record("PASS real settings repository: fresh viewer session reads final 1.4 / fit / 0.65 sizes from persistent history")

        val file = File(output, "fixture.jpg")
        val bitmap = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff33aa66.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val memoryKey = "${file.path}:0"
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun show() = instrumentation.runOnMainSync {
            val currentStore = store
            val epoch = ++generation
            activity.setContent { PixelGalleryTheme {
                key(epoch) { Box(Modifier.fillMaxSize()) {
                    SimpleSubsamplingImageView(file.toURI().toString(), file.path,
                        sourceWidth = 2048, sourceHeight = 1536,
                        transformStateStore = currentStore, modifier = Modifier.fillMaxSize())
                } }
            } }
        }
        store = reopened()
        show()
        SystemClock.sleep(2200)
        try {
            for (target in listOf(2.4f, 1.4f, 1f, .65f)) {
                // ScaleGestureDetector deliberately ignores initial touch slop.
                // Correct with another real pinch instead of assuming ideal event ratios.
                repeat(3) {
                    val current = store.get(memoryKey)?.let { it.scale / it.baseFitScale } ?: 1f
                    if (abs(current - target) >= .08f) {
                        pinch(target / current)
                        SystemClock.sleep(1400)
                    }
                }
                val actual = store.get(memoryKey)?.let { it.scale / it.baseFitScale } ?: 1f
                check(abs(actual - target) < .10f) { "Pinch target=$target actual=$actual" }
                store.checkpoint()
                store = reopened()
                show()
                SystemClock.sleep(1600)
                val saved = store.get(memoryKey)?.let { it.scale / it.baseFitScale } ?: 1f
                check(abs(saved - actual) < .025f) { "Reload target=$actual actual=$saved" }
                var visibleScale = Float.NaN
                instrumentation.runOnMainSync {
                    val views = mutableListOf<View>()
                    fun visit(view: View) { views += view; if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) } }
                    visit(activity.window.decorView)
                    val tiled = views.filterIsInstance<SubsamplingScaleImageView>().firstOrNull { it.isShown && it.alpha > .9f }
                    visibleScale = tiled?.snapshotViewState()?.let { it.scale / it.baseFitScale }
                        ?: views.filterIsInstance<ImageView>().first { it.isShown && it.alpha > .9f && it.drawable != null }.scaleX
                }
                check(abs(visibleScale - actual) < .10f) { "Rendered target=$actual actual=$visibleScale" }
                instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                    File(output, "restored-$target.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle()
                }
                record("PASS pinch and recreate target=$target saved=$saved rendered=$visibleScale")
            }
            return report.joinToString("\n") + "\nEvidence: $output"
        } catch (error: Throwable) { record("FAIL ${error.stackTraceToString()}"); throw error }
        finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun pinch(factor: Float) {
        val display = context.resources.displayMetrics
        val cx = display.widthPixels / 2f
        val cy = display.heightPixels / 2f
        val radius = display.widthPixels * .30f / maxOf(1f, factor)
        event(MotionEvent.ACTION_DOWN, listOf(cx - radius to cy))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(cx - radius to cy, cx + radius to cy))
        var points = emptyList<Pair<Float, Float>>()
        for (step in 1..24) {
            val r = radius * (1f + (factor - 1f) * step / 24f)
            points = listOf(cx - r to cy, cx + r to cy)
            event(MotionEvent.ACTION_MOVE, points); SystemClock.sleep(22)
        }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points)
        event(MotionEvent.ACTION_UP, listOf(points.first()))
    }
    private fun event(action: Int, points: List<Pair<Float, Float>>) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        val properties = Array(points.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(points.size) { MotionEvent.PointerCoords().apply { x = points[it].first; y = points[it].second; pressure = 1f; size = 1f } }
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).let {
            instrumentation.sendPointerSync(it); it.recycle()
        }
    }
}
