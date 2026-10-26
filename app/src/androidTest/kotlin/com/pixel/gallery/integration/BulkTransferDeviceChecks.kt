package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real MainScaffold, selection sheets, foreground queue, MediaStore and files. */
internal class BulkTransferDeviceChecks(private val instrumentation: Instrumentation, token: String?) {
    private val context = instrumentation.targetContext
    private val run = token ?: UUID.randomUUID().toString().take(8)
    private val output = File(context.filesDir, "bulk1000/$run").apply { mkdirs() }
    private val root = File(Environment.getExternalStorageDirectory(), "Pictures/GalleryBulk_$run")
    private val source = File(root, "B1000_${run}_S")
    private val moved = File(root, "B1000_${run}_M")
    private val sorted = File(root, "B1000_${run}_Q")
    private val resolver = context.contentResolver
    private val lines = mutableListOf<String>()
    private var downTime = 0L
    private lateinit var activity: MainActivity
    private lateinit var photos: PhotosViewModel
    private var epoch = 0

    fun exercise(): String {
        check(run.matches(Regex("[a-f0-9]{8}")))
        record("RUN $run sdk=${android.os.Build.VERSION.SDK_INT} pid=${android.os.Process.myPid()}")
        val manifest = File(output, "expected.json")
        val expected = if (manifest.exists()) JSONArray(manifest.readText()) else createFixtures().also { manifest.writeText(it.toString()) }
        check(expected.length() == 1000)
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync { photos = ViewModelProvider(activity)[PhotosViewModel::class.java] }
        try {
            if (count(source) > 0) {
                openAlbum(source.name)
                selectThousand()
                click("Move or copy to")
                await("Search albums")
                search(moved.name)
                click(moved.name)
                click("Move")
                await("Move 1000 items?")
                snapshot("move-confirmation")
                click("Move")
                waitForBatch(source, moved, "move")
            }
            verify(expected, moved, source, "move")
            deleteSeed(moved)
            if (count(moved) > 0) {
                openAlbum(moved.name)
                selectThousand()
                click("More")
                click("Quick sort")
                await("Choose albums")
                if (find("Search albums") == null) {
                    val header = Rect().also(find("Choose albums")!!::getBoundsInScreen)
                    swipe(header.centerX().toFloat(), header.centerY().toFloat(), context.resources.displayMetrics.heightPixels * .35f)
                }
                await("Search albums")
                search(sorted.name)
                click(sorted.name)
                val capsule = "${sorted.name}, ${sorted.path}"
                await(capsule)
                snapshot("quick-sort-selected")
                click(capsule)
                waitForBatch(moved, sorted, "quick-sort")
            }
            verify(expected, sorted, moved, "quick-sort")
            record("PASS both 1000-file batches completed; source absence, hashes, modified times, MediaStore counts checked. Fixtures retained at ${root.path}")
            snapshot("finished")
            return lines.joinToString("\n")
        } catch (error: Throwable) {
            record("FAIL ${error.stackTraceToString()}")
            snapshot("failure")
            throw error
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun createFixtures(): JSONArray {
        check(!root.exists())
        root.mkdirs()
        val random = java.util.Random(1000)
        val bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(512 * 384) { 0xff000000.toInt() or random.nextInt(0xffffff) }
        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 384)
        val bytes = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray()
        bitmap.recycle()
        val expected = JSONArray()
        fun insert(folder: File, name: String): Pair<Uri, File> {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/${root.name}/${folder.name}/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Could not create fixture")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            fun actualFile() = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
                check(it.moveToFirst()); File(it.getString(0))
            }
            val file = actualFile()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:25 12:00:00")
                setAttribute(ExifInterface.TAG_USER_COMMENT, "$run/$name")
                saveAttributes()
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri to actualFile()
        }
        repeat(1000) { index ->
            val (_, file) = insert(source, "bulk-${index.toString().padStart(4, '0')}.jpg")
            expected.put(JSONObject().put("name", file.name).put("hash", hash(file)).put("modified", file.lastModified()))
            if (index % 100 == 99) record("Created ${index + 1}/1000")
        }
        listOf(moved, sorted).forEach { insert(it, "seed.jpg") }
        record("Fixtures ready: ${expected.length()} images, ${source.listFiles()!!.sumOf { it.length() }} bytes")
        return expected
    }

