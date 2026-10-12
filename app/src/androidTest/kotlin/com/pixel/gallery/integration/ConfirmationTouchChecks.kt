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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.components.*
import com.pixel.gallery.ui.deduplication.*
import com.pixel.gallery.ui.labels.LabelsScreen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.transfer.*
import com.pixel.gallery.ui.viewer.ViewerScreen
import com.pixel.gallery.ui.viewer.ViewerTransformStateStore
import java.io.File

/** Touch production components; callbacks record decisions without copying or deleting files. */
internal class ConfirmationTouchChecks(private val instrumentation: Instrumentation) {
    private val output = File(instrumentation.targetContext.cacheDir, "confirmation-qa").apply { mkdirs() }
    private val mode = mutableIntStateOf(0)
    private val dark = mutableStateOf(false)
    private val selected = mutableStateOf<TransferDestination?>(null)
    private val query = mutableStateOf("")
    private val pending = mutableStateOf<TransferMode?>(null)
    private var copyDecisions = 0
    private var moveDecisions = 0
    private var deleteDecisions = 0
    private var createdLabels = 0
    private var downTime = 0L
    private val report = mutableListOf<String>()

    @OptIn(ExperimentalMaterial3Api::class)
    fun exercise(): String {
        val fixture = File(output, "IMG_0241.jpg")
        Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            canvas.drawColor(0xff92abc1.toInt())
            val paint = android.graphics.Paint().apply { color = 0xff365b58.toInt() }
            canvas.drawCircle(65f, 390f, 220f, paint)
            paint.color = 0xff739984.toInt(); canvas.drawCircle(335f, 430f, 230f, paint)
            paint.color = 0xffeadbb2.toInt(); canvas.drawCircle(260f, 105f, 48f, paint)
            fixture.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 95, it) }; recycle()
        }
        val media = MediaEntry(-9101, android.net.Uri.fromFile(fixture).toString(), fixture.absolutePath,
            "image/jpeg", 360, 480, 0, fixture.length(), 1, 1000, bestTimestamp = 1000)
        val newer = media.copy(contentId = -9102, path = fixture.absolutePath + ".copy.jpg", bestTimestamp = 2000)
        val targets = (1..24).map {
            TransferDestination("qa-$it", if (it == 1) "Travel" else "Album %02d".format(it), "/storage/emulated/0/Pictures/Album-$it", coverUri = media.uri, itemCount = it * 12)
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            activity.setContent {
                PixelGalleryTheme(darkTheme = dark.value, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) {
                        when (mode.intValue) {
                            0 -> Scaffold(topBar = { TopAppBar(title = { Text("Select album") }) }) { padding ->
                                TransferDestinationContent(
                                    destinations = targets.filter { matchesTransferDestinationQuery(it, query.value) },
                                    albumUses = emptyList(), query = query.value, selectedDestination = selected.value,
                                    isRunning = false, progress = null, progressLabel = null,
                                    onQueryChange = { query.value = it }, onSelect = { selected.value = it },
                                    onNewAlbum = { mode.intValue = 4 }, onOtherFolders = {},
                                    onCopy = { pending.value = TransferMode.COPY }, onMove = { pending.value = TransferMode.MOVE },
                                    modifier = Modifier.padding(padding),
                                )
                                pending.value?.let { action ->
                                    TransferConfirmationDialog(action, selected.value!!, 12,
                                        onDismiss = { pending.value = null },
                                        onConfirm = { if (action == TransferMode.COPY) copyDecisions++ else moveDecisions++; pending.value = null })
                                }
                            }
                            1 -> ViewerScreen(initialId = media.contentId, photos = listOf(media),
                                transformStateStore = remember { ViewerTransformStateStore() }, onBack = {})
                            2 -> DuplicateReviewDialog(
                                DuplicateUiState("All files", DuplicatePhase.REVIEW,
                                    result = DuplicateScanResult(listOf(DuplicateGroup(media, listOf(newer), DuplicateFingerprint(fixture.length(), "fixture"))), emptyList(), 2),
                                    selectedIds = setOf(newer.contentId)), {}, {}, { deleteDecisions++ })
                            3 -> DeleteConfirmDialog(true, { mode.intValue = 0 }, "Delete permanently?",
                                "These items will be deleted permanently. This action cannot be undone.", "Delete", true, { deleteDecisions++ })
                            4 -> LabelsScreen(emptyList(), {}, {}, { createdLabels++ }, { _, _ -> }, {})
                            5 -> SortDialog(true, { mode.intValue = 0 }, "Sort albums",
                                listOf(SortCriterion("name", "Name", "A to Z", "Z to A"), SortCriterion("count", "Count", "Fewest", "Most"), SortCriterion("date", "Date", "Oldest", "Newest")),
                                "name", "ASC", { _, _ -> mode.intValue = 0 })
                        }
                    }
                }
            }
        }
        settle(1200)
        check(selected.value == null)
        val row = listOf("Travel", "Album 02", "Album 03", "Album 04").map(::bounds)
        check(row.maxOf { it.top } - row.minOf { it.top } < 4 && row.map { it.left }.distinct().size == 4) { "Grid is not four columns: $row" }
        val copy = bounds("Copy"); val move = bounds("Move")
        check(copy.width() == move.width() && copy.height() == move.height()) { "FAB sizes differ: $copy / $move" }
        check(find("Copy")?.text == null && find("Move")?.text == null) { "Actions have visible text labels" }
        click("Copy"); check(pending.value == null && copyDecisions == 0) { "Unselected action was enabled" }
        snapshot("transfer-unselected-light")
        click("Travel"); settle(250)
        click("Copy"); settle(300)
        check(copyDecisions == 0 && find("Copy 12 items?") != null && find(targets[0].path) != null)
        snapshot("copy-confirmation-light")
        click("Cancel"); check(copyDecisions == 0 && pending.value == null)
        click("Move"); settle(300)
        check(moveDecisions == 0 && find("The items will leave") != null)
        snapshot("move-confirmation-light")
        click("Move"); settle(200); check(moveDecisions == 1 && copyDecisions == 0)
        instrumentation.runOnMainSync { dark.value = true }
        settle(300); snapshot("transfer-selected-dark")
        click("Copy"); settle(250); snapshot("copy-confirmation-dark"); click("Cancel")
        report += "Transfer: four columns, equal icon-only FABs, no action before selection or confirmation; Cancel does not execute; confirmed Move calls its callback once. Destination name and full path remain visible. Light/dark screenshots saved."
        instrumentation.runOnMainSync { mode.intValue = 1; dark.value = false }
        settle(1800)
        click("More"); settle(250); click("Build JPEG index"); settle(500)
        check(find("IMG_0241.jpg") != null && find("significant power") != null && find("every zoom level") != null)
        snapshot("index-confirmation-light")
        click("Cancel"); settle(250)
        report += "Production viewer: Build JPEG index opens Family 6-A with file facts and separate complete-read/power paragraphs; cancellation leaves the fixture unchanged."
        instrumentation.runOnMainSync { mode.intValue = 2 }
        settle(450); click("Move selected to recycle bin"); settle(350)
        check(find("Selected copies") != null && find("Marked originals stay") != null && deleteDecisions == 0)
        snapshot("duplicates-confirmation-light")
        click("Cancel"); check(deleteDecisions == 0)
        instrumentation.runOnMainSync { dark.value = true }
        settle(350); click("Move selected to recycle bin"); settle(350); snapshot("duplicates-confirmation-dark"); click("Cancel")
        report += "Production duplicate review: count/scope and recheck/retained-original/recycle-bin guidance shown in light/dark. Cancel makes no deletion decision."
        instrumentation.runOnMainSync { mode.intValue = 3; dark.value = false }
        settle(300); snapshot("permanent-delete-confirmation"); click("Cancel"); check(deleteDecisions == 0)
        instrumentation.runOnMainSync { mode.intValue = 4 }
        settle(350); click("New label"); settle(250); click("Save"); settle(250)
        check(createdLabels == 0 && find("Label name") != null) { "Invalid label was accepted" }
        snapshot("label-input-validation"); click("Cancel")
        instrumentation.runOnMainSync { mode.intValue = 5 }
        settle(300); check(find("A to Z") != null); snapshot("sort-dialog"); click("Cancel")
        report += "Permanent delete and sort use the shared shell; invalid label input keeps the dialog open and does not create a label."
        instrumentation.runOnMainSync { activity.finish() }
        return report.joinToString("\n") + "\nScreenshots: ${output.absolutePath}"
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        fun match(node: AccessibilityNodeInfo?, exact: Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            val value = node.text?.toString()
            if ((if (exact) value?.equals(text, true) else value?.contains(text, true)) == true || node.contentDescription?.toString()?.equals(text, true) == true) return node
            for (i in 0 until node.childCount) match(node.getChild(i), exact)?.let { return it }
            return null
        }
        return match(root, true) ?: match(root, false)
    }
    private fun bounds(text: String) = Rect().also {
        instrumentation.uiAutomation.clearCache()
        checkNotNull(find(text)) { "Missing control: $text" }.getBoundsInScreen(it)
    }
    private fun click(text: String) {
        val rect = bounds(text); val now = SystemClock.uptimeMillis(); downTime = now
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, rect.centerX().toFloat(), rect.centerY().toFloat(), 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN; instrumentation.sendPointerSync(this); recycle()
            }
        }
        settle(120)
    }
    private fun settle(ms: Long) { SystemClock.sleep(ms); instrumentation.waitForIdleSync(); instrumentation.uiAutomation.clearCache() }
    private fun snapshot(name: String) {
        settle(180)
        instrumentation.uiAutomation.takeScreenshot().apply {
            File(output, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}
