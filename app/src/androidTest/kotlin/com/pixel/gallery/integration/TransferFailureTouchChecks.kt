package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pixel.gallery.MainActivity
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.transfer.TransferResultFeedback
import java.io.File

/** Exercise the production result handling; callbacks never modify media. */
internal class TransferFailureTouchChecks(private val instrumentation: Instrumentation) {
    private val summary = mutableStateOf<TransferSummary?>(null)
    private val error = mutableStateOf<String?>(null)
    private val mode = mutableStateOf(TransferMode.COPY)
    private var finished = 0
    private var cleared = 0
    private val entry = MediaEntry(-1, "content://fixture/1", "/fixture/photo.jpg", "image/jpeg", 10, 10, 0, 42, 1, 1000)
    private val output = File(instrumentation.targetContext.cacheDir, "transfer-failure-qa").apply { mkdirs() }

    fun exercise(): String {
        val context = instrumentation.targetContext
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                owner.registry.currentState = Lifecycle.State.RESUMED
                activity.setContent {
                    PixelGalleryTheme(darkTheme = false, dynamicColor = false) {
                        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                            Surface(Modifier.fillMaxSize()) {
                                Text("Select album", Modifier.statusBarsPadding())
                                TransferResultFeedback(summary.value, error.value, mode.value,
                                    onFinished = { finished++; summary.value = null; error.value = null },
                                    onClear = { cleared++; summary.value = null; error.value = null })
                            }
                        }
                    }
                }
            }
            fun show(action: TransferMode, succeeded: Int, reason: String) {
                instrumentation.runOnMainSync {
                    mode.value = action
                    summary.value = TransferSummary(action, succeeded, 0, listOf(TransferItemFailure(entry, reason)))
                }
            }
            show(TransferMode.COPY, 2, "No space left on device")
            requireText(context.getString(R.string.transfer_copy_incomplete))
            requireText(context.getString(R.string.transfer_copy_failed_result, 2, 1))
            requireText(context.getString(R.string.qs_move_no_space))
            check(finished == 0 && cleared == 0) { "Partial result was cleared before acknowledgment" }
            snapshot("copy-partial-failure")
            dismiss()
            check(finished == 1 && cleared == 0)

            show(TransferMode.MOVE, 0, "Source file no longer exists")
            requireText(context.getString(R.string.qs_warning_title))
            requireText(context.getString(R.string.transfer_move_failed_result, 0, 1))
            requireText(context.getString(R.string.transfer_source_missing))
            dismiss()
            check(finished == 1 && cleared == 1) { "All-failed move navigated away" }

            show(TransferMode.MOVE, 1, "Storage permission was not granted")
            requireText(context.getString(R.string.transfer_move_failed_result, 1, 1))
            snapshot("move-partial-failure")
            dismiss()
            check(finished == 2 && cleared == 1)

            instrumentation.runOnMainSync { mode.value = TransferMode.COPY; error.value = "Storage permission was not granted" }
            requireText(context.getString(R.string.transfer_copy_incomplete))
            requireText(context.getString(R.string.qs_move_no_permission))
            dismiss()
            check(cleared == 2)

            instrumentation.runOnMainSync { error.value = "Unexpected provider exception: /private/debug/diagnostic" }
            requireText(context.getString(R.string.transfer_copy_failed))
            check(find("Unexpected provider exception: /private/debug/diagnostic") == null)
            dismiss()
            check(cleared == 3)

            instrumentation.runOnMainSync {
                owner.registry.currentState = Lifecycle.State.CREATED
                summary.value = TransferSummary(TransferMode.MOVE, 0, 0, listOf(TransferItemFailure(entry, "Storage permission denied")))
            }
            settle()
            check(find(context.getString(R.string.qs_warning_title)) == null && cleared == 3)
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
            requireText(context.getString(R.string.transfer_move_failed_result, 0, 1))
            dismiss()
            check(cleared == 4)

            instrumentation.runOnMainSync { summary.value = TransferSummary(TransferMode.COPY, 0, 3, emptyList()) }
            settle()
            check(finished == 3 && cleared == 4) { "Skipped files were reported as failures" }
            check(find(context.getString(R.string.transfer_copy_incomplete)) == null)
            return "Copy/Move partial failures stay until acknowledged; all-failed transfers stay on the destination screen; permission and unknown errors use localized brief reasons; background results defer until resumed; skipped items are not failures. No retry or task buttons. Screenshots: ${output.path}"
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun dismiss() {
        check(find("View tasks") == null && find("Retry failed") == null)
        val node = requireText(instrumentation.targetContext.getString(R.string.dialog_got_it))
        val rect = Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            instrumentation.sendPointerSync(event); event.recycle()
        }
        settle()
    }
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(350) }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun requireText(text: String): AccessibilityNodeInfo {
        instrumentation.uiAutomation.clearCache()
        repeat(20) { find(text)?.let { return it }; SystemClock.sleep(100) }
        error("Missing UI text: $text")
    }
    private fun snapshot(name: String) {
        settle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
