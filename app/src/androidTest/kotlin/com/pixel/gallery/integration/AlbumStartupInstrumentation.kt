package com.pixel.gallery.integration

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.pixel.gallery.MainActivity
import com.pixel.gallery.model.Album
import com.pixel.gallery.model.AlbumGrouping
import com.pixel.gallery.ui.home.GroupedAlbumsScreen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import java.io.File

/** Real production grid, with deliberately staggered data delivery; no media/settings mutations. */
class AlbumStartupInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val records = mutableListOf<Map<String, Any>>()
        var activity: MainActivity? = null
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
            val rows = mutableStateOf(emptyList<Album>())
            val grid = LazyGridState()
            runOnMainSync {
                activity!!.setContent {
                    PixelGalleryTheme(dynamicColor = false) {
                        GroupedAlbumsScreen(albums = rows.value, labels = emptyList(), grouping = AlbumGrouping(),
                            bottomPadding = androidx.compose.ui.unit.Dp(0f), gridState = grid, columns = 4,
                            onColumnsChange = {}, selectedPaths = emptySet(), selectionMode = false,
                            onToggleSelection = {}, selectedLabelIds = emptySet(), onToggleLabelSelection = {},
                            onAlbum = {}, onLabel = {}, onLabels = {}, onTrash = {}, onToggleGroup = {})
                    }
                }
            }
            fun settle() { SystemClock.sleep(900); waitForIdleSync() }
            fun record(name: String) { runOnMainSync { records += mapOf("phase" to name,
                "index" to grid.firstVisibleItemIndex, "offset" to grid.firstVisibleItemScrollOffset,
                "key" to (grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == grid.firstVisibleItemIndex }?.key?.toString() ?: "")) } }
            val albums = (0 until 80).map { Album("Album $it", "/test/$it", "", 1) }
            settle(); record("trash-only")
            runOnMainSync { rows.value = albums }; settle(); record("loaded")
            runOnMainSync { grid.requestScrollToItem(0) }; settle()
            runOnMainSync { rows.value = listOf(Album("000 New", "/test/new", "", 1)) + albums }; settle(); record("insert-at-top")
            runOnMainSync { grid.requestScrollToItem(24, 17) }; settle(); record("scrolled")
            runOnMainSync { rows.value = (80 until 84).map { Album("000 More $it", "/test/$it", "", 1) } + rows.value }
            settle(); record("insert-while-scrolled")
            val top = records.filter { it["phase"] in listOf("loaded", "insert-at-top") }
            val before = records.first { it["phase"] == "scrolled" }
            val after = records.last()
            val passed = top.all { it["index"] == 0 && it["offset"] == 0 } && before["key"] == after["key"] && before["offset"] == after["offset"]
            runOnMainSync { activity!!.finish() }; activity = null
            val report = Gson().toJson(mapOf("passed" to passed, "records" to records))
            File(targetContext.filesDir, "album-startup-result.json").writeText(report)
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report) })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) })
        } finally { activity?.let { runOnMainSync { it.finish() } } }
    }
}
