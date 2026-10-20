package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.Screen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Persistent sample media stays available for manual QA; no file is moved or deleted. */
internal class AlbumRegressionChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val album = "QA 01 Small"
    private val output = File(context.cacheDir, "album-regression-qa").apply { mkdirs() }
    private var down = 0L

    fun exercise(): String = runBlocking {
        val files = File("/sdcard/Pictures/GalleryQA20261020").walkTopDown().filter { it.isFile }.toList()
        check(files.size >= 300) { "Expanded media set is missing: ${files.size}" }
        val scanned = CountDownLatch(files.size)
        MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null) { _, _ -> scanned.countDown() }
        check(scanned.await(45, TimeUnit.SECONDS)) { "Media scan did not finish" }
        val settings = SettingsRepository(context)
        settings.setLastSyncedGeneration(0)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val viewModel = ViewModelProvider(activity)[PhotosViewModel::class.java]
                activity.setContent { PixelGalleryTheme(dynamicColor = false, darkTheme = false) {
                    MainScaffold(initialScreen = Screen.Photo(album), initialHomeTab = 1, photosViewModel = viewModel)
                } }
                viewModel.refresh()
            }
            settle(1200)
            if (find("Background support") != null) click("Cancel")
            await(album)
            val tile = awaitTile()
            event(MotionEvent.ACTION_DOWN, tile.centerX().toFloat(), tile.centerY().toFloat())
            SystemClock.sleep(650)
            event(MotionEvent.ACTION_UP, tile.centerX().toFloat(), tile.centerY().toFloat())
            await("1 selected")
            click("Select all"); await("4 selected")
            click("Deselect all"); await("0 selected")
            check(find("Clear selection") != null && find("Select all") != null)
            snapshot("empty-selection-retained")
            tap(tile.centerX().toFloat(), tile.centerY().toFloat()); await("1 selected")
            click("Select all"); await("4 selected")
            click("Deselect all"); await("0 selected")
            click("Select all"); await("4 selected")
            click("Deselect all"); await("0 selected")
            back(); await(album); check(find("Clear selection") == null)
            // Explicit close also exits an empty selection and restores the album toolbar.
            event(MotionEvent.ACTION_DOWN, tile.centerX().toFloat(), tile.centerY().toFloat())
            SystemClock.sleep(650)
            event(MotionEvent.ACTION_UP, tile.centerX().toFloat(), tile.centerY().toFloat())
            await("1 selected"); click("Select all"); click("Deselect all"); await("0 selected")
            click("Clear selection"); await(album); check(find("Clear selection") == null)

            // A collapsed panel must disappear when the album's toolbar back button is tapped.
            click("More"); click("Quick sort"); await("Choose albums")
            snapshot("quick-sort-in-album-collapsed")
            click("Back"); awaitAbsent("Choose albums")
            check(find("Albums") != null)
            tap(540f, 700f); awaitAbsent("Choose albums")
            snapshot("album-exit-no-panel")
            reopenAlbum()
            click("More"); await("Quick sort"); click("Quick sort"); await("Choose albums")
            val header = Rect().also(find("Choose albums")!!::getBoundsInScreen)
            drag(540f, header.centerY().toFloat(), 540f, 700f)
            await("New album")
            snapshot("quick-sort-in-album-expanded")
            click("Back"); awaitAbsent("Choose albums")
            snapshot("expanded-album-exit-no-panel")
            reopenAlbum()
            click("More"); await("Quick sort"); back()
            snapshot("reopened-album-no-quick-sort")
            // Normal viewer navigation remains intact after closing selection and sorting.
            val reopenedTile = awaitTile()
            tap(reopenedTile.centerX().toFloat(), reopenedTile.centerY().toFloat())
            await("Share"); check(find("Clear selection") == null)
            back(); await(album)
            check(find("Choose albums") == null)
            click("Back"); reopenAlbum("QA 03 Large")
            val largeTile = awaitTile()
            event(MotionEvent.ACTION_DOWN, largeTile.centerX().toFloat(), largeTile.centerY().toFloat())
            SystemClock.sleep(650)
            event(MotionEvent.ACTION_UP, largeTile.centerX().toFloat(), largeTile.centerY().toFloat())
            await("1 selected"); click("Select all"); await("98 selected")
            snapshot("large-album-all-selected")
            click("Deselect all"); await("0 selected")
            snapshot("large-album-empty-selection")
            click("Clear selection"); click("Back")
            snapshot("expanded-sample-albums")
            return@runBlocking "${files.size} persistent files scanned across 24 albums. Full album selection -> 0 selected retains toolbar; tap selects instead of opening viewer; repeated select/clear works; Back and Close exit empty selection. Collapsed and expanded Quick Sort close on album exit; Home blank taps leave no panel; reopening offers Quick sort again; normal viewer return remains intact. Mixed large album: all 98 files select and clear with mode retained. Screenshots: $output"
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun reopenAlbum(name: String = album) {
        // Start from the top because the previous search may have scrolled this retained grid.
        repeat(5) { if (find(name) == null) drag(820f, 700f, 820f, 1700f) }
        repeat(15) {
            if (find(name) != null) { click(name); await("Back"); return }
            drag(820f, 1700f, 820f, 700f)
        }
        error("Sample album not found on Home: $name")
    }
    private fun awaitTile(): Rect {
        repeat(50) {
            val tile = nodes().filter { it.isClickable }.map { Rect().also(it::getBoundsInScreen) }
                .firstOrNull { it.width() > 120 && kotlin.math.abs(it.width() - it.height()) < 10 && it.top > 200 && it.bottom < 1900 }
            if (tile != null) return tile
            settle(150)
        }
        error("Album tiles did not load")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(label: String) = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun await(label: String) {
        repeat(60) { if (find(label) != null) return; settle(150) }
        snapshot("failed"); error("Missing $label: ${nodes().map { "${it.text}:${it.contentDescription}" }}")
    }
    private fun awaitAbsent(label: String) {
        repeat(40) { if (find(label) == null) { settle(250); check(find(label) == null); return }; settle(100) }
        error("Unexpected remaining $label")
    }
    private fun click(label: String) { await(label); val r = Rect().also(find(label)!!::getBoundsInScreen); tap(r.centerX().toFloat(), r.centerY().toFloat()) }
    private fun tap(x: Float, y: Float) { event(MotionEvent.ACTION_DOWN, x, y); event(MotionEvent.ACTION_UP, x, y); settle(350) }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(350) }
    private fun drag(x: Float, y: Float, toX: Float, toY: Float) {
        event(MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..12) { SystemClock.sleep(25); event(MotionEvent.ACTION_MOVE, x+(toX-x)*step/12, y+(toY-y)*step/12) }
        event(MotionEvent.ACTION_UP, toX, toY); settle(450)
    }
    private fun event(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN; instrumentation.sendPointerSync(this); recycle()
        }
    }
    private fun settle(ms: Long) { SystemClock.sleep(ms); instrumentation.waitForIdleSync() }
    private fun snapshot(name: String) {
        settle(200)
        instrumentation.uiAutomation.takeScreenshot().apply {
            File(output, "$name.png").outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}
