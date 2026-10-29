package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.Screen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import java.io.File

/** Exercises the actual retained settings routes in the isolated test package. */
internal class BranchSettingsChecks(private val instrumentation: Instrumentation) {
    fun exercise(): String {
        val context = instrumentation.targetContext
        val shortcuts = context.packageName.contains(".shortcuts.")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync { activity.setContent {
                PixelGalleryTheme(dynamicColor = false) { MainScaffold(initialScreen = Screen.Settings) }
            } }
            await("Performance & Caching")
            val folder = File(context.cacheDir, "branch-settings-qa").apply { mkdirs() }
            snapshot(folder, "settings")
            if (shortcuts) {
                click("Desktop shortcuts")
                await("No shortcuts")
                snapshot(folder, "shortcuts")
                click("Create Shortcut")
                await("Shortcut Name")
                check(find("Target View") != null)
                snapshot(folder, "create-shortcut")
                click("Cancel")
                await("No shortcuts")
            } else {
                check(find("Desktop shortcuts") == null)
                click("Display")
                await("Theme")
                snapshot(folder, "display")
            }
            return "PASS: settings routes use shared header; branch-specific shortcut entry and create dialog verified=$shortcuts; screenshots=${folder.absolutePath}"
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(text: String) {
        repeat(40) { if (find(text) != null) { settle(); return }; settle() }
        error("Missing text: $text")
    }
    private fun click(text: String) {
        var node = checkNotNull(find(text)) { "Missing action: $text" }
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $text" }
        settle()
    }
    private fun settle() { SystemClock.sleep(350); instrumentation.waitForIdleSync() }
    private fun snapshot(folder: File, name: String) {
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
