package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.Screen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.LabelsViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import java.io.File
import java.util.UUID

/** Actual MainScaffold, pager and production sheets; only invocation-owned fixtures. */
internal class ViewerSheetPagingChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val token = UUID.randomUUID().toString().take(8)
    private val album = "ViewerSheet_$token"
    private val output = File(context.filesDir, "viewer-sheet-paging/$token").apply { mkdirs() }
    private val report = mutableListOf<String>()
    private lateinit var activity: MainActivity
    private lateinit var photos: PhotosViewModel
    private lateinit var labels: LabelsViewModel
    private var downTime = 0L

    fun exercise(): String {
        val fixtures = (0..2).map { index ->
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "paging-$index.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$album/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(listOf(0xffaa2222.toInt(), 0xff22aa22.toInt(), 0xff2222aa.toInt())[index])
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            android.content.ContentUris.parseId(uri)
        }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            photos = ViewModelProvider(activity)[PhotosViewModel::class.java]
            labels = ViewModelProvider(activity)[LabelsViewModel::class.java]
            activity.setContent { PixelGalleryTheme(dynamicColor = false) {
                MainScaffold(initialScreen = Screen.Viewer(fixtures[1], Screen.ViewerSource.Album, albumName = album),
                    initialHomeTab = 1, photosViewModel = photos)
            } }
            photos.refresh()
        }
        try {
            waitUntil(60_000) {
                if (find("Background support") != null && find("Cancel") != null) click("Cancel")
                find("Info") != null
            }
            verifyCurrentName("paging-1.jpg")
            openLabels()
            swipe(-0.035f, 650)
            check(find("Edit labels") != null) { "Same-page drag dismissed the label sheet" }
            snapshot("labels-before-page-change")
            swipe(-0.70f, 500)
            waitUntil(5000) { panelName() != "paging-1.jpg" }
            check(find("Edit labels") != null)
            snapshot("labels-after-page-change")
            record("PASS label sheet remains open and displays the adjacent image name; same-page drag keeps it open")
            val adjacent = panelName()!!
            check(adjacent != "paging-1.jpg") { "Swipe did not change the image" }
            val adjacentIndex = adjacent.substringAfter("paging-").substringBefore('.').toInt()
            // The card text is separate from the viewer's Favourites icon below it.
            click("Favourites", textOnly = true)
            waitUntil(10_000) { labels.memberships.value.any { it.labelId == 1L && it.contentId == fixtures[adjacentIndex] } }
            check(labels.memberships.value.none { it.labelId == 1L && it.contentId == fixtures[1] })
            record("PASS retained label sheet edits the visible image; original image remains unlabelled")
            swipe(0.70f, 500)
            waitUntil(5000) { panelName() == "paging-1.jpg" }
            check(find("Edit labels") != null)
            record("PASS reverse swipe retains sheet and restores original image information")
            closeSheet("Edit labels")
            ensureControls()
            val display = context.resources.displayMetrics
            gesture(display.widthPixels * .5f, display.heightPixels * .91f,
                display.widthPixels * .5f, display.heightPixels * .68f, 450)
            await("Move")
            click("Move")
            await("Select album")
            snapshot("transfer-before-page-change")
            await("paging-1.jpg")
            nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, album) })
            SystemClock.sleep(400)
            await(album)
            click(album)
            check(actionEnabled("Copy"))
            click(album)
            check(!actionEnabled("Copy")) { "Second album tap did not deselect" }
            click(album)
            check(actionEnabled("Copy"))
            record("PASS album select -> deselect -> reselect; Copy disabled while deselected")
            swipe(-0.70f, 500)
            waitUntil(5000) { panelName() == adjacent }
            check(find("Select album") != null)
            check(!actionEnabled("Copy")) { "Old image's destination selection leaked to the new image" }
            snapshot("transfer-after-page-change")
            record("PASS destination half-sheet follows adjacent image and clears previous selection")
            check(fixtures.all { id -> context.contentResolver.query(
                Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!.use { it.moveToFirst() } })
            record("PASS all three original fixture media records retained; no transfer was submitted")
            return report.joinToString("\n") + "\nEvidence: $output"
        } catch (error: Throwable) {
            record("FAIL ${error.stackTraceToString()}")
            snapshot("failure")
            throw error
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun record(line: String) { report += line; File(output, "report.txt").appendText("$line\n") }
    private fun panelName() = nodes().mapNotNull { it.text?.toString() }.firstOrNull { it.matches(Regex("paging-[0-2]\\.jpg")) }
    private fun actionEnabled(name: String): Boolean {
        var node = requireNotNull(find(name))
        while (!node.isClickable && node.parent != null) node = node.parent
        return node.isEnabled && node.isClickable
    }
    private fun closeSheet(title: String) {
        repeat(3) {
            if (find(title) != null) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                SystemClock.sleep(400)
            }
        }
        check(find(title) == null)
    }
    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun currentName(): String {
        ensureControls()
        click("Info")
        await("Details")
        val name = nodes().mapNotNull { it.text?.toString() }.first { it.matches(Regex("paging-[0-2]\\.jpg")) }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitUntil(5000) { find("Details") == null }
        return name
    }
    private fun verifyCurrentName(name: String) { check(currentName() == name) }
    private fun ensureControls() {
        val display = context.resources.displayMetrics
        fun tapImage() = gesture(display.widthPixels * .5f, display.heightPixels * .35f,
            display.widthPixels * .5f, display.heightPixels * .35f, 40)
        SystemClock.sleep(250)
        if (find("Info") != null) {
            tapImage()
            waitUntil(7000) { find("Info") == null }
        }
        tapImage()
        await("Info")
        SystemClock.sleep(250)
    }
    private fun openLabels() {
        ensureControls()
        val bounds = Rect().also(find("Favourites")!!::getBoundsInScreen)
        gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(),
            bounds.centerX().toFloat(), bounds.centerY().toFloat(), 850)
        await("Edit labels")
    }
    private fun swipe(fraction: Float, duration: Long) {
        val display = context.resources.displayMetrics
        val start = if (fraction < 0) .85f else .15f
        gesture(display.widthPixels * start, display.heightPixels * .18f,
            display.widthPixels * (start + fraction), display.heightPixels * .18f, duration)
        SystemClock.sleep(500)
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(text: String, textOnly: Boolean = false) = nodes().firstOrNull {
        !it.isEditable && (it.text?.toString() == text || (!textOnly && it.contentDescription?.toString() == text))
    }
    private fun await(text: String) { waitUntil(15_000) { find(text) != null } }
    private fun waitUntil(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(120) }
        error("Timed out; visible: ${nodes().map { "${it.text}:${it.contentDescription}" }}")
    }
    private fun click(text: String, textOnly: Boolean = false) {
        val bounds = Rect().also(requireNotNull(find(text, textOnly))::getBoundsInScreen)
        gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(), bounds.centerX().toFloat(), bounds.centerY().toFloat(), 40)
        SystemClock.sleep(350)
    }
    private fun gesture(x: Float, y: Float, endX: Float, endY: Float, duration: Long) {
        downTime = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                x + (endX - x) * fraction, y + (endY - y) * fraction, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..16) { SystemClock.sleep(duration / 16); send(MotionEvent.ACTION_MOVE, step / 16f) }
        send(MotionEvent.ACTION_UP, 1f)
    }
}
