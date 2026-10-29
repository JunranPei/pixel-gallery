package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.pixel.gallery.MainActivity
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.ui.UnifiedGalleryContainer
import com.pixel.gallery.ui.labels.LabelPhotosScreen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import java.io.File

/** Holds loading open explicitly so a brief empty-state flash cannot pass unnoticed. */
internal class LabelLoadingChecks(private val instrumentation: Instrumentation) {
    fun exercise(): String {
        val context = instrumentation.targetContext
        val emptyText = context.getString(R.string.labels_empty_contents)
        val items = mutableStateOf<List<GridItem>?>(null)
        val label = mutableStateOf(LabelEntry(9001, "Loading label", "loading label"))
        val output = File(context.cacheDir, "label-loading-qa").apply { mkdirs() }
        val image = File(output, "sample.png")
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(90, 130, 110))
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val photo = GridItem.Photo(MediaEntry(
            contentId = 9001, uri = image.toURI().toString(), path = image.absolutePath,
            sourceMimeType = "image/png", width = 32, height = 32, sourceRotationDegrees = 0,
            sizeBytes = image.length(), dateAddedSecs = 1, dateModifiedMillis = 1000,
        ))
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    PixelGalleryTheme(dynamicColor = false) {
                        val page = LabelPhotosScreen(
                            label = label.value, items = items.value,
                            selectedIds = emptySet(), columns = 3, gridState = rememberLazyGridState(),
                            onBack = {}, onNavigateToViewer = {}, onSelectionChange = {},
                            onToggleSelection = {}, onColumnsChange = {}, quickSort = null,
                            onToggleQuickSort = {},
                        )
                        UnifiedGalleryContainer.instance.Shell(
                            containerColor = MaterialTheme.colorScheme.surface,
                            header = page.header, snackbarHost = {},
                        ) { padding -> Box(Modifier.padding(padding)) { page.content() } }
                    }
                }
            }
            repeat(30) {
                if (!find("Loading label")) settle()
            }
            snapshot(output, "initial")
            check(find("Loading label")) { "Label page did not appear: ${instrumentation.uiAutomation.rootInActiveWindow}" }
            repeat(8) {
                check(!find(emptyText)) { "Empty message appeared before grouping completed" }
                SystemClock.sleep(80)
            }
            snapshot(output, "loading")
            instrumentation.runOnMainSync { items.value = listOf(photo) }
            settle()
            check(!find(emptyText)) { "Populated label displays empty message" }
            snapshot(output, "populated")
            instrumentation.runOnMainSync { items.value = emptyList() }
            settle()
            check(find(emptyText)) { "Confirmed empty label must display the empty message" }
            snapshot(output, "empty")
            // Opening another label must discard the previous label's empty state.
            instrumentation.runOnMainSync {
                label.value = LabelEntry(9002, "Next label", "next label")
                items.value = null
            }
            settle()
            check(find("Next label") && !find(emptyText)) { "Old empty state leaked into the next label" }
            instrumentation.runOnMainSync { items.value = listOf(photo) }
            settle()
            check(!find(emptyText))
            instrumentation.runOnMainSync { items.value = emptyList() }
            settle()
            check(find(emptyText)) { "Last-file removal failed to show the empty state" }
            return "PASS: pending load stays quiet; populated label has no empty message; confirmed empty label and final-file removal show the message; next label does not inherit empty state. Screenshots: ${output.absolutePath}"
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun settle() {
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
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

    private fun snapshot(output: File, name: String) {
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
