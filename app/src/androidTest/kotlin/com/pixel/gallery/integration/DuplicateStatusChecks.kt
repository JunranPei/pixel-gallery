package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LabelOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.pixel.gallery.MainActivity
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.GalleryHeader
import com.pixel.gallery.ui.UnifiedGalleryContainer
import com.pixel.gallery.ui.deduplication.*
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import java.io.File
import kotlin.math.roundToInt

/** Production pages with isolated UI states; does not scan or submit user files. */
internal class DuplicateStatusChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val output = File(context.cacheDir, "duplicate-status-qa").apply { mkdirs() }
    private val dark = mutableStateOf(false)
    private val icons = mutableStateOf(false)
    private val state = mutableStateOf(DuplicateUiState(
        title = "All files", phase = DuplicatePhase.SCANNING,
        progress = DuplicateScanProgress(842, 1280, DuplicateScanStage.COMPARING, 24_000_000),
    ))
    private var iconBounds = Rect()
    private var trackColor = 0

    fun exercise(): String {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    PixelGalleryTheme(darkTheme = dark.value, dynamicColor = false) {
                        trackColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
                        if (icons.value) {
                            UnifiedGalleryContainer.instance.Shell(
                                containerColor = MaterialTheme.colorScheme.surface,
                                header = GalleryHeader(title = { Text("标签操作") }), snackbarHost = {},
                            ) { padding ->
                                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                                    Row(Modifier.fillMaxWidth().padding(24.dp).onGloballyPositioned {
                                        val r = it.boundsInWindow()
                                        iconBounds = Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                                    }, horizontalArrangement = Arrangement.SpaceEvenly) {
                                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Icon(Icons.Outlined.Label, null, Modifier.size(40.dp))
                                            Text("添加标签", style = MaterialTheme.typography.titleMedium)
                                        }
                                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Icon(Icons.Outlined.LabelOff, null, Modifier.size(40.dp))
                                            Text("取消标签", style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                }
                            }
                        } else {
                            val page = checkNotNull(DuplicateReviewDialog(state.value, {}, {}, {}))
                            UnifiedGalleryContainer.instance.Shell(
                                containerColor = MaterialTheme.colorScheme.surface,
                                header = page.header, snackbarHost = {},
                            ) { padding -> Box(Modifier.padding(padding)) { page.content() } }
                        }
                    }
                }
            }
            awaitText(R.string.duplicate_status_comparing)
            check(!find("All files") && !find(context.getString(R.string.duplicate_scan_short)))
            check(find("842") && find("1,280"))
            checkTrack()
            snapshot("scan-light")
            instrumentation.runOnMainSync { dark.value = true }
            settle()
            checkTrack()
            snapshot("scan-dark")
            instrumentation.runOnMainSync { state.value = state.value.copy(progress = DuplicateScanProgress(0, 0, DuplicateScanStage.PREPARING)) }
            awaitText(R.string.duplicate_status_preparing)
            check(!find("842") && !find("1,280"))
            snapshot("preparing-dark")
            val empty = DuplicateScanResult(emptyList(), emptyList(), 1280)
            instrumentation.runOnMainSync {
                dark.value = false
                state.value = DuplicateUiState(phase = DuplicatePhase.REVIEW, result = empty)
            }
            awaitText(R.string.duplicate_no_duplicates_short)
            snapshot("result-scan-light")
            val scan = bodyBitmap()
            instrumentation.runOnMainSync {
                state.value = state.value.copy(phase = DuplicatePhase.COMPLETE, removedCount = 48)
            }
            awaitText(R.string.duplicate_no_duplicates_short)
            check(!find(context.getString(R.string.duplicate_cleanup_complete)))
            check(!find(context.getString(R.string.duplicate_removed_summary, 48)))
            val cleanup = bodyBitmap()
            check(scan.sameAs(cleanup)) { "Scan and cleanup result pages differ" }
            scan.recycle(); cleanup.recycle()
            snapshot("result-cleanup-light")
            instrumentation.runOnMainSync { dark.value = true }
            settle()
            snapshot("result-dark")
            instrumentation.runOnMainSync { state.value = state.value.copy(cancelled = true) }
            awaitText(R.string.duplicate_cleanup_cancelled)
            check(!find(context.getString(R.string.duplicate_no_duplicates_short)))
            instrumentation.runOnMainSync { state.value = state.value.copy(phase = DuplicatePhase.ERROR, error = "Synthetic unavailable file") }
            awaitText(R.string.duplicate_failed)
            check(find("Synthetic unavailable file") && !find(context.getString(R.string.duplicate_no_duplicates_short)))
            val entry = MediaEntry(1, "file:///unused.jpg", "/unused.jpg", "image/jpeg", 1, 1, 0, 1, 1, 1)
            instrumentation.runOnMainSync {
                state.value = DuplicateUiState(phase = DuplicatePhase.REVIEW,
                    result = DuplicateScanResult(emptyList(), listOf(DuplicateSkippedFile(entry, DuplicateSkipReason.UNREADABLE)), 1))
            }
            awaitText(R.string.duplicate_check_incomplete)
            check(!find(context.getString(R.string.duplicate_no_duplicates_short)))
            instrumentation.runOnMainSync { dark.value = false; icons.value = true }
            awaitLiteral("取消标签")
            snapshot("label-icons-screen")
            val screen = instrumentation.uiAutomation.takeScreenshot()
            check(iconBounds.width() > 0 && iconBounds.height() > 0)
            val crop = Bitmap.createBitmap(screen, iconBounds.left, iconBounds.top, iconBounds.width(), iconBounds.height())
            write(crop, "label-icons"); crop.recycle(); screen.recycle()
            return "PASS: native light/dark scans; preparation hides counts; rounded progress track has no stop dot; no-match scan and cleanup results are pixel-identical; cancellation, error and incomplete check are not labelled empty. Label/LabelOff comparison captured. Screenshots: ${output.absolutePath}"
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun checkTrack() {
        instrumentation.uiAutomation.clearCache()
        fun findRange(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.rangeInfo != null) return node
            for (i in 0 until node.childCount) findRange(node.getChild(i))?.let { return it }
            return null
        }
        val bar = checkNotNull(findRange(instrumentation.uiAutomation.rootInActiveWindow))
        val bounds = Rect().also(bar::getBoundsInScreen)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val inset = (context.resources.displayMetrics.density * 2).roundToInt().coerceAtLeast(2)
        check(screenshot.getPixel(bounds.right - inset, bounds.centerY()) == trackColor) { "Progress end contains a stop marker" }
        screenshot.recycle()
    }
    private fun bodyBitmap(): Bitmap {
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val top = screenshot.height / 6
        return Bitmap.createBitmap(screenshot, 0, top, screenshot.width, screenshot.height - top - 80).also { screenshot.recycle() }
    }
    private fun awaitText(id: Int) = awaitLiteral(context.getString(id))
    private fun awaitLiteral(text: String) {
        repeat(35) { if (find(text)) { settle(); return }; settle() }
        snapshot("failure")
        error("Missing UI text: $text")
    }
    private fun find(text: String): Boolean {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.contains(text) == true) return true
            return (0 until node.childCount).any { visit(node.getChild(it)) }
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun settle() { SystemClock.sleep(450); instrumentation.waitForIdleSync() }
    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().also { write(it, name); it.recycle() }
    }
    private fun write(bitmap: Bitmap, name: String) {
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
