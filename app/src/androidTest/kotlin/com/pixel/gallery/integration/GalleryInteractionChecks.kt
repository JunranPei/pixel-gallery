package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.utils.GalleryInteractionProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Actual touch + rendered coordinates/state; does not capture or recognize screenshots. */
internal class GalleryInteractionChecks(private val runner: Instrumentation, private val run: String) {
    private val context = runner.targetContext
    private var down = 0L
    @Volatile private var phase = "setup"
    private val frames = CopyOnWriteArrayList<Map<String, Any>>()
    private val observations = mutableListOf<Map<String, Any>>()
    fun exercise(): String = runBlocking {
        val settings = SettingsRepository(context)
        val original = settings.albumGrouping.first()
        val originalSort = settings.albumSortOrder.first()
        val enabled = settings.groupingEnabled.first()
        val token = UUID.randomUUID().toString().take(6)
        val names = (0..5).map { "AAFollow_${token}_$it" }
        val paths = names.map { "/storage/emulated/0/Pictures/$it" }
        val gid = "follow-$token"
        val owned = names.map { name ->
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "follow.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$name/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff668855.toInt()) }
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        }
        var activity: MainActivity? = null
        try {
            settings.setGroupingEnabled(true)
            settings.setAlbumSortOrder("CUSTOM")
            settings.updateAlbumGrouping { state -> state.copy(
                groups = listOf(AlbumGroup(gid, "Follow group $token", paths.take(3))) + state.groups,
                collapsedIds = state.collapsedIds + state.groups.map { it.id },
            ).withOrder("groups:albums") { it.copy(custom = listOf("group:$gid") + it.custom) }
                .withOrder(gid) { AlbumOrderState(rule = AlbumSortOrder.CUSTOM, customStarted = true, custom = paths.take(3).map { "album:$it" }) }
                .withOrder("albums") { it.copy(customStarted = true, custom = paths.drop(3).map { "album:$it" } + it.custom) }
            }
            runner.uiAutomation.performGlobalAction(2); SystemClock.sleep(400)
            val a = runner.startActivitySync(Intent(context, MainActivity::class.java)
                .setData(android.net.Uri.parse("pixel-gallery-test://interaction/$token"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
            activity = a
            runner.runOnMainSync {
                val photos = ViewModelProvider(a)[PhotosViewModel::class.java]
                a.setContent { PixelGalleryTheme(dynamicColor = false) { MainScaffold(initialHomeTab = 0, photosViewModel = photos) } }
                a.window.decorView.viewTreeObserver.addOnDrawListener {
                    GalleryInteractionProbe.navigation?.let { n -> frames += mapOf(
                        "phase" to phase, "time" to SystemClock.uptimeMillis(), "selected" to n.selected,
                        "settled" to n.settled, "target" to n.target, "scrolling" to n.scrolling,
                        "weight" to GalleryInteractionProbe.albumWeight,
                    ) }
                }
                photos.refresh()
            }
            GalleryInteractionProbe.enabled = true
            SystemClock.sleep(1800)
            if (find("Background support") != null) click("Cancel")
            await { find("Albums") != null && find("Photos") != null }
            // The existing selected tab starts changing only after settledPage changes.
            for ((label, target, ordinal) in listOf(Triple("Albums", 1, 2), Triple("Photos", 0, 1))) {
                phase = "tap-$label"
                click(label)
                await { GalleryInteractionProbe.navigation?.let { it.settled == target && !it.scrolling } == true }
                SystemClock.sleep(700)
                val samples = frames.filter { it["phase"] == phase }
                val simultaneous = samples.any { it["selected"] == ordinal && it["settled"] != target &&
                    if (target == 1) (it["weight"] as Float) > .01f else (it["weight"] as Float) < .99f }
                observations += mapOf("case" to phase, "indicatorStartedBeforePagerSettled" to simultaneous)
            }
            if (run != "baseline") {
            phase = "reverse"
            click("Albums"); SystemClock.sleep(60)
            // Address the tab itself while its animated bounds are changing.
            var requested = false
            repeat(5) {
                if (!requested) {
                    var photosTab = checkNotNull(find("Photos"))
                    while (!photosTab.isClickable && photosTab.parent != null) photosTab = photosTab.parent
                    requested = photosTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!requested) SystemClock.sleep(20)
                }
            }
            check(requested) { "Accessibility target changed during rapid switch" }
            await { GalleryInteractionProbe.navigation?.let { it.selected == 1 && it.settled == 0 && !it.scrolling } == true }
            observations += mapOf("case" to phase, "converged" to true)
            } else {
                click("Albums"); await { find(names[0]) != null }; SystemClock.sleep(800)
            }
            SystemClock.sleep(600)
            phase = "swipe"
            val display = context.resources.displayMetrics
            val y = display.heightPixels * .5f
            event(MotionEvent.ACTION_DOWN, display.widthPixels * .85f, y)
            repeat(18) { i -> event(MotionEvent.ACTION_MOVE, display.widthPixels * (.85f - .7f * (i + 1) / 18), y); SystemClock.sleep(12) }
            event(MotionEvent.ACTION_UP, display.widthPixels * .15f, y)
            await { GalleryInteractionProbe.navigation?.let { it.selected == 2 && it.settled == 1 } == true }
            SystemClock.sleep(600)
            observations += mapOf("case" to phase, "converged" to true)
            await { find(names[0]) != null }
            phase = "drag"
            val card = bounds(names[0], true)
            event(MotionEvent.ACTION_DOWN, card.exactCenterX(), card.exactCenterY())
            SystemClock.sleep(650)
            event(MotionEvent.ACTION_UP, card.exactCenterX(), card.exactCenterY())
            await { find("1 selected") != null }
            SystemClock.sleep(450)
            val start = bounds(names[0], true)
            val textStart = bounds(names[0])
            val dx = start.width() * .32f
            event(MotionEvent.ACTION_DOWN, start.exactCenterX(), start.exactCenterY())
            repeat(8) { i -> event(MotionEvent.ACTION_MOVE, start.exactCenterX() + dx * (i + 1) / 8, start.exactCenterY()); SystemClock.sleep(20) }
            SystemClock.sleep(150)
            val moved = bounds(names[0])
            val actualDx = moved.left - textStart.left
            observations += mapOf("case" to "within-cell-follow", "pointerDx" to dx, "cardDx" to actualDx, "errorPx" to abs(actualDx - dx))
            event(MotionEvent.ACTION_CANCEL, start.exactCenterX() + dx, start.exactCenterY())
            SystemClock.sleep(400)
            val reset = bounds(names[0])
            observations += mapOf("case" to "cancel-resets-position", "errorPx" to abs(reset.left - textStart.left))
            // A real crossing must still update persistent order, not only move pixels.
            val from = bounds(names[0], true); val to = bounds(names[1], true)
            event(MotionEvent.ACTION_DOWN, from.exactCenterX(), from.exactCenterY())
            repeat(20) { i ->
                val f = (i + 1) / 20f
                event(MotionEvent.ACTION_MOVE, from.exactCenterX() + (to.exactCenterX() - from.exactCenterX()) * f,
                    from.exactCenterY() + (to.exactCenterY() - from.exactCenterY()) * f)
                SystemClock.sleep(20)
            }
            event(MotionEvent.ACTION_UP, to.exactCenterX(), to.exactCenterY())
            await { runBlocking { settings.albumGrouping.first().orderState(gid).custom.indexOf("album:${paths[0]}") > settings.albumGrouping.first().orderState(gid).custom.indexOf("album:${paths[1]}") } }
            observations += mapOf("case" to "group-custom-persist", "passed" to true)
            SystemClock.sleep(450)
            val reverseOrder = settings.albumGrouping.first().orderState(gid).custom
            val reverseFrom = bounds(names[0], true); val reverseTo = bounds(names[1], true)
            event(MotionEvent.ACTION_DOWN, reverseFrom.exactCenterX(), reverseFrom.exactCenterY())
            fun moveAcross(a: Rect, b: Rect) {
                repeat(16) { i ->
                    val f = (i + 1) / 16f
                    event(MotionEvent.ACTION_MOVE, a.exactCenterX() + (b.exactCenterX() - a.exactCenterX()) * f,
                        a.exactCenterY() + (b.exactCenterY() - a.exactCenterY()) * f)
                    SystemClock.sleep(20)
                }
            }
            moveAcross(reverseFrom, reverseTo)
            await { runBlocking { settings.albumGrouping.first().orderState(gid).custom != reverseOrder } }
            SystemClock.sleep(250)
            // One stationary sample lets the gesture see its own slot after the saved move.
            event(MotionEvent.ACTION_MOVE, reverseTo.exactCenterX(), reverseTo.exactCenterY())
            moveAcross(reverseTo, reverseFrom)
            event(MotionEvent.ACTION_UP, reverseFrom.exactCenterX(), reverseFrom.exactCenterY())
            SystemClock.sleep(450)
            val reversed = settings.albumGrouping.first().orderState(gid).custom == reverseOrder
            observations += mapOf("case" to "reverse-drag-without-release", "passed" to reversed)
            click("Clear selection"); SystemClock.sleep(350)
            // Existing side routes use the same navigation control; reveal through its own accessibility action.
            for ((label, ordinal) in listOf("Hidden" to 3, "Lock" to 0)) {
                phase = "side-$label"
                check(namedAction("Show $label"))
                await { find(label) != null }; SystemClock.sleep(600)
                click(label)
                await { GalleryInteractionProbe.navigation?.selected == ordinal }
                click("Albums")
                await { GalleryInteractionProbe.navigation?.let { it.selected == 2 && it.settled == 1 } == true }
                SystemClock.sleep(650)
                observations += mapOf("case" to phase, "returnedToAlbums" to true)
            }
            if (run != "baseline") {
                check(observations.filter { it.containsKey("indicatorStartedBeforePagerSettled") }.all { it["indicatorStartedBeforePagerSettled"] == true }) { "Navigation still waits for page completion: $observations" }
                check((observations.first { it["case"] == "within-cell-follow" }["errorPx"] as Float) <= 3f) { "Dragged card does not follow pointer: $observations" }
                check((observations.first { it["case"] == "cancel-resets-position" }["errorPx"] as Int) <= 3)
                check(reversed) { "Cannot reverse reorder across the same neighbour" }
            }
            observations.toString()
        } finally {
            GalleryInteractionProbe.enabled = false
            File(context.cacheDir, "gallery-interaction-$run.json").writeText(Gson().toJson(mapOf("observations" to observations, "frames" to frames)))
            activity?.let { a -> runner.runOnMainSync { a.finish() } }
            settings.updateAlbumGrouping { original }; settings.setAlbumSortOrder(originalSort); settings.setGroupingEnabled(enabled)
            owned.forEach { context.contentResolver.delete(it, null, null) }
        }
    }
    private fun find(name: String): AccessibilityNodeInfo? {
        runner.uiAutomation.clearCache()
        fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.text?.toString() == name || n.contentDescription?.toString() == name) return n
            repeat(n.childCount) { n.getChild(it)?.let(::visit)?.let { found -> return found } }
            return null
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun bounds(name: String, clickable: Boolean = false): Rect {
        var n = checkNotNull(find(name)) { "Missing $name in $phase" }
        if (clickable) while (!n.isClickable && n.parent != null) n = n.parent
        return Rect().also(n::getBoundsInScreen)
    }
    private fun namedAction(label: String): Boolean {
        runner.uiAutomation.clearCache()
        fun visit(n: AccessibilityNodeInfo): Boolean {
            n.actionList.firstOrNull { it.label?.toString() == label }?.let { return n.performAction(it.id) }
            repeat(n.childCount) { n.getChild(it)?.let { child -> if (visit(child)) return true } }
            return false
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit) == true
    }
    private fun click(name: String) {
        val r = bounds(name, true)
        event(MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY())
        event(MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY())
    }
    private fun event(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            runner.sendPointerSync(this); recycle()
        }
    }
    private fun await(check: () -> Boolean) {
        repeat(100) { if (check()) return; SystemClock.sleep(100) }
        error("Timeout in $phase; ${GalleryInteractionProbe.navigation}")
    }
}
