package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.deduplication.*
import com.pixel.gallery.ui.gallery.PhotoScreen
import com.pixel.gallery.ui.quicksort.QuickSortTargetsSheet
import com.pixel.gallery.ui.settings.LibrarySettingsScreen
import com.pixel.gallery.ui.settings.DisplaySettingsScreen
import com.pixel.gallery.ui.settings.ThemeSettingsGroup
import com.pixel.gallery.ui.components.SortDialog
import com.pixel.gallery.ui.components.SortCriterion
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewer.ViewerScreen
import com.pixel.gallery.ui.viewer.ViewerTransformStateStore
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real pointer events and screenshots of production composables; fixtures never enter the move/delete queue. */
internal class RedesignTouchChecks(private val instrumentation: Instrumentation) {
    private val mode = mutableIntStateOf(0)
    private val dark = mutableStateOf(false)
    private val targets = (1..24).map { TransferDestination("fixture-$it", "Album %02d".format(it), "/Pictures/UiFixture/$it", itemCount = it * 10) }
    private val selected = mutableStateOf(targets.take(3))
    private val density = instrumentation.targetContext.resources.displayMetrics.density
    private var root = androidx.compose.ui.geometry.Rect.Zero
    @Volatile private var panelHeight = 0f
    private var downTime = 0L
    private val output = File(instrumentation.targetContext.cacheDir, "redesign-qa").apply { mkdirs() }
    private val report = mutableListOf<String>()
    private var sortConfirmed: Pair<String, String>? = null

