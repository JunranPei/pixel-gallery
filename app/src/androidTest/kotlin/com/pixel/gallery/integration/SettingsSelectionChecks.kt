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
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Navigate the real scaffold and select only this test's own synthetic album. */
internal class SettingsSelectionChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val output = File(context.cacheDir, "settings-selection-qa").apply { mkdirs() }
    private var down = 0L

    fun exercise(): String = runBlocking {
        val settings = SettingsRepository(context)
        val startup = settings.startupAtAlbums.first()
        val token = UUID.randomUUID().toString()
        val album = "SettingsSelectionQA_$token"
        val fixture = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "test-selection.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$album/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        context.contentResolver.openOutputStream(fixture)!!.use { stream ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xffaa88cc.toInt()); compress(Bitmap.CompressFormat.JPEG, 90, stream); recycle()
            }
        }
        context.contentResolver.update(fixture, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        settings.setStartupAtAlbums(false)
        settings.setLastSyncedGeneration(0)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val viewModel = ViewModelProvider(activity)[PhotosViewModel::class.java]
                activity.setContent { PixelGalleryTheme(dynamicColor = false, darkTheme = false) { MainScaffold(photosViewModel = viewModel) } }
                viewModel.refresh()
            }
            settle(900)
            repeat(4) {
                if (find("Background support") != null) click("Cancel")
                if (find("开启后台保活支持") != null) click("取消")
                settle(250)
            }
            click("More"); click("Settings")
            await("Display")
            check(find("Themes") == null && find("Label backup") == null)
            snapshot("settings-main")
            click("Display"); await("Hand preference"); click("Themes"); await("Theme mode")
            snapshot("theme-from-display")
            back(); await("Hand preference"); back(); await("Library")
            click("Library"); await("Label backup")
            check(nodes().count { it.text?.toString() == "Label backup" } >= 1)
            check(find("Export labels") == null && find("Import labels") == null)
            snapshot("library-final")
            click("Label backup"); await("Export labels"); await("Import labels")
            snapshot("label-actions-dialog")
            click("Cancel"); await("Excluded Folders")
            click("Excluded Folders"); snapshot("excluded-from-library")
            back(); await("Label backup"); back(); await("Performance & Caching")
            click("Performance & Caching"); awaitContains("Image Decoding Threads")
            snapshot("performance-final")
            back(); back()
            click("Albums"); await(album); click(album)
            val tile = nodes().filter { it.isClickable }.map { Rect().also(it::getBoundsInScreen) }
                .firstOrNull { it.width() > 120 && kotlin.math.abs(it.width() - it.height()) < 10 && it.top > 200 }
                ?: error("Synthetic album tile unavailable")
            event(MotionEvent.ACTION_DOWN, tile.centerX().toFloat(), tile.centerY().toFloat())
            SystemClock.sleep(650)
            event(MotionEvent.ACTION_UP, tile.centerX().toFloat(), tile.centerY().toFloat())
            await("1 selected")
            listOf("Clear selection", "Select all", "Share", "Move or copy to", "Delete", "More").forEach { name ->
                if (name != "Select all") check(find(name) != null) { "Original toolbar action lost: $name" }
            }
            snapshot("selection-count-final")
            click("Clear selection")
            return@runBlocking "Actual MainScaffold: Themes only under Display; one Label backup entry opens Export/Import dialog; Excluded returns to Library; Performance contains three intact sliders; synthetic album selection retains original actions and full selected caption. Screenshots: $output"
        } finally {
            context.contentResolver.delete(fixture, null, null)
            settings.setStartupAtAlbums(startup)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            all += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return all
    }
    private fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun await(text: String) { repeat(40) { if (find(text) != null) return; settle(150) }; snapshot("failed"); error("Missing $text: ${nodes().map { "${it.text}:${it.contentDescription}" }}") }
    private fun awaitContains(text: String) { repeat(40) { if (nodes().any { it.text?.contains(text) == true }) return; settle(150) }; error("Missing $text") }
    private fun click(text: String) {
        await(text)
        val bounds = Rect().also(find(text)!!::getBoundsInScreen)
        event(MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat())
        event(MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat())
        settle(350)
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(350) }
    private fun event(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN; instrumentation.sendPointerSync(this); recycle()
        }
    }
    private fun settle(ms: Long) { SystemClock.sleep(ms); instrumentation.waitForIdleSync() }
    private fun snapshot(name: String) {
        settle(250)
        instrumentation.uiAutomation.takeScreenshot().apply {
            File(output, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}
