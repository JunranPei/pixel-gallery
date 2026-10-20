package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.media.MediaScannerConnection
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.Screen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Observes the existing viewer without changing resources, transforms, or loading policy. */
internal class ViewerPreviewGeometryChecks(private val instrumentation: Instrumentation, private val run: String = "baseline") {
    private val context = instrumentation.targetContext
    private val album = "PreviewSizeQA_20261020"
    private val output = File(context.getExternalFilesDir(null), if (run == "baseline") "preview-geometry" else "preview-geometry-$run").apply { mkdirs() }
    private val writer = Executors.newSingleThreadExecutor()
    private val seen = mutableSetOf<String>()
    private val saved = mutableSetOf<String>()
    private var phase = "album"
    private var down = 0L

    fun exercise(): String = runBlocking {
        val files = File("/sdcard/Pictures/$album").listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        check(files.size == 7) { "Expected seven unchanged user-photo copies" }
        val scan = CountDownLatch(files.size)
        MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null) { _, _ -> scan.countDown() }
        check(scan.await(45, TimeUnit.SECONDS))
        SettingsRepository(context).setLastSyncedGeneration(0)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val observer = ViewTreeObserver.OnPreDrawListener {
            fun visit(view: View) {
                if (view is ImageView && view.isShown && view.alpha > 0f && view.width > 100 && view.height > 100) {
                    val visible = Rect()
                    val drawable = view.drawable
                    if (drawable != null && view.getGlobalVisibleRect(visible)) {
                        val bitmap = (drawable as? BitmapDrawable)?.bitmap
                        val matrix = FloatArray(9).also(view.imageMatrix::getValues)
                        val mapped = RectF(drawable.bounds).also(view.imageMatrix::mapRect)
                        val key = "$phase:${System.identityHashCode(view)}:${System.identityHashCode(drawable)}:${view.width}:${view.height}:${matrix.toList()}:${view.alpha}"
                        if (seen.add(key)) {
                            record(JSONObject().put("event", "DRAWABLE").put("phase", phase)
                                .put("view", "${view.width}x${view.height}").put("visible", visible.toShortString())
                                .put("intrinsic", "${drawable.intrinsicWidth}x${drawable.intrinsicHeight}")
                                .put("bitmap", bitmap?.let { "${it.width}x${it.height}" }).put("density", bitmap?.density)
                                .put("matrix", matrix.toList().toString()).put("mapped", mapped.toShortString())
                                .put("alpha", view.alpha).put("scaleX", view.scaleX).put("scaleY", view.scaleY)
                                .put("translationX", view.translationX).put("translationY", view.translationY))
                            if (bitmap != null && bitmap.width <= 1024 && bitmap.height <= 1024) {
                                val name = "$phase-${bitmap.width}x${bitmap.height}"
                                if (saved.add(name)) {
                                    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    writer.execute { save(copy, "$name.png") }
                                }
                            }
                        }
                    }
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(activity.window.decorView)
            true
        }
        instrumentation.runOnMainSync {
            val model = ViewModelProvider(activity)[PhotosViewModel::class.java]
            activity.setContent { PixelGalleryTheme(dynamicColor = false, darkTheme = false) {
                MainScaffold(initialScreen = Screen.Photo(album), initialHomeTab = 1, photosViewModel = model)
            } }
            model.refresh()
            activity.window.decorView.viewTreeObserver.addOnPreDrawListener(observer)
        }
        try {
            SystemClock.sleep(1800)
            if (find("Cancel") != null) click("Cancel")
            await(album)
            for (round in 1..2) {
                for (index in files.indices) {
                    val tiles = awaitTiles()
                    val tile = tiles[index]
                    instrumentation.runOnMainSync { phase = "round$round-tile$index" }
                    record(JSONObject().put("event", "TAP").put("phase", phase).put("tile", tile.toShortString()))
                    tap(tile.centerX().toFloat(), tile.centerY().toFloat())
                    await("Share")
                    SystemClock.sleep(4500)
                    save(instrumentation.uiAutomation.takeScreenshot(), "$phase-final.png")
                    record(JSONObject().put("event", "BACK").put("phase", phase))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await(album)
                    SystemClock.sleep(400)
                }
            }
            val resolver = context.contentResolver
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("Pictures/$album/"), null)!!.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    record(JSONObject().put("event", "MEDIA").put("id", id).put("name", name))
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    for (size in listOf(200, 512)) save(resolver.loadThumbnail(uri, Size(size, size), null), "$name-system-$size.png")
                }
            }
            return@runBlocking "Seven original-byte copies opened from the real album grid twice; native drawable geometry, small preview pixels, final screenshots and system thumbnails saved to $output. Observer did not alter rendering."
        } finally {
            instrumentation.runOnMainSync { activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(observer) }
            writer.shutdown()
            check(writer.awaitTermination(20, TimeUnit.SECONDS))
            // Keep the actual app and sample album visible for manual inspection.
        }
    }

    private fun record(json: JSONObject) {
        json.put("uptimeMs", SystemClock.uptimeMillis())
        val line = json.toString()
        Log.i("PreviewGeometryQA", line)
        writer.execute { File(output, "events.jsonl").appendText("$line\n") }
    }
    private fun save(bitmap: Bitmap, name: String) {
        File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(label: String) = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun await(label: String) {
        repeat(100) { if (find(label) != null) return; SystemClock.sleep(150) }
        error("Missing $label: ${nodes().map { "${it.text}:${it.contentDescription}" }}")
    }
    private fun awaitTiles(): List<Rect> {
        repeat(70) {
            val result = nodes().filter { it.isClickable }.map { Rect().also(it::getBoundsInScreen) }
                .filter { it.width() > 120 && kotlin.math.abs(it.width() - it.height()) < 10 && it.top > 200 && it.bottom < 2200 }
                .distinctBy { it.toShortString() }.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
            if (result.size == 7) return result
            SystemClock.sleep(150)
        }
        error("Expected seven album tiles: ${nodes().map { "${it.className}:${it.text}:${it.isClickable}" }}")
    }
    private fun click(label: String) {
        val rect = Rect().also(find(label)!!::getBoundsInScreen)
        tap(rect.centerX().toFloat(), rect.centerY().toFloat())
    }
    private fun tap(x: Float, y: Float) {
        down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
    }
}