    fun exercise(quickSortOnly: Boolean = false): String {
        val fixture = File(output, "viewer-fixture.jpg")
        Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff668899.toInt())
            fixture.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 95, it) }
            recycle()
        }
        val media = MediaEntry(-9001, android.net.Uri.fromFile(fixture).toString(), fixture.absolutePath,
            "image/jpeg", 160, 240, 0, fixture.length(), 1, 1000, bestTimestamp = 1000)
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        instrumentation.runOnMainSync {
            activity.setContent {
                PixelGalleryTheme(darkTheme = dark.value, dynamicColor = false) {
                    Box(Modifier.fillMaxSize().background(if (dark.value) Color(0xff121212) else Color.White)
                        .onGloballyPositioned { root = it.boundsInWindow() }) {
                        when (mode.intValue) {
                            0 -> QuickSortTargetsSheet(targets, selectedTargets = selected.value, dockTop = false,
                                albumUses = listOf(QuickSortAlbumUse("fixture-job", targets[0].stableKey, System.currentTimeMillis())),
                                onDockTopChange = {}, onApply = {}, onDismiss = {},
                                onTargetsChange = { selected.value = it },
                                onPanelHeightChange = { panelHeight = it.value * density })
                            1 -> DuplicateReviewDialog(
                                DuplicateUiState(title = "Synthetic duplicates", phase = DuplicatePhase.REVIEW,
                                    result = DuplicateScanResult(emptyList(), emptyList(), 2)), {}, {}, {})
                            2 -> PhotoScreen(albumName = "/Pictures/UiFixture", onBack = {}, onNavigateToViewer = { _, _, _ -> })
                            3 -> ViewerScreen(initialId = media.contentId, photos = listOf(media),
                                transformStateStore = remember { ViewerTransformStateStore() }, onBack = {})
                            4 -> LibrarySettingsScreen(onBack = {}, onNavigateToExcludedFolders = {})
                            5 -> DisplaySettingsScreen(onBack = {})
                            6 -> ThemeSettingsGroup(title = "Theme")
                            7, 8 -> SortDialog(visible = true, onDismissRequest = {},
                                title = if (mode.intValue == 7) "Sort Photos" else "Sort Albums",
                                criteria = if (mode.intValue == 7) listOf(
                                    SortCriterion("DATE", "Date", "Oldest first", "Newest first"),
                                    SortCriterion("NAME", "Name", "A to Z", "Z to A"),
                                    SortCriterion("SIZE", "Size", "Smallest first", "Largest first")) else listOf(
                                    SortCriterion("NAME", "Name", "A to Z", "Z to A"),
                                    SortCriterion("COUNT", "Count", "Least first", "Most first"),
                                    SortCriterion("DATE", "Date", "Oldest first", "Newest first")),
                                initialCriterion = if (mode.intValue == 7) "DATE" else "NAME", initialDirection = "DESC",
                                onConfirm = { criterion, direction -> sortConfirmed = criterion to direction })
                        }
                    }
                }
            }
        }
        // MainActivity shows a native notification-access prompt on every launch.
        // Dismiss only that optional prompt; do not grant notification access for UI tests.
        SystemClock.sleep(450)
        if (findText("开启后台保活支持") != null) {
            findText("取消")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        settle(1300)
        // Accessibility can connect after the first launch query. Use a real tap once connected.
        for (attempt in 0 until 5) {
            val cancel = findText("取消")
            if (cancel != null) {
                val bounds = Rect().also(cancel::getBoundsInScreen)
                tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                settle(200)
                break
            }
            SystemClock.sleep(150)
        }
        check(panelHeight < 120 * density) { "Chooser did not start collapsed" }
        check(findText("Album 01") == null) { "Album grid was expanded on entry" }
        check(findText("Expand") == null && findText("Done") == null) { "Expand/Done is still visible" }
        snapshot("quicksort-collapsed")
        val expandX = root.center.x
        val expandY = root.bottom - panelHeight + 10 * density
        event(MotionEvent.ACTION_DOWN, expandX, expandY)
        for (step in 1..20) { event(MotionEvent.ACTION_MOVE, expandX, expandY - step * 25 * density); settle(18) }
        event(MotionEvent.ACTION_UP, expandX, expandY - 500 * density)
        settle(400)
        check(panelHeight > 200 * density) { "Upward swipe did not expand chooser" }
        snapshot("quicksort-light")
        // All albums appear in inventory order without a separate Frequent section.
        instrumentation.uiAutomation.clearCache()
        val frequentCount = countText("Frequent") + countText("常用")
        check(frequentCount == 1) { "Expected one visible Frequent label, found $frequentCount" }
        val row = (1..4).map { boundsFor("Album %02d".format(it)) }
        check(row.maxOf { it.top } - row.minOf { it.top } <= 3) { "Albums are not in one four-column row: $row" }
        check(row.map { it.left }.distinct().size == 4) { "Four album columns missing: $row" }
        val heightBefore = panelHeight
        val x = root.center.x
        val y = root.bottom - panelHeight + 10 * density
        event(MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..10) { event(MotionEvent.ACTION_MOVE, x, y + step * 9 * density); settle(22) }
        val changed = heightBefore - panelHeight
        check(changed in (60 * density)..(100 * density)) { "Chooser did not track before UP: changed=$changed" }
        snapshot("quicksort-mid-drag")
        event(MotionEvent.ACTION_CANCEL, x, y + 90 * density)
        settle(350)
        check(abs(panelHeight - heightBefore) < 2 * density) { "Cancelled drag failed to restore anchor" }
        report += "Quick-sort: four album columns; measured height follows MOVE before UP; CANCEL restores expanded anchor."
        val frequent = boundsForTextEither("Frequent", "常用")
        tap(frequent.centerX().toFloat(), frequent.centerY().toFloat())
        settle(300)
        check(findText("Album 01") != null && findText("Album 02") == null) { "Frequent tab did not use successful-batch history" }
        check(findText("Successful batches in the last 30 days") == null && findText("按近 30 天成功批次排序") == null)
        snapshot("quicksort-frequent")
        report += "Frequent tab shows the album with a successful fixture batch and excludes unused albums."
        val newAlbum = boundsForTextEither("New album", "新建相册")
        tap(newAlbum.centerX().toFloat(), newAlbum.centerY().toFloat())
        settle(250)
        check(findText("Album name") != null || findText("相册名称") != null) { "New album action was lost" }
        val cancelCreate = boundsForTextEither("Cancel", "取消")
        tap(cancelCreate.centerX().toFloat(), cancelCreate.centerY().toFloat())
        settle(250)
        val clear = boundsForTextEither("3 selected", "已选 3 个")
        tap(clear.centerX().toFloat(), clear.centerY().toFloat())
        settle(250)
        check(selected.value.isEmpty()) { "Count-chip clear did not clear all album targets" }
        check(findText("0 selected") != null || findText("已选 0 个") != null)
        snapshot("quicksort-cleared")
        report += "Default stays collapsed; swipe opens chooser; full short title and clear chip share one header row; New album is retained and opens its dialog; clear applies immediately."
        if (quickSortOnly) {
            instrumentation.runOnMainSync { dark.value = true }
            settle(250)
            snapshot("quicksort-dark")
            instrumentation.runOnMainSync { dark.value = false; mode.intValue = 4 }
            settle(500)
            snapshot("library-settings")
            val backupEntry = boundsForTextEither("Label backup", "标签备份")
            check(!backupEntry.isEmpty)
            check(findText("Export labels") != null || findText("导出标签") != null)
            check(findText("Import labels") != null || findText("导入标签") != null)
            snapshot("label-backup-settings")
            instrumentation.runOnMainSync { mode.intValue = 5 }
            settle(400)
            snapshot("hand-preference-settings")
            instrumentation.runOnMainSync { mode.intValue = 6 }
            settle(400)
            snapshot("theme-mode-reference")
            instrumentation.runOnMainSync { mode.intValue = 7 }
            settle(350)
            snapshot("sort-photos-date")
            val byName = boundsFor("Name")
            tap(byName.centerX().toFloat(), byName.centerY().toFloat())
            settle(250)
            val ascending = boundsFor("A to Z")
            tap(ascending.centerX().toFloat(), ascending.centerY().toFloat())
            settle(250)
            snapshot("sort-photos-name")
            val confirm = boundsFor("Confirm")
            tap(confirm.centerX().toFloat(), confirm.centerY().toFloat())
            settle(180)
            check(sortConfirmed == ("NAME" to "ASC")) { "Sort Photos selection changed its callback behavior" }
            instrumentation.runOnMainSync { mode.intValue = 8 }
            settle(350)
            val byCount = boundsFor("Count")
            tap(byCount.centerX().toFloat(), byCount.centerY().toFloat())
            settle(250)
            snapshot("sort-albums-count")
            check(findText("Most first") != null && findText("Least first") != null)
            report += "Theme mode, Quick Sort tabs, Sort Photos and Sort Albums use the same shape-switching control; criterion/direction callbacks remain NAME/ASC after changing both options."
            instrumentation.runOnMainSync { activity.finish() }
            return report.joinToString("\n") + "\nScreenshots: ${output.absolutePath}"
        }
        instrumentation.runOnMainSync { dark.value = true; mode.intValue = 1 }
        settle(500)
        val darkDuplicate = snapshot("duplicates-dark")
        check(darkDuplicate.getPixel(darkDuplicate.width / 2, darkDuplicate.height / 2) and 0xffffff == 0x121212) {
            "Duplicate page did not use the dark app surface"
        }
        instrumentation.runOnMainSync { dark.value = false }
        settle(300)
        val lightDuplicate = snapshot("duplicates-light")
        check(lightDuplicate.getPixel(lightDuplicate.width / 2, lightDuplicate.height / 2) and 0xffffff == 0xffffff)
        report += "Duplicate review uses #121212 in dark mode and the light app surface in light mode."
        instrumentation.runOnMainSync { mode.intValue = 2 }
        settle(600)
        val menu = boundsFor("More")
        tap(menu.centerX().toFloat(), menu.centerY().toFloat())
        settle(350)
        check(findText("Sort Photos") != null && findText("Hide Album") != null) { "Album overflow actions missing" }
        snapshot("album-menu")
        report += "Album overflow opens the shared rounded menu with original album-specific actions."
        instrumentation.runOnMainSync { mode.intValue = 3 }
        settle(500)
        val share = boundsFor("Share")
        val viewerX = root.center.x
        val viewerY = share.centerY().toFloat()
        // Begin the drag immediately; a compositor screenshot can outlast the viewer's auto-hide timer.
        event(MotionEvent.ACTION_DOWN, viewerX, viewerY)
        for (step in 1..6) { event(MotionEvent.ACTION_MOVE, viewerX, viewerY - step * 7 * density); settle(25) }
        val during = boundsFor("Share")
        check(share.top - during.top > 15 * density) { "Viewer action bar did not follow MOVE before UP: $share / $during" }
        snapshot("viewer-mid-drag")
        // Keep moving to reach the expanded anchor, then settle.
        for (step in 7..16) { event(MotionEvent.ACTION_MOVE, viewerX, viewerY - step * 7 * density); settle(20) }
        event(MotionEvent.ACTION_UP, viewerX, viewerY - 112 * density)
        settle(300)
        check(findText("Move") != null) { "Viewer secondary actions did not expand" }
        snapshot("viewer-expanded")
        val expandedShare = boundsFor("Share")
        event(MotionEvent.ACTION_DOWN, viewerX, expandedShare.centerY().toFloat())
        for (step in 1..7) { event(MotionEvent.ACTION_MOVE, viewerX, expandedShare.centerY() + step * 7 * density); settle(25) }
        val collapsing = boundsFor("Share")
        snapshot("viewer-downward-mid-drag")
        check(collapsing.top - expandedShare.top > 15 * density) { "Viewer action bar did not track downward drag: $expandedShare / $collapsing" }
        event(MotionEvent.ACTION_CANCEL, viewerX, expandedShare.centerY() + 49 * density)
        settle(300)
        report += "Production viewer: first-row actions move continuously during upward/downward MOVE; release reveals secondary actions; CANCEL is handled."
        instrumentation.runOnMainSync { activity.finish() }
        return report.joinToString("\n") + "\nScreenshots: ${output.absolutePath}"
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        return findTextIn(text, instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun findTextIn(text: String, node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) return node
        for (index in 0 until node.childCount) findTextIn(text, node.getChild(index))?.let { return it }
        return null
    }
    private fun countText(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): Int {
        if (node == null) return 0
        // Selectable controls expose a merged label as well as its text descendant.
        if (node.text?.toString() == text) return 1
        return (0 until node.childCount).sumOf { countText(text, node.getChild(it)) }
    }
    private fun boundsFor(text: String): Rect = Rect().also { bounds ->
        // Animated layout changes can leave UiAutomation's node bounds cached between MOVE events.
        instrumentation.uiAutomation.clearCache()
        checkNotNull(findText(text)) { "Missing accessible control: $text" }.getBoundsInScreen(bounds)
    }
    private fun boundsForTextEither(first: String, second: String) = boundsFor(if (findText(first) != null) first else second)
    private fun tap(x: Float, y: Float) { event(MotionEvent.ACTION_DOWN, x, y); event(MotionEvent.ACTION_UP, x, y) }
    private fun event(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        MotionEvent.obtain(downTime, now, action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(this)
            recycle()
        }
    }
    private fun settle(millis: Long) { SystemClock.sleep(millis); instrumentation.waitForIdleSync() }
    private fun snapshot(name: String): Bitmap {
        settle(280)
        return instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
