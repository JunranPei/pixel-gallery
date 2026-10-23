package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.LabelsViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Exercises the real album routes; only UUID-scoped fixtures are changed. */
internal class AlbumSelectionChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val output = File(context.cacheDir, "album-selection-qa").apply { mkdirs() }
    private var down = 0L
    private var heldCount = 0

    fun exercise(): String = runBlocking {
        val settings = SettingsRepository(context)
        val originalSort = settings.albumSortOrder.first()
        val token = UUID.randomUUID().toString().take(8)
        val names = listOf("AASelect_${token}_A", "AASelect_${token}_B")
        val fixtures = names.map { name ->
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "selection.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$name/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff448866.toInt()) }
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var labelModel: LabelsViewModel
        var modelsReady = false
        try {
            instrumentation.runOnMainSync {
                val photos = ViewModelProvider(activity)[PhotosViewModel::class.java]
                labelModel = ViewModelProvider(activity)[LabelsViewModel::class.java]
                modelsReady = true
                activity.setContent { PixelGalleryTheme(dynamicColor = false, darkTheme = false) {
                    MainScaffold(initialHomeTab = 1, photosViewModel = photos)
                } }
                photos.setAlbumSortOrder(com.pixel.gallery.model.AlbumSortOrder.NAME_ASC)
                photos.refresh()
            }
            settle(1200)
            if (find("Background support") != null) click("Cancel")
            await(names[0]); await(names[1])
            snapshot("albums-normal")
            longPress(names[0])
            await("1 selected")
            listOf("Rename album", "Hide Album", "Exclude Album", "Clear selection").forEach(::await)
            check(find("Select all") == null)
            snapshot("album-single")
            click("Rename album"); await("Album name"); click("Cancel")
            click(names[1]); await("2 selected")
            check(find("Rename album") == null)
            await("Hide Album"); await("Exclude Album")
            snapshot("album-multiple")
            back(); await("Pixel Gallery"); check(find("Clear selection") == null)
            longPress(names[0]); await("1 selected"); click(names[0]); await("Pixel Gallery")
            check(find("Clear selection") == null)
            names.forEach { name ->
                instrumentation.runOnMainSync { labelModel.createLabel(name) }
                repeat(60) { if (labelModel.labelEntries.value.none { it.name == name }) settle(100) }
                check(labelModel.labelEntries.value.any { it.name == name })
            }
            click("Labels"); await(names[0]); await(names[1])
            snapshot("labels-normal")
            longPress(names[0]); await("1 selected"); await("Rename label"); await("Delete")
            check(find("Select all") == null && find("Hide Album") == null)
            snapshot("label-single")
            click("Rename label"); await("Label name"); click("Cancel")
            click(names[1]); await("2 selected"); check(find("Rename label") == null)
            snapshot("label-multiple")
            click("Delete"); await("Delete 2 labels?"); click("Cancel"); await("2 selected")
            click("Delete"); await("Delete 2 labels?"); click("Delete label")
            repeat(60) { if (labelModel.labelEntries.value.any { it.name in names }) settle(100) }
            check(labelModel.labelEntries.value.none { it.name in names }) { "Batch delete did not remove both labels" }
            check(fixtures.all { uri -> context.contentResolver.openInputStream(uri)!!.use { it.read() >= 0 } })
            await("Favourites"); longPress("Favourites"); check(find("Clear selection") == null)
            snapshot("labels-system-protected")
            return@runBlocking "PASS: real album and label long presses select; single selection has rename; multiple selection hides rename; filesystem albums retain hide/exclude, labels retain delete; Back and last-item deselect exit; no select-all or old menu; rename/delete cancel preserves selection; confirmed batch delete removes both fixture labels and preserves media; Favourites protected. Screenshots: $output"
        } finally {
            if (modelsReady) {
                val remaining = labelModel.labelEntries.value.filter { it.name in names }.map { it.id }.toSet()
                if (remaining.isNotEmpty()) instrumentation.runOnMainSync { labelModel.deleteLabels(remaining) }
            }
            fixtures.forEach { context.contentResolver.delete(it, null, null) }
            settings.setAlbumSortOrder(originalSort)
            instrumentation.runOnMainSync { activity.finish() }
        }
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
    private fun find(name: String) = nodes().firstOrNull { it.text?.toString() == name || it.contentDescription?.toString() == name }
    private fun await(name: String) {
        repeat(60) { if (find(name) != null) return; settle(150) }
        snapshot("failed"); error("Missing $name: ${nodes().map { "${it.text}:${it.contentDescription}" }}")
    }
    private fun click(name: String) = press(name, false)
    private fun longPress(name: String) = press(name, true)
    private fun press(name: String, long: Boolean) {
        await(name)
        var node = find(name)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        val rect = Rect().also(node::getBoundsInScreen)
        event(MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat())
        if (long) { SystemClock.sleep(700); snapshot("held-${++heldCount}-${name.take(24)}") }
        event(MotionEvent.ACTION_UP, rect.centerX().toFloat(), rect.centerY().toFloat())
        settle(450)
    }
    private fun event(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(this); recycle()
        }
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(400) }
    private fun settle(ms: Long) { SystemClock.sleep(ms); instrumentation.waitForIdleSync() }
    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().apply {
            File(output, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}
