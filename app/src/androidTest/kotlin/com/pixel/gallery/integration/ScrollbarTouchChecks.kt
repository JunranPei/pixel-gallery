package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.components.GalleryScrollbar
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real DOWN/MOVE/UP/CANCEL events against the production composable, using synthetic grid items. */
internal class ScrollbarTouchChecks(private val instrumentation: Instrumentation) {
    private val state = LazyGridState()
    private val itemCount = mutableStateOf(200)
    private val showScrollbar = mutableStateOf(true)
    private val showLabels = mutableStateOf(true)
    private var bounds = Rect.Zero
    private var engaged = false
    private var clicks = 0
    private var labelIndex = -1
    private val density = instrumentation.targetContext.resources.displayMetrics.density
    private val report = mutableListOf<String>()
    private val output = File(instrumentation.targetContext.cacheDir, "scrollbar-qa").apply { mkdirs() }
    private var downTime = 0L

    fun exercise(): String {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        instrumentation.runOnMainSync {
            activity.setContent {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta)) {
                    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(300.dp, 460.dp).onGloballyPositioned { bounds = it.boundsInWindow() }) {
                            LazyVerticalGrid(columns = GridCells.Fixed(3), state = state, modifier = Modifier.fillMaxSize()) {
                                items(itemCount.value) { index ->
                                    Box(Modifier.fillMaxWidth().height(80.dp).background(Color(0xFFE0E0E0))
                                        .clickable { clicks++ }, contentAlignment = Alignment.Center) { Text("Item $index") }
                                }
                            }
                            if (showScrollbar.value) {
                                GalleryScrollbar(
                                    state, Modifier.align(Alignment.CenterEnd),
                                    positionLabelProvider = if (showLabels.value) ({ index -> labelIndex = index; "Position $index" }) else null,
                                    onDragStateChanged = { engaged = it }
                                )
                            }
                        }
                    }
                }
            }
        }
        settle(1300)
        check(bounds.width > 0) { "Grid was not laid out" }
        check(handlePixels(snapshot("initial-hidden")) == null) { "Initial handle should be hidden" }

        wake()
        val measurementX = bounds.right - 8 * density
        val measurementY = thumbCenter()
        event(MotionEvent.ACTION_DOWN, measurementX, measurementY)
        settle(80)
        check(engaged) { "Handle did not engage after scrolling" }
        assertDimensions(snapshot("visible-handle"))
        event(MotionEvent.ACTION_UP, measurementX, measurementY)
        settle(80)
        report += "Visible handle: 10 x 40 dp, 4 dp from right edge; outer touch frame: 26 x 40 dp."

        // Both transparent padding regions are part of the handle's exact target.
        for (x in listOf(bounds.right - 25 * density, bounds.right - density)) {
            wake()
            val y = thumbCenter()
            event(MotionEvent.ACTION_DOWN, x, y)
            settle(100)
            check(engaged) { "Handle did not engage on DOWN at x=$x" }
            event(MotionEvent.ACTION_MOVE, x, y + density)
            settle(80)
            check(engaged) { "One-dp movement required slop" }
            event(MotionEvent.ACTION_UP, x, y + density)
            settle(100)
            check(!engaged) { "Handle did not release on UP" }
        }
        report += "Start/end padding responds on DOWN; one-dp movement works without touch slop."

        wake()
        val outsideClicks = clicks
        val outsideX = bounds.right - 28 * density
        val outsideY = thumbCenter()
        event(MotionEvent.ACTION_DOWN, outsideX, outsideY)
        settle(50)
        check(!engaged) { "Touch outside 26 dp intercepted" }
        event(MotionEvent.ACTION_UP, outsideX, outsideY)
        settle(100)
        check(clicks == outsideClicks + 1) { "Photo outside handle target was blocked" }
        report += "Photo tap outside the exact 26-dp frame passes through."

        wake()
        val x = bounds.right - 8 * density
        event(MotionEvent.ACTION_DOWN, x, thumbCenter())
        event(MotionEvent.ACTION_MOVE, x, bounds.bottom - density)
        settle(150)
        check(!state.canScrollForward) { "Drag did not reach last row" }
        check(labelIndex == 199 && engaged) { "Bottom position label/callback lost: $labelIndex/$engaged" }
        settle(1300)
        check(handlePixels(snapshot("held-at-bottom")) != null && engaged) { "Held handle disappeared" }
        event(MotionEvent.ACTION_MOVE, x, bounds.top + density)
        settle(150)
        check(!state.canScrollBackward && labelIndex == 0) { "Drag did not reach first row" }
        snapshot("drag-at-top")
        event(MotionEvent.ACTION_CANCEL, x, bounds.top + density)
        settle(100)
        check(!engaged) { "CANCEL did not reset drag callback" }
        settle(1200)
        check(handlePixels(snapshot("after-cancel-hidden")) == null) { "Idle handle did not hide" }
        report += "Both endpoints and labels work; held handle stays visible; CANCEL resets callback and idle hides."

        // Retained extra feature: only a vertical drag on the track starts seeking.
        wake()
        val trackY = bounds.top + bounds.height * .7f
        val firstIndex = state.firstVisibleItemIndex
        event(MotionEvent.ACTION_DOWN, x, trackY)
        event(MotionEvent.ACTION_UP, x, trackY)
        settle(80)
        check(!engaged && state.firstVisibleItemIndex == firstIndex) { "Track tap unexpectedly jumped" }
        event(MotionEvent.ACTION_DOWN, x, trackY)
        event(MotionEvent.ACTION_MOVE, x, trackY + 40 * density)
        settle(80)
        check(engaged && state.firstVisibleItemIndex > firstIndex) { "Track drag not preserved" }
        val trackLabel = snapshot("track-label")
        check(hasPositionHint(trackLabel)) { "Track drag did not render its position hint" }
        event(MotionEvent.ACTION_UP, x, trackY + 40 * density)
        settle(100)
        check(!engaged) { "Track drag did not release callback" }
        report += "Track drag and position hint retained; track taps do not seek; callback includes track dragging."

        settle(1300)
        val hiddenClicks = clicks
        val edgeY = bounds.top + 40 * density
        event(MotionEvent.ACTION_DOWN, x, edgeY)
        event(MotionEvent.ACTION_UP, x, edgeY)
        settle(100)
        check(clicks == hiddenClicks + 1 && !engaged) { "Hidden scrollbar intercepted edge photo" }
        report += "Hidden edge is touchable by the photo grid."

        instrumentation.runOnMainSync { itemCount.value = 3 }
        settle(300)
        check(!state.canScrollBackward && !state.canScrollForward) { "Small grid was not fitted" }
        check(handlePixels(snapshot("fitted-grid")) == null) { "Handle shown on non-scrollable grid" }
        val fittedClicks = clicks
        event(MotionEvent.ACTION_DOWN, x, edgeY)
        event(MotionEvent.ACTION_UP, x, edgeY)
        settle(100)
        check(clicks == fittedClicks + 1 && !engaged) { "Fitted grid was intercepted" }
        report += "Content fitting viewport: no handle, no touch interception."

        instrumentation.runOnMainSync { itemCount.value = 200; showLabels.value = false }
        settle(150)
        wake()
        event(MotionEvent.ACTION_DOWN, x, thumbCenter())
        settle(80)
        check(engaged) { "No-label scrollbar cannot be dragged" }
        snapshot("without-label")
        instrumentation.runOnMainSync { showScrollbar.value = false }
        settle(100)
        check(!engaged) { "Disposal left drag callback active" }
        event(MotionEvent.ACTION_CANCEL, x, thumbCenter())
        report += "No-label callers work; removal during drag resets callback."
        instrumentation.runOnMainSync { activity.finish() }
        return report.joinToString("\n") + "\nScreenshots: ${output.absolutePath}"
    }

    private fun wake() {
        runBlocking(Dispatchers.Main) { state.scrollToItem(if (state.firstVisibleItemIndex == 30) 33 else 30) }
        settle(100)
    }

    private fun thumbCenter(): Float {
        val info = state.layoutInfo
        val range = info.totalItemsCount - info.visibleItemsInfo.size
        val fraction = if (range > 0) state.firstVisibleItemIndex.toFloat() / range else 0f
        return bounds.top + fraction * (bounds.height - 40 * density) + 20 * density
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

    private fun settle(millis: Long) { SystemClock.sleep(millis); instrumentation.waitForIdleSync() }
    private fun snapshot(name: String): Bitmap {
        // Main-thread idle does not wait for the emulator's compositor to present.
        settle(350)
        return instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun hasPositionHint(bitmap: Bitmap): Boolean {
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until (bounds.right - 26 * density).toInt()) {
                if (bitmap.getPixel(x, y) and 0xFFFFFF == 0xFF00FF) return true
            }
        }
        return false
    }

    private fun handlePixels(bitmap: Bitmap): android.graphics.Rect? {
        var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in (bounds.right - 26 * density).toInt() until bounds.right.toInt()) {
                if (bitmap.getPixel(x, y) and 0xFFFFFF == 0xFF00FF) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right < 0) null else android.graphics.Rect(left, top, right + 1, bottom + 1)
    }

    private fun assertDimensions(bitmap: Bitmap) {
        val handle = checkNotNull(handlePixels(bitmap)) { "No visible handle" }
        check(abs(handle.width() - (10 * density).roundToInt()) <= 1) { "Handle width: $handle" }
        check(abs(handle.height() - (40 * density).roundToInt()) <= 1) { "Handle height: $handle" }
        check(abs(bounds.right - handle.right - 4 * density) <= 1) { "Handle edge margin: $handle, $bounds" }
    }
}
