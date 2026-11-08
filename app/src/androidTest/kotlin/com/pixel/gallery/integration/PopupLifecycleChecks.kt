package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.pixel.gallery.MainActivity
import com.pixel.gallery.model.AlbumSortOrder
import com.pixel.gallery.ui.components.*
import com.pixel.gallery.ui.home.GroupIllustrationPicker
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Native windows and render-layer state only; never captures screenshots. */
internal class PopupLifecycleChecks(private val runner: Instrumentation) {
    fun exercise(): String {
        check(runner.targetContext.packageName == "com.pixel.gallery.memorypatched")
        val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val visible = mutableStateOf(false)
        val kind = mutableStateOf("dialog")
        val dark = mutableStateOf(false)
        val frames = CopyOnWriteArrayList<Map<String, Any>>()
        var attached = 0
        var removed = 0
        val checks = mutableListOf<String>()
        try {
            runner.runOnMainSync { activity.setContent {
                PixelGalleryTheme(dynamicColor = false, darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize()) {
                        Text("Popup test host")
                        if (kind.value == "sort") {
                            AlbumSortDialog(visible.value, { visible.value = false }, "Sort albums",
                                AlbumSortOrder.NAME_ASC, { visible.value = false })
                        } else PopupPresence(visible.value) {
                            val motion = checkNotNull(LocalPopupMotion.current)
                            DisposableEffect(Unit) { attached++; onDispose { removed++ } }
                            if (kind.value == "illustrations") {
                                GroupIllustrationPicker(0, { visible.value = false }, { visible.value = false })
                            } else GalleryDialog(onDismissRequest = { visible.value = false },
                                title = { Text("Popup motion") },
                                text = { Box(Modifier.size(120.dp).graphicsLayer {
                                    if (frames.size < 2000) frames += mapOf("time" to SystemClock.uptimeMillis(),
                                        "opening" to motion.opening, "fraction" to motion.displayedFraction)
                                }) { Text("Content remains until closing ends") } },
                                confirmButton = { Button(onClick = { visible.value = false }) { Text("Done") } })
                        }
                    }
                }
            } }
            SystemClock.sleep(700)
            fun windows(): Int { var count = 0; runner.runOnMainSync {
                count = WindowInspector.getGlobalWindowViews().count { it.isAttachedToWindow }
            }; return count }
            val baseWindows = windows()
            fun set(value: Boolean) = runner.runOnMainSync { visible.value = value }
            fun completeOpen() = await { frames.any { it["opening"] == true && it["fraction"] == 1f } }
            fun sortTitlePresent(): Boolean {
                fun hasTitle(node: AccessibilityNodeInfo): Boolean {
                    if (node.text?.toString() == "Sort albums") return true
                    return (0 until node.childCount).any { index -> node.getChild(index)?.let(::hasTitle) == true }
                }
                return runner.uiAutomation.rootInActiveWindow?.let(::hasTitle) == true
            }
            if (Settings.Global.getFloat(runner.targetContext.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) {
                for (type in listOf("dialog", "sort", "illustrations")) {
                    runner.runOnMainSync { kind.value = type; dark.value = type != "dialog" }
                    frames.clear()
                    set(true)
                    if (type == "dialog") completeOpen()
                    else if (type == "sort") await { sortTitlePresent() }
                    else SystemClock.sleep(250)
                    check(if (type == "sort") windows() == baseWindows else windows() == baseWindows + 1)
                    set(false)
                    await { windows() == baseWindows && removed == attached }
                    if (type == "sort") await { !sortTitlePresent() }
                    checks += "$type with system animations disabled opens and releases its window/content"
                }
                val settledFrames = frames.size
                SystemClock.sleep(700)
                check(frames.size == settledFrames)
                return Gson().toJson(mapOf("passed" to true, "animationsDisabled" to true,
                    "checks" to checks, "attached" to attached, "removed" to removed))
            }
            set(true); completeOpen()
            check(windows() == baseWindows + 1)
            val middle = frames.filter { it["opening"] == true && (it["fraction"] as Float) in .01f.. .99f }
            check(middle.size >= 3) { "Missing opening render frames: $frames" }
            val first = frames.first { it["opening"] == true }
            check(middle.any { kotlin.math.abs((it["fraction"] as Float) -
                ((it["time"] as Long) - (first["time"] as Long)) / 450f) > .12f }) { "Opening was linear" }
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { !visible.value && frames.any { it["opening"] == false && (it["fraction"] as Float) in .05f.. .95f } }
            check(windows() == baseWindows + 1) { "Dialog removed before exit completed" }
            await { windows() == baseWindows && removed == attached }
            val settledFrames = frames.size
            SystemClock.sleep(700)
            check(frames.size == settledFrames) { "Popup kept rendering after disposal" }
            checks += "Native dialog: nonlinear opening, retained Back-close frames, window disposal and no idle frames"

            frames.clear(); set(true); completeOpen()
            set(false)
            await { frames.any { it["opening"] == false && (it["fraction"] as Float) in .15f.. .8f } }
            val start = frames.size
            set(true)
            await { frames.drop(start).any { it["opening"] == true } }
            val resumedIndex = frames.indices.first { it >= start && frames[it]["opening"] == true }
            val before = frames[resumedIndex - 1]["fraction"] as Float
            val resumed = frames[resumedIndex]["fraction"] as Float
            check(kotlin.math.abs(before - resumed) < .25f) { "Interrupted reopen jumped: $before -> $resumed" }
            await { frames.drop(start).any { it["opening"] == true && it["fraction"] == 1f } }
            set(false); await { windows() == baseWindows && removed == attached }
            checks += "Close reversed from current rendered position without dropping the dialog"

            for (type in listOf("sort", "illustrations")) {
                runner.runOnMainSync { kind.value = type; dark.value = true }
                set(true); SystemClock.sleep(650)
                check(if (type == "illustrations") windows() == baseWindows + 1 else windows() == baseWindows)
                set(false); SystemClock.sleep(70)
                if (type == "illustrations") check(windows() == baseWindows + 1)
                await { windows() == baseWindows && removed == attached }
                SystemClock.sleep(550)
                checks += "$type dark-theme enter/exit completed and all owned windows disposed"
            }
            runner.runOnMainSync { kind.value = "dialog" }
            repeat(12) { set(true); SystemClock.sleep(45); set(false); SystemClock.sleep(45) }
            await { windows() == baseWindows && removed == attached }
            checks += "12 rapid enter/exit pairs released every composed popup and native window"
            return Gson().toJson(mapOf("passed" to true, "checks" to checks, "attached" to attached, "removed" to removed))
        } finally {
            File(runner.targetContext.cacheDir, "popup-layer-frames.json").writeText(Gson().toJson(frames))
            runner.runOnMainSync { activity.finish() }
        }
    }

    private fun await(predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < until) { if (predicate()) return; SystemClock.sleep(16) }
        error("Popup lifecycle timeout")
    }
}
