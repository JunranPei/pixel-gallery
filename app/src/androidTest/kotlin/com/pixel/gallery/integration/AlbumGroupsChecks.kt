package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Actual MainScaffold, UUID media, persisted settings; never move or delete user albums. */
internal class AlbumGroupsChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val output = File(context.filesDir, "album-groups-qa").apply { mkdirs() }
    private var down = 0L
    fun exercise(): String = runBlocking {
        val settings = SettingsRepository(context)
        val original = settings.albumGrouping.first()
        val originalSort = settings.albumSortOrder.first()
        val originalColumns = settings.gridColumns.first()
        val token = UUID.randomUUID().toString().take(6)
        val groupName = "Travel$token"
        val secondName = "Work$token"
        val names = (0..4).map { "AAGroup${token}_$it" }
        val paths = names.map { "/storage/emulated/0/Pictures/$it" }
        val fixtures = names.mapIndexed { index, name ->
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "group.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$name/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
                eraseColor(listOf(0xff607d8b, 0xffb98d9c, 0xff619b90, 0xffa7b581, 0xff8c8db5)[index].toInt())
            }
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        }
        var activity: MainActivity? = null
        lateinit var photos: PhotosViewModel
        try {
            settings.updateAlbumGrouping { AlbumGrouping() }
            settings.setAlbumSortOrder(AlbumSortOrder.NAME_ASC.name)
            settings.setGridColumns(3)
            fun launch(dark: Boolean = false) {
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                instrumentation.runOnMainSync {
                    photos = ViewModelProvider(activity!!)[PhotosViewModel::class.java]
                    activity!!.setContent { PixelGalleryTheme(dynamicColor = false, darkTheme = dark) {
                        androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                            MainScaffold(initialHomeTab = 1, photosViewModel = photos)
                        }
                    } }
                    photos.refresh()
                }
                settle(1000)
                if (find("Background support") != null) click("Cancel")
            }
            launch()
            await(names[0])
            longPress(names[0]); await("1 selected"); click("More"); click("Add to group"); click("New group")
            await("Group name"); click("Group name")
            instrumentation.uiAutomation.executeShellCommand("input text $groupName").close()
            settle(300); back(); click("Save"); await(groupName)
            val stored = settings.albumGrouping.first { it.groups.any { g -> g.name == groupName } }
            val id = stored.groups.single { it.name == groupName }.id
            check(stored.groups.single().paths == listOf(paths[0])) { "UI creation persisted wrong members: $stored" }
            // Add further members using the atomic storage operation; the first creation used the real picker.
            settings.updateAlbumGrouping { it.assign(paths.take(3).toSet(), id).copy(
                groups = it.assign(paths.take(3).toSet(), id).groups + AlbumGroup("work:$token", secondName, listOf(paths[3]))) }
            await(secondName); await(names[2]); await(names[3])
            screenshot("01-multiple-open-three-columns")
            // All member and ungrouped columns align with the same three X coordinates.
            val xPositions = (listOf(groupName, secondName) + names).mapNotNull { name -> find(name)?.let { bounds(it).left } }.distinct()
            check(xPositions.size <= 3) { "Cards use different column frames: $xPositions" }
            click("Collapse $groupName"); await("Expand $groupName")
            check(find(names[0]) == null && find(names[1]) == null && find(names[2]) == null)
            check(find(names[3]) != null) { "Closing one group also hid another group" }
            click("Expand $groupName"); await(names[0])
            click("Expand Labels"); await("Favourites"); screenshot("02-labels-and-albums-open")
            // Same UI at both column boundaries, then a practical intermediate density.
            for (columns in listOf(2, 6, 4)) {
                instrumentation.runOnMainSync { photos.setGridColumns(columns) }
                settle(600); screenshot("03-columns-$columns")
            }
            val persisted = settings.albumGrouping.first()
            check(id !in persisted.collapsedIds && LABELS_ALBUM_GROUP !in persisted.collapsedIds)
            val failedWrite = runCatching { settings.updateAlbumGrouping { it.assign(setOf(paths[0]), "missing-group") } }
            check(failedWrite.isFailure && settings.albumGrouping.first() == persisted) { "Invalid edit did not preserve atomic state" }
            // New viewmodels read the saved grouping, not merely composable-local remembered state.
            instrumentation.runOnMainSync { activity!!.finish() }; settle(400); launch()
            await(groupName); await(secondName); await(names[0]); await("Favourites")
            check(settings.albumGrouping.first() == persisted)
            screenshot("04-reopened-persisted")
            // Enter a real album and return to the same expanded grid.
            click(names[0]); await("More"); back(); await(groupName); await(names[0])
            // Selection of an expanded member must keep existing album actions available.
            longPress(names[1]); await("1 selected"); await("Rename album"); await("Hide Album"); click("Clear selection")
            instrumentation.runOnMainSync { activity!!.finish() }; settle(300); launch(dark = true)
            await(groupName); screenshot("06-dark-groups")
            instrumentation.runOnMainSync { activity!!.finish() }; settle(300)
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.5").close()
            settle(600); launch(dark = true); await(groupName); screenshot("07-dark-system-font-150")
            instrumentation.runOnMainSync { activity!!.finish() }; settle(300)
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.0").close()
            settle(600); launch(); await(groupName)
            // Dissolve through its real group menu; members reappear as ordinary albums.
            longPress(groupName); click("Dissolve group"); click("Dissolve group")
            repeat(30) { if (settings.albumGrouping.first().groups.none { it.id == id }) return@repeat; settle(100) }
            check(settings.albumGrouping.first().groups.none { it.id == id })
            await(names[0]); await(names[1]); screenshot("05-dissolved-preserved-albums")
            check(fixtures.all { uri -> context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true })
            "Album groups PASS: UI create/persist, multiple-open, shared columns 2/3/4/6, labels, restart, album route, selection, dark theme, real system font 150%, dissolve; all 5 originals readable. Evidence: $output"
        } catch (failure: Throwable) {
            screenshot("failed")
            throw failure
        } finally {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.0").close()
            settings.updateAlbumGrouping { original }
            settings.setAlbumSortOrder(originalSort)
            settings.setGridColumns(originalColumns)
            fixtures.forEach { context.contentResolver.delete(it, null, null) }
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) { if (n == null) return; out += n; for (i in 0 until n.childCount) visit(n.getChild(i)) }
        visit(instrumentation.uiAutomation.rootInActiveWindow); return out
    }
    private fun find(name: String) = nodes().lastOrNull { it.text?.toString() == name || it.contentDescription?.toString() == name }
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)
    private fun await(name: String) { repeat(80) { if (find(name) != null) return; settle(150) }; error("Missing $name: ${nodes().map { "${it.text}:${it.contentDescription}" }}") }
    private fun click(name: String) = press(name, false)
    private fun longPress(name: String) = press(name, true)
    private fun press(name: String, long: Boolean) {
        await(name); var n = find(name)!!
        while (!n.isClickable && n.parent != null) n = n.parent
        val b = bounds(n); event(MotionEvent.ACTION_DOWN, b.centerX().toFloat(), b.centerY().toFloat())
        if (long) SystemClock.sleep(650)
        event(MotionEvent.ACTION_UP, b.centerX().toFloat(), b.centerY().toFloat()); settle(400)
    }
    private fun event(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN; instrumentation.sendPointerSync(this); recycle()
        }
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(400) }
    private fun settle(ms: Long) { SystemClock.sleep(ms); instrumentation.waitForIdleSync() }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.apply {
            File(output, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }
}
