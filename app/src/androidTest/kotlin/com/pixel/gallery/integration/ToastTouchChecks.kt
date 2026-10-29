package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.GalleryHeader
import com.pixel.gallery.ui.UnifiedGalleryContainer
import com.pixel.gallery.ui.components.*
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.DuplicateFingerprint
import com.pixel.gallery.model.DuplicateGroup
import com.pixel.gallery.model.DuplicateScanResult
import com.pixel.gallery.ui.deduplication.DuplicatePhase
import com.pixel.gallery.ui.deduplication.DuplicateUiState
import com.pixel.gallery.ui.deduplication.DuplicateReviewDialog
import java.io.File

/** Synthetic messages only; real pointer input exercises the shared production host. */
internal class ToastTouchChecks(private val instrumentation: Instrumentation) {
    private lateinit var messages: GalleryMessages
    private val dark = mutableStateOf(false)
    private val modal = mutableStateOf(false)
    private var downTime = 0L
    private val output = File(instrumentation.targetContext.cacheDir, "toast-qa").apply { mkdirs() }

    fun exercise(): String {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    PixelGalleryTheme(darkTheme = dark.value, dynamicColor = false) {
                        GalleryMessageProvider {
                            messages = LocalGalleryMessages.current
                            UnifiedGalleryContainer.instance.Shell(
                                containerColor = MaterialTheme.colorScheme.surface,
                                header = GalleryHeader(title = { Text("Toast preview") }),
                                snackbarHost = {
                                    GalleryMessageHost()
                                    if (modal.value) GalleryMessageHost(modal = true)
                                },
                            ) { }
                        }
                    }
                }
            }
            settle(1000)
            repeat(5) {
                find("取消")?.let { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    event(MotionEvent.ACTION_DOWN, bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    event(MotionEvent.ACTION_UP, bounds.centerX().toFloat(), bounds.centerY().toFloat())
                }
                settle(200)
            }
            settle(500)
            instrumentation.runOnMainSync { messages.show("Synthetic selection updated", SnackbarDuration.Long) }
            awaitMessage()
            check(!current().visuals.withDismissAction) { "Close action is still enabled" }
            snapshot("toast-light")
            val original = current()
            swipe(0.10f)
            check(current() === original) { "Short drag dismissed the toast" }
            instrumentation.runOnMainSync { messages.show("Synthetic selection updated", SnackbarDuration.Long) }
            swipe(-0.85f)
            awaitMessage()
            check(current() !== original) { "Left swipe did not advance the identical-message queue" }
            // A newly queued message must be settled, without inheriting the prior swipe.
            settle(500)
            check(find("Synthetic selection updated") != null)
            swipe(0.85f)
            check(messages.hostState.currentSnackbarData == null) { "Right swipe did not dismiss" }
            instrumentation.runOnMainSync {
                dark.value = true
                modal.value = true
                messages.show("Synthetic selection updated", SnackbarDuration.Long)
            }
            awaitMessage()
            snapshot("toast-dark-modal")
            swipe(-0.85f)
            check(messages.hostState.currentSnackbarData == null) { "Modal host did not dismiss" }
            instrumentation.runOnMainSync {
                modal.value = false
                messages.show("Synthetic selection updated", SnackbarDuration.Short)
            }
            awaitMessage()
            settle(6500)
            check(messages.hostState.currentSnackbarData == null) { "Short message did not expire" }
            verifyDedupDesign(activity)
            return "PASS: left/right real swipes; short drag retained; identical queued message reset; light/dark modal hosts; no close action; automatic timeout. Dedup page: light/dark screenshots, horizontal photo swipe, floating footer remains anchored, empty selection disables action. Screenshots: ${output.absolutePath}"
        } catch (error: Throwable) {
            snapshot("failure")
            throw error
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun verifyDedupDesign(activity: MainActivity) {
        val groups = (1..3).map { group ->
            val file = File(output, "group-$group.jpg")
            Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xffc5d8de.toInt())
                val canvas = android.graphics.Canvas(this)
                val paint = android.graphics.Paint().apply { color = 0xff789694.toInt() }
                val mountain = android.graphics.Path().apply {
                    moveTo(0f, 160f); lineTo(85f, 35f); lineTo(165f, 155f)
                    lineTo(210f, 80f); lineTo(240f, 130f); lineTo(240f, 180f); lineTo(0f, 180f); close()
                }
                canvas.drawPath(mountain, paint)
                file.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 95, it) }
                recycle()
            }
            val entries = (0..if (group == 1) 3 else 1).map { copy ->
                val copyFile = File(output, "group-$group-${if (copy == 0) "keeper" else "copy-$copy"}.jpg")
                file.copyTo(copyFile, overwrite = true)
                MediaEntry(-(group * 10 + copy).toLong(), android.net.Uri.fromFile(copyFile).toString(),
                    copyFile.absolutePath,
                    "image/jpeg", 240, 180, 0, file.length(), 1, 1000, bestTimestamp = 1000)
            }
            DuplicateGroup(entries.first(), entries.drop(1), DuplicateFingerprint(file.length(), "synthetic-$group"))
        }
        val state = mutableStateOf(DuplicateUiState(title = "All photos", phase = DuplicatePhase.REVIEW,
            result = DuplicateScanResult(groups, emptyList(), groups.sumOf { it.entries.size }),
            selectedIds = groups.flatMap { it.duplicates }.map { it.contentId }.toSet()))
        instrumentation.runOnMainSync {
            dark.value = false
            activity.setContent {
                PixelGalleryTheme(darkTheme = dark.value, dynamicColor = false) {
                    GalleryPageCheckHost(page = DuplicateReviewDialog(state.value, {}, { id ->
                        val current = state.value.selectedIds
                        val group = groups.first { group -> group.entries.any { it.contentId == id } }
                        if (id in current || group.entries.count { it.contentId !in current } > 1) {
                            state.value = state.value.copy(selectedIds = if (id in current) current - id else current + id)
                        }
                    }, {}))
                }
            }
        }
        settle(1100)
        check(!checkedState("group-1-keeper.jpg")) { "Unselected keeper has a checked state" }
        check(checkedState("group-1-copy-1.jpg")) { "Selected copy is not checked" }
        val copyBounds = Rect().also { checkNotNull(find("group-1-copy-1.jpg")).getBoundsInScreen(it) }
        event(MotionEvent.ACTION_DOWN, copyBounds.centerX().toFloat(), copyBounds.centerY().toFloat())
        event(MotionEvent.ACTION_UP, copyBounds.centerX().toFloat(), copyBounds.centerY().toFloat())
        settle(250)
        check(!checkedState("group-1-copy-1.jpg")) { "Deselecting did not clear the checkbox" }
        event(MotionEvent.ACTION_DOWN, copyBounds.centerX().toFloat(), copyBounds.centerY().toFloat())
        event(MotionEvent.ACTION_UP, copyBounds.centerX().toFloat(), copyBounds.centerY().toFloat())
        settle(250)
        check(checkedState("group-1-copy-1.jpg")) { "Selecting did not restore the checkbox" }
        check(find("All photos") == null) { "Removed scope label is still shown" }
        val moveLabel = instrumentation.targetContext.getString(R.string.duplicate_move_to_bin_short)
        val initialMove = Rect().also { checkNotNull(find(moveLabel)).getBoundsInScreen(it) }
        val clearLabel = instrumentation.targetContext.getString(R.string.duplicate_clear_all_short)
        val firstGroupBounds = Rect().also {
            checkNotNull(find(instrumentation.targetContext.getString(R.string.duplicate_group_title, 1, 4))).getBoundsInScreen(it)
        }
        check(textBounds(clearLabel).none { it.centerY() < firstGroupBounds.top }) { "Global selection action remains above the groups" }
        val footerSelect = textBounds(clearLabel).first { it.centerY() > initialMove.top && it.centerY() < initialMove.bottom }
        check(footerSelect.right < initialMove.left) { "Footer selection action is not left of Move to bin" }
        event(MotionEvent.ACTION_DOWN, footerSelect.centerX().toFloat(), footerSelect.centerY().toFloat())
        event(MotionEvent.ACTION_UP, footerSelect.centerX().toFloat(), footerSelect.centerY().toFloat())
        settle(250)
        check(find(instrumentation.targetContext.getString(R.string.duplicate_selected_count, 0)) != null)
        val selectLabel = instrumentation.targetContext.getString(R.string.duplicate_select_all_short)
        val selectCopies = textBounds(selectLabel).first { it.centerY() > initialMove.top && it.centerY() < initialMove.bottom }
        event(MotionEvent.ACTION_DOWN, selectCopies.centerX().toFloat(), selectCopies.centerY().toFloat())
        event(MotionEvent.ACTION_UP, selectCopies.centerX().toFloat(), selectCopies.centerY().toFloat())
        settle(250)
        check(checkedState("group-1-copy-1.jpg")) { "Footer select copies did not restore selection" }
        snapshot("dedup-final-light")
        val before = Rect().also { checkNotNull(find(moveLabel)).getBoundsInScreen(it) }
        val filename = Rect().also { checkNotNull(find("group-1-keeper.jpg")).getBoundsInScreen(it) }
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val y = filename.top - 65 * metrics.density
        event(MotionEvent.ACTION_DOWN, metrics.widthPixels * 0.86f, y)
        repeat(18) { i ->
            event(MotionEvent.ACTION_MOVE, metrics.widthPixels * (0.86f - 0.72f * (i + 1) / 18), y)
            SystemClock.sleep(18)
        }
        event(MotionEvent.ACTION_UP, metrics.widthPixels * 0.14f, y)
        settle(600)
        val lastCopy = Rect().also { checkNotNull(find("group-1-copy-3.jpg")).getBoundsInScreen(it) }
        check(lastCopy.centerX() in 1 until metrics.widthPixels && lastCopy.width() > 0) { "Horizontal strip did not reveal final copy: $lastCopy" }
        val after = Rect().also { checkNotNull(find(moveLabel)).getBoundsInScreen(it) }
        check(before == after) { "Floating footer moved during the horizontal swipe" }
        instrumentation.runOnMainSync { dark.value = true }
        settle(500)
        snapshot("dedup-final-dark")
        val groupTitle = instrumentation.targetContext.getString(R.string.duplicate_group_title, 2, 2)
        val titleBounds = Rect().also { checkNotNull(find(groupTitle)).getBoundsInScreen(it) }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            var readablePixels = 0
            for (yPixel in titleBounds.top until titleBounds.bottom step 2) {
                for (xPixel in titleBounds.left until titleBounds.right step 2) {
                    val pixel = bitmap.getPixel(xPixel, yPixel)
                    if (android.graphics.Color.red(pixel) > 120 && android.graphics.Color.green(pixel) > 120 &&
                        android.graphics.Color.blue(pixel) > 120) readablePixels++
                }
            }
            bitmap.recycle()
            check(readablePixels > 20) { "Dark-theme group title is not readable" }
        }
        repeat(2) {
            val x = metrics.widthPixels * 0.5f
            val start = metrics.heightPixels * 0.72f
            val end = metrics.heightPixels * 0.28f
            event(MotionEvent.ACTION_DOWN, x, start)
            repeat(18) { step ->
                event(MotionEvent.ACTION_MOVE, x, start + (end - start) * (step + 1) / 18)
                SystemClock.sleep(18)
            }
            event(MotionEvent.ACTION_UP, x, end)
            settle(350)
        }
        val last = Rect().also { checkNotNull(find("group-3-copy-1.jpg")).getBoundsInScreen(it) }
        check(last.height() > 0 && last.bottom <= after.top) { "Last file cannot scroll above the floating footer: $last / $after" }
        snapshot("dedup-final-scrolled")
        instrumentation.runOnMainSync { state.value = state.value.copy(selectedIds = emptySet()) }
        settle(400)
        check(find(instrumentation.targetContext.getString(R.string.duplicate_selected_count, 0)) != null)
        // The text child can remain enabled in accessibility even when its
        // containing Compose button is disabled. Verify the actual tap instead.
        event(MotionEvent.ACTION_DOWN, after.centerX().toFloat(), after.centerY().toFloat())
        event(MotionEvent.ACTION_UP, after.centerX().toFloat(), after.centerY().toFloat())
        settle(300)
        check(find(instrumentation.targetContext.getString(R.string.dialog_duplicate_title)) == null) {
            "Empty-selection action opened confirmation"
        }
    }

    private fun checkedState(filename: String): Boolean {
        var node: AccessibilityNodeInfo? = checkNotNull(find(filename))
        while (node != null) {
            if (node.isCheckable) return node.isChecked
            node = node.parent
        }
        error("Missing checkbox semantics for $filename")
    }

    private fun textBounds(text: String): List<Rect> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<Rect>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString() == text) {
                result += Rect().also(node::getBoundsInScreen)
                return
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result.filter { it.width() > 0 && it.height() > 0 }
    }

    private fun current(): SnackbarData = checkNotNull(messages.hostState.currentSnackbarData)
    private fun awaitMessage() {
        repeat(30) {
            if (messages.hostState.currentSnackbarData != null && find("Synthetic selection updated") != null) {
                settle(350)
                return
            }
            settle(100)
        }
        error("Toast did not appear; current=${messages.hostState.currentSnackbarData?.visuals?.message}; activeWindow=${instrumentation.uiAutomation.rootInActiveWindow}")
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.contains(text) == true) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun swipe(fraction: Float) {
        val bounds = Rect().also { checkNotNull(find("Synthetic selection updated")).getBoundsInScreen(it) }
        val width = instrumentation.targetContext.resources.displayMetrics.widthPixels
        val startX = if (fraction < 0) width * 0.88f else width * 0.12f
        val y = bounds.centerY().toFloat()
        event(MotionEvent.ACTION_DOWN, startX, y)
        repeat(18) { i ->
            event(MotionEvent.ACTION_MOVE, startX + width * fraction * (i + 1) / 18, y)
            SystemClock.sleep(18)
        }
        event(MotionEvent.ACTION_UP, startX + width * fraction, y)
        settle(750)
    }
    private fun event(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        MotionEvent.obtain(downTime, now, action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(this)
            recycle()
        }
    }
    private fun settle(ms: Long) { SystemClock.sleep(ms); instrumentation.waitForIdleSync() }
    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
