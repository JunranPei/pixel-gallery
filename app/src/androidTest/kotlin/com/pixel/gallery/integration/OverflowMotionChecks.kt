package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.components.GalleryMenuRow
import com.pixel.gallery.ui.components.GalleryOverflowButton
import com.pixel.gallery.ui.components.OverflowMotionProbe
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import java.io.File
import java.util.UUID

internal class OverflowMotionChecks(private val runner: Instrumentation) {
    private val output = File(runner.targetContext.cacheDir, "overflow-motion-qa").apply { mkdirs() }
    private var down = 0L
    fun exercise(): String {
        val expanded = mutableStateOf(false)
        val dark = mutableStateOf(false)
        var actions = 0
        var underlying = 0
        runner.uiAutomation.performGlobalAction(2); SystemClock.sleep(300)
        val a = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
            .setData(android.net.Uri.parse("pixel-gallery-test://overflow/${UUID.randomUUID()}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
        OverflowMotionProbe.samples.clear(); OverflowMotionProbe.enabled = true
        val passed = mutableListOf<String>()
        try {
            runner.runOnMainSync { a.setContent {
                PixelGalleryTheme(dynamicColor = false, darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Albums", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            GalleryOverflowButton(expanded.value, { expanded.value = it }, onSettings = { actions++ }) {
                                GalleryMenuRow("Sort albums", Icons.Outlined.Sort, onClick = { expanded.value = false; actions++ })
                                GalleryMenuRow("Find duplicates", Icons.Outlined.Sort, onClick = { expanded.value = false; actions++ })
                            }
                        }
                        Text("Underlying", Modifier.align(Alignment.Center).clickable { underlying++ }.padding(24.dp))
                    }
                    }
                }
            } }
            await { find("More") != null }
            val anchor = bounds("More")
            tap(anchor)
            await { OverflowMotionProbe.samples.any { it.opening && it.progress >= 1f } }
            check(find("Sort albums") != null && find("Settings") != null)
            snapshot("open-light")
            check(OverflowMotionProbe.samples.count { it.opening && it.progress > 0f && it.progress < 1f } > 3)
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { !expanded.value && find("Underlying") != null }
            check(OverflowMotionProbe.samples.any { !it.opening && it.progress > 0f && it.progress < 1f })
            passed += "opening and retained Back-close render multiple intermediate frames"

            // A tap on the source closes it; another tap during closing reverses the motion.
            tap(anchor); SystemClock.sleep(100); tap(anchor); SystemClock.sleep(90); tap(anchor)
            await { expanded.value && find("Sort albums") != null }
            SystemClock.sleep(650)
            tap(bounds("Sort albums"))
            await { actions == 1 && !expanded.value && find("Underlying") != null }
            check(underlying == 0)
            passed += "interrupted close/reopen and menu action work"

            tap(anchor); SystemClock.sleep(550)
            val display = runner.targetContext.resources.displayMetrics
            tap(Rect(display.widthPixels / 2 - 2, display.heightPixels / 2 - 2, display.widthPixels / 2 + 2, display.heightPixels / 2 + 2))
            await { !expanded.value && find("Underlying") != null }
            check(underlying == 0) { "Outside dismiss clicked through to the page" }
            passed += "outside dismiss does not click through"

            runner.runOnMainSync { dark.value = true }
            SystemClock.sleep(250)
            val darkStart = OverflowMotionProbe.samples.size
            tap(anchor)
            await { OverflowMotionProbe.samples.drop(darkStart).any { it.opening && it.progress >= 1f } }
            snapshot("open-dark")
            tap(bounds("Settings"))
            await { actions == 2 && !expanded.value && find("Underlying") != null }
            passed += "dark theme and Settings action work"
            snapshot("closed")
            File(output, "result.txt").writeText(passed.joinToString("\n"))
            return passed.joinToString("; ")
        } finally {
            OverflowMotionProbe.enabled = false
            File(output, "frames.json").writeText(Gson().toJson(OverflowMotionProbe.samples.toList()))
            OverflowMotionProbe.samples.clear()
            runner.runOnMainSync { a.finish() }
        }
    }
    private fun find(name: String): AccessibilityNodeInfo? {
        runner.uiAutomation.clearCache()
        fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.text?.toString() == name || n.contentDescription?.toString() == name) return n
            repeat(n.childCount) { n.getChild(it)?.let(::visit)?.let { found -> return found } }
            return null
        }
        return runner.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun bounds(name: String): Rect {
        var n = checkNotNull(find(name)) { "Missing $name" }
        while (!n.isClickable && n.parent != null) n = n.parent
        return Rect().also(n::getBoundsInScreen)
    }
    private fun tap(r: Rect) {
        down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, r.exactCenterX(), r.exactCenterY(), 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                runner.sendPointerSync(this); recycle()
            }
        }
    }
    private fun await(condition: () -> Boolean) {
        repeat(80) { if (condition()) return; SystemClock.sleep(100) }
        error("Overflow timeout; samples=${OverflowMotionProbe.samples.takeLast(2)}")
    }
    private fun snapshot(name: String) {
        runner.uiAutomation.takeScreenshot().apply {
            File(output, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}
