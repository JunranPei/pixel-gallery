package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.pixel.gallery.MainActivity
import com.pixel.gallery.R
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.quicksort.*
import com.pixel.gallery.ui.settings.DisplaySettingsScreen
import com.pixel.gallery.ui.settings.PerformanceSettingsScreen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.transfer.TransferDestinationContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/** UI fixtures execute callbacks only; filesystem tests live in the synthetic safety suite. */
internal class UndoHandednessTouchChecks(private val instrumentation: Instrumentation) {
    private val mode = mutableIntStateOf(0)
    private val hand = mutableStateOf(Handedness.RIGHT)
    private val targets = (1..3).map { TransferDestination("hand-fixture-$it", "Album $it", "/Pictures/HandFixture/$it") }
    private val gestures = QuickSortGestureState()
    private var root = ComposeRect.Zero
    private var undoClicks = 0
    private var copyClicks = 0
    private var moveClicks = 0
    private var resolved: ConflictPolicy? = null
    private var dockHeight = 0
    private val output = File(instrumentation.targetContext.cacheDir, "undo-handedness-qa").apply { mkdirs() }

    fun exercise(): String {
        val context = instrumentation.targetContext
        val settings = SettingsRepository(context)
        val originalHand = runBlocking { settings.handedness.first() }
        val originalGrid = runBlocking { settings.glidePersistentGridCacheSize.first() }
        val originalLegacy = runBlocking { settings.glidePersistentCacheSize.first() }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    PixelGalleryTheme(darkTheme = false, dynamicColor = false) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                            .then(if (mode.intValue <= 1) Modifier.statusBarsPadding() else Modifier)
                            .onGloballyPositioned { root = it.boundsInWindow() }) {
                            when (mode.intValue) {
                                0 -> QuickSortDock(QuickSortUiConfig(targets, false, {}, {}, {}, { _, _ -> },
                                    handedness = hand.value, undoAvailable = true, onUndo = { undoClicks++ }),
                                    gestures, emptySet(), Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                        .navigationBarsPadding().padding(bottom = 16.dp), { dockHeight = it })
                                1 -> TransferDestinationContent(targets, emptyList(), "", targets.first(), false, null, null,
                                    {}, {}, {}, {}, { copyClicks++ }, { moveClicks++ }, handedness = hand.value)
                                2 -> DisplaySettingsScreen({}, {})
                                3 -> PerformanceSettingsScreen({})
                                4 -> QuickSortConflictDialog(QuickSortJob("fixture-conflict", targets.first(), emptyList(), 0,
                                    status = QuickSortJobStatus.NeedsConflictDecision, conflictCount = 2),
                                    onResolve = { resolved = it }, onCancel = {})
                                5 -> QuickSortWarningDialog("Original filename is occupied. Files were kept.") {}
                            }
                        }
                    }
                }
            }
            settle()
            find { it.text?.toString() == "取消" }?.let { tap(bounds(it)) }
            settle()
            val undoDescription = context.getString(R.string.qs_undo)
            val rightUndo = bounds(requireNode { it.contentDescription?.toString() == undoDescription })
            check(rightUndo.centerX() > root.center.x)
            check(gestures.targetBounds.getValue(targets.first().stableKey).center.x > root.center.x)
            check(rightUndo.bottom <= gestures.targetBounds.values.minOf { it.top })
            check(dockHeight > 0)
            snapshot("quick-sort-right")
            tap(rightUndo)
            check(undoClicks == 1)
            changeHand(Handedness.LEFT)
            val leftUndo = bounds(requireNode { it.contentDescription?.toString() == undoDescription })
            check(leftUndo.centerX() < root.center.x)
            check(gestures.targetBounds.getValue(targets.first().stableKey).center.x < root.center.x)
            snapshot("quick-sort-left")
            tap(leftUndo)
            check(undoClicks == 2)

            changeMode(1)
            val copyDescription = context.getString(R.string.dialog_copy)
            val moveDescription = context.getString(R.string.dialog_move)
            val leftCopy = bounds(requireNode { it.contentDescription?.toString() == copyDescription })
            check(leftCopy.centerX() < root.center.x)
            tap(leftCopy)
            tap(bounds(requireNode { it.contentDescription?.toString() == moveDescription }))
            check(copyClicks == 1 && moveClicks == 1)
            snapshot("copy-move-left")
            changeHand(Handedness.RIGHT)
            check(bounds(requireNode { it.contentDescription?.toString() == copyDescription }).centerX() > root.center.x)
            snapshot("copy-move-right")

            runBlocking { settings.setHandedness(Handedness.RIGHT) }
            changeMode(2)
            tap(bounds(requireNode { it.text?.toString() == context.getString(R.string.display_left_handed) }))
            check(runBlocking { settings.handedness.first() } == Handedness.LEFT)
            snapshot("display-left-handed")
            tap(bounds(requireNode { it.text?.toString() == context.getString(R.string.display_right_handed) }))
            check(runBlocking { settings.handedness.first() } == Handedness.RIGHT)

            changeMode(3)
            val slider = requireNode { it.rangeInfo?.min == 50f && it.rangeInfo?.max == 2000f }
            val sliderBounds = bounds(slider)
            val eventAt = SystemClock.uptimeMillis()
            val x = sliderBounds.left + sliderBounds.width() * .7f
            event(MotionEvent.ACTION_DOWN, x, sliderBounds.exactCenterY(), eventAt)
            event(MotionEvent.ACTION_UP, x, sliderBounds.exactCenterY(), eventAt)
            settle()
            check(runBlocking { settings.glidePersistentGridCacheSize.first() } != originalGrid)
            check(runBlocking { settings.glidePersistentCacheSize.first() } == originalLegacy)
            snapshot("persistent-grid-changed")

            changeMode(4)
            requireNode { it.text?.toString() == context.getString(R.string.qs_keep_both) }
            requireNode { it.text?.toString() == context.getString(R.string.qs_skip_conflicts) }
            val replace = requireNode { it.text?.toString() == context.getString(R.string.qs_replace) }
            snapshot("same-name-three-choices")
            tap(bounds(replace))
            tap(bounds(requireNode { it.text?.toString() == context.getString(R.string.qs_continue) }))
            check(resolved == ConflictPolicy.REPLACE)
            changeMode(5)
            snapshot("undo-warning")
            return "Undo capsule above album row; right/left dock and Copy/Move controls; persisted Display hand preference; Grid slider writes its own key; three conflict choices and shared warning dialog checked. Screenshots: ${output.path}"
        } finally {
            runBlocking { settings.setHandedness(originalHand); settings.setGlidePersistentGridCacheSize(originalGrid) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun changeHand(value: Handedness) { instrumentation.runOnMainSync { hand.value = value }; settle() }
    private fun changeMode(value: Int) { instrumentation.runOnMainSync { mode.intValue = value }; settle() }
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(450) }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun tap(rect: Rect) {
        val down = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), down)
        event(MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), down)
        settle()
    }
    private fun event(action: Int, x: Float, y: Float, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        instrumentation.sendPointerSync(event)
        event.recycle()
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { match -> return match } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun requireNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(8) { find(predicate)?.let { return it }; SystemClock.sleep(150) }
        error("Expected UI node not found")
    }
    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } ?: error("Screenshot unavailable")
    }
}