    private fun openAlbum(name: String) {
        instrumentation.runOnMainSync {
            epoch++
            activity.setContent { PixelGalleryTheme { androidx.compose.runtime.key(epoch) {
                MainScaffold(initialHomeTab = 1, photosViewModel = photos)
            } } }
            photos.refresh()
        }
        waitUntil(90_000) {
            find("Background support")?.let { find("Cancel")?.let { click("Cancel") } }
            find(name) != null
        }
        click(name)
        await("More")
    }

    private fun selectThousand() {
        val display = context.resources.displayMetrics
        val x = display.widthPixels / 6f
        val y = display.heightPixels * .23f
        event(MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(750)
        event(MotionEvent.ACTION_UP, x, y)
        await("Select all")
        click("Select all")
        await("1000 selected")
        snapshot("selected-1000")
    }

    private fun waitForBatch(from: File, to: File, phase: String) {
        val start = SystemClock.elapsedRealtime()
        var nextProgress = 0L
        var strokes = 0
        waitUntil(20 * 60_000L) {
            val completed = count(to)
            val now = SystemClock.elapsedRealtime()
            if (now >= nextProgress) {
                record("$phase $completed/1000 elapsedMs=${now - start} heap=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
                nextProgress = now + 5000
                // Touch only the exposed source grid, above the selection sheet.
                if (completed < 1000) {
                    val display = context.resources.displayMetrics
                    swipe(display.widthPixels * .45f, display.heightPixels * .23f,
                        display.heightPixels * (if (strokes++ % 2 == 0) .15f else .31f))
                }
            }
            completed == 1000 && count(from) == 0
        }
        SystemClock.sleep(5000)
        instrumentation.waitForIdleSync()
        snapshot("$phase-completed")
        record("$phase completed elapsedMs=${SystemClock.elapsedRealtime() - start}; UI still responds after batch refresh")
    }

    private fun verify(expected: JSONArray, target: File, previous: File, phase: String) {
        check(count(previous) == 0 && count(target) == 1000)
        repeat(expected.length()) { index ->
            val item = expected.getJSONObject(index)
            val file = File(target, item.getString("name"))
            check(file.isFile && hash(file) == item.getString("hash")) { "$phase content mismatch: ${file.name}" }
            check(file.lastModified() == item.getLong("modified")) { "$phase timestamp mismatch: ${file.name}" }
        }
        resolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("Pictures/${root.name}/${target.name}/", "bulk-%"), null)!!.use { check(it.count == 1000) }
        check(root.walkTopDown().filter { it.isFile }.none { it.name.endsWith(".tmp") || it.name.endsWith(".partial") })
        record("PASS $phase: independently checked all 1000 SHA-256 hashes, modified times, source removal and MediaStore rows")
    }

    private fun deleteSeed(folder: File) {
        resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(File(folder, "seed.jpg").path))
    }
    private fun count(folder: File) = folder.listFiles().orEmpty().count { it.name.startsWith("bulk-") && it.extension == "jpg" }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun record(line: String) { lines += line; File(output, "report.txt").appendText("$line\n"); android.util.Log.i("Bulk1000", line) }
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) { if (node == null) return; result += node; repeat(node.childCount) { visit(node.getChild(it)) } }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(text: String) = nodes().firstOrNull { !it.isEditable && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(text: String) { waitUntil(30_000) { find(text) != null } }
    private fun waitUntil(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(150) }
        error("Timed out: ${nodes().map { "${it.text}:${it.contentDescription}" }}")
    }
    private fun click(text: String) {
        await(text)
        var node = find(text)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        val bounds = Rect().also(node::getBoundsInScreen)
        event(MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat())
        event(MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat())
        SystemClock.sleep(400)
    }
    private fun search(text: String) {
        val node = nodes().first { it.isEditable }
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
        SystemClock.sleep(600)
        instrumentation.runOnMainSync {
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        SystemClock.sleep(350)
    }
    private fun swipe(x: Float, from: Float, to: Float) {
        event(MotionEvent.ACTION_DOWN, x, from)
        repeat(8) { i -> event(MotionEvent.ACTION_MOVE, x, from + (to - from) * (i + 1) / 8); SystemClock.sleep(20) }
        event(MotionEvent.ACTION_UP, x, to)
    }
    private fun event(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(this); recycle()
        }
    }
    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.apply {
            File(output, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}
