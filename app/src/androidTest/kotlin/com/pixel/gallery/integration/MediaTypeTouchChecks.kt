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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.MainActivity
import com.pixel.gallery.R
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Real production navigation with an isolated mixed-media MediaStore fixture. */
internal class MediaTypeTouchChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val created = mutableListOf<Uri>()
    private val output = File(context.cacheDir, "media-type-qa").apply { mkdirs() }
    private val dark = mutableStateOf(false)
    private val fontScale = mutableStateOf(1f)
    private val all get() = context.getString(R.string.media_type_all)
    private val images get() = context.getString(R.string.media_type_images)
    private val videos get() = context.getString(R.string.media_type_videos)
    private var downTime = 0L

    fun exercise(): String = runBlocking {
        val settings = SettingsRepository(context)
        settings.setStartupAtAlbums(false)
        settings.setPhotoSortOrder("NAME_ASC")
        settings.setAlbumSortOrder("NAME_ASC")
        val root = "Pictures/MediaTypeQA_${UUID.randomUUID()}/"
        var activity: MainActivity? = null
        try {
            fixture(root, "MT-Mixed", "01-needle.jpg", false)
            fixture(root, "MT-Mixed", "02-needle.mp4", true)
            fixture(root, "MT-Mixed", "03-other.jpg", false)
            fixture(root, "MT-Mixed", "04-other.mp4", true)
            fixture(root, "MT-Videos", "05-only.mp4", true)
            fixture(root, "MT-Videos", "06-only.mp4", true)
            fixture(root, "MT-Images", "07-only.jpg", false)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync {
                val viewModel = ViewModelProvider(activity!!)[PhotosViewModel::class.java]
                activity!!.setContent {
                    PixelGalleryTheme(darkTheme = dark.value, dynamicColor = false) {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                            Surface { MainScaffold(photosViewModel = viewModel) }
                        }
                    }
                }
                viewModel.refresh()
            }
            await("seven fixture tiles") { tiles().size == 7 }
            click("More"); selected(all); snapshot("home-menu-all-light")
            click(videos)
            await("four video tiles") { tiles().size == 4 }
            click("Albums")
            await("video albums") { find("MT-Videos") != null && find("MT-Mixed") != null }
            check(find("MT-Images") == null) { "Image-only album survived the video filter" }
            click("More"); selected(videos); snapshot("album-grid-menu-videos-light")
            click(images)
            await("image albums") { find("MT-Images") != null && find("MT-Videos") == null }
            click("Photos")
            await("home follows album-grid choice") { tiles().size == 3 }
            click("Albums"); click("MT-Mixed")
            await("album inherits images") { tiles().size == 2 }
            click("More"); selected(images); click(all)
            await("local all-media choice") { tiles().size == 4 }
            back(); click("More"); selected(images); back()
            click("MT-Mixed")
            await("re-entered album inherits parent anew") { tiles().size == 2 }
            click("More"); selected(images); click(videos)
            await("two local videos") { tiles().size == 2 }
            snapshot("album-videos-light")

            // Select all must operate on the filtered pair, excluding both hidden images.
            longPress(tiles().first())
            await("selection starts") { find("1 selected") != null }
            click(context.getString(R.string.select_all))
            await("only videos selected") { find("2 selected") != null }
            snapshot("album-select-all-videos")
            click("Clear selection")

            // A search inside the video category yields one result and one viewer page.
            val title = bounds("MT-Mixed")
            drag(screenWidth() * .65f, title.centerY().toFloat(), screenWidth() * .2f, title.centerY().toFloat())
            await("album search field") { nodes().any { it.isEditable } }
            val input = nodes().first { it.isEditable }
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "needle")
            })
            await("search and video intersection") { tiles().size == 1 }
            back() // Hide the keyboard, retaining the album search.
            tap(tiles().first())
            await("viewer opens") { find("Info") != null }
            click("Info")
            await("filtered viewer starts with video") { find("02-needle.mp4") != null }
            back()
            drag(screenWidth() * .85f, screenHeight() * .45f, screenWidth() * .1f, screenHeight() * .45f)
            settle(350)
            // Bring the auto-hidden action row back if needed.
            if (find("Info") == null) tap(Rect(screenWidth()/2, screenHeight()/2, screenWidth()/2+1, screenHeight()/2+1))
            click("Info")
            check(find("02-needle.mp4") != null && find("04-other.mp4") == null) {
                "Viewer escaped the one-item search/type intersection"
            }
            snapshot("viewer-filtered-details")
            back(); back()
            // First back above closes details; second returns to the same album/search.
            await("album search retained after viewer") { tiles().size == 1 }
            back() // Exit album search.
            await("local video choice retained") { tiles().size == 2 }
            instrumentation.runOnMainSync { dark.value = true }
            click("More"); selected(videos); snapshot("album-menu-videos-dark")
            back(); back()
            click("More"); selected(images); snapshot("parent-menu-images-dark")
            back()
            click("MT-Images")
            await("image-only album") { tiles().size == 1 }
            click("More"); click(videos)
            await("empty video category") { find(context.getString(R.string.media_type_no_videos)) != null }
            snapshot("album-no-videos-dark")
            back(); click("More"); selected(images)
            instrumentation.runOnMainSync { fontScale.value = 1.5f }
            settle(350)
            selected(images); snapshot("menu-large-text-dark")
            check(bounds(images).width() > 0 && bounds(videos).right < screenWidth())
            "Home and album grid share the filter; album detail inherits once and remains independent.\n" +
                "Re-entry inherits the parent again; image-only/video-only albums are excluded appropriately.\n" +
                "Select all selects only the two visible videos; search narrows to one video and viewer paging stays in that result.\n" +
                "Empty-video state and 150% font scale checked; light/dark menus reuse the Theme mode control. Screenshots: ${output.absolutePath}"
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            created.forEach { runCatching { resolver.delete(it, null, null) } }
        }
    }

    private fun fixture(root: String, album: String, name: String, video: Boolean) {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$root$album/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        created += uri
        resolver.openOutputStream(uri)!!.use { stream ->
            if (video) instrumentation.context.assets.open("media-type-clip.mp4").use { it.copyTo(stream) }
            else Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xffb08c66.toInt()); compress(Bitmap.CompressFormat.JPEG, 95, stream); recycle()
            }
        }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(text: String) = nodes().firstOrNull {
        it.text?.toString() == text || it.contentDescription?.toString() == text
    }
    private fun bounds(text: String): Rect {
        await(text) { find(text) != null }
        return Rect().also(find(text)!!::getBoundsInScreen)
    }
    private fun selected(text: String) {
        val node = checkNotNull(find(text)) { "Missing option: $text" }
        check(node.isSelected || node.isChecked || node.parent?.isSelected == true || node.parent?.isChecked == true) {
            "Option is not selected: $text"
        }
    }
    private fun tiles(): List<Rect> = nodes().filter { it.isClickable }.map { Rect().also(it::getBoundsInScreen) }
        .filter { it.width() > screenWidth()/5 && abs(it.height() - it.width()) < 12 && it.top > 100 }
        .distinctBy { it.toShortString() }.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
    private fun click(text: String) { tap(bounds(text)); settle(180) }
    private fun tap(rect: Rect) {
        event(MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat())
        event(MotionEvent.ACTION_UP, rect.centerX().toFloat(), rect.centerY().toFloat())
    }
    private fun longPress(rect: Rect) {
        event(MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat())
        SystemClock.sleep(650)
        event(MotionEvent.ACTION_UP, rect.centerX().toFloat(), rect.centerY().toFloat())
    }
    private fun drag(x: Float, y: Float, endX: Float, endY: Float) {
        event(MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..15) {
            event(MotionEvent.ACTION_MOVE, x+(endX-x)*step/15, y+(endY-y)*step/15)
            SystemClock.sleep(20)
        }
        event(MotionEvent.ACTION_UP, endX, endY)
        settle(350)
    }
    private fun event(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN; instrumentation.sendPointerSync(this); recycle()
        }
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(350) }
    private fun screenWidth() = context.resources.displayMetrics.widthPixels
    private fun screenHeight() = context.resources.displayMetrics.heightPixels
    private fun await(label: String, condition: () -> Boolean) {
        repeat(60) { settle(100); if (condition()) return }
        snapshot("failure")
        error("Timed out: $label; nodes=${nodes().map { "${it.text}:${it.contentDescription}" }}")
    }
    private fun settle(ms: Long) {
        SystemClock.sleep(ms)
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        // Fresh emulators show the Android immersive-mode education over the viewer.
        find("Got it")?.let {
            it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(150)
            instrumentation.uiAutomation.clearCache()
        }
    }
    private fun snapshot(name: String) {
        settle(200)
        instrumentation.uiAutomation.takeScreenshot().apply {
            File(output, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}
