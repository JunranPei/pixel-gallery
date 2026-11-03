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
        File(output, "original-grouping.json").writeText(com.google.gson.Gson().toJson(original))
        val originalSort = settings.albumSortOrder.first()
        val originalColumns = settings.albumGridColumns.first()
        val originalPickerColumns = settings.pageGridColumns("groups:picker").first()
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
            settings.setAlbumGridColumns(3)
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
            longPress(names[0]); await("1 selected"); click("More"); click("Add to group")
            click("Search groups")
            enterQuery(groupName)
            hideKeyboard(activity!!); screenshot("picker-create-from-search")
            click("Create group “$groupName”"); await("Joined")
            dismissPicker(); click("Clear selection"); await(groupName)
            val stored = settings.albumGrouping.first { it.groups.any { g -> g.name == groupName } }
            val id = stored.groups.single { it.name == groupName }.id
            check(stored.groups.single().paths == listOf(paths[0])) { "UI creation persisted wrong members: $stored" }
            // Add further members using the atomic storage operation; the first creation used the real picker.
            settings.updateAlbumGrouping { it.assign(paths.take(3).toSet(), id).copy(
                groups = it.assign(paths.take(3).toSet(), id).groups + AlbumGroup("work:$token", secondName, listOf(paths[3]))) }
            await(secondName); await(names[2]); await(names[3])
            screenshot("01-multiple-open-three-columns")
            verifyTopStretch(names[1])
            longPress(groupName); click("Album settings"); screenshot("group-settings")
            screenshot("dialog-rename"); click("Cancel"); click("Clear selection")
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
                instrumentation.runOnMainSync { photos.setAlbumGridColumns(columns) }
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
            click(names[0]); await("Back"); back(); await(groupName); await(names[0])
            // Selection of an expanded member must keep existing album actions available.
            longPress(names[1]); await("1 selected"); await("Album settings"); await("Hide Album"); click("Clear selection")
            instrumentation.runOnMainSync { activity!!.finish() }; settle(300); launch(dark = true)
            await(groupName); screenshot("06-dark-groups")
            instrumentation.runOnMainSync { activity!!.finish() }; settle(300)
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.5").close()
            settle(600); launch(dark = true); await(groupName); screenshot("07-dark-system-font-150")
            instrumentation.runOnMainSync { activity!!.finish() }; settle(300)
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.0").close()
            settle(600); launch(); await(groupName)
            // Dissolve through its real group menu; members reappear as ordinary albums.
            longPress(groupName); click("More"); click("Dissolve group"); screenshot("dialog-dissolve"); click("Dissolve group")
            repeat(30) { if (settings.albumGrouping.first().groups.none { it.id == id }) return@repeat; settle(100) }
            check(settings.albumGrouping.first().groups.none { it.id == id })
            await(names[0]); await(names[1]); screenshot("05-dissolved-preserved-albums")
            // Keep the picker open while loading 100 candidates; verify bounded lazy content and exact search.
            longPress(names[0]); click("More"); click("Add to group"); await("Search groups")
            val beforeScale = settings.albumGrouping.first()
            val hundred = (1..100).map { n -> AlbumGroup("scale:$n", "GroupQA" + n.toString().padStart(3, '0')) }
            settings.updateAlbumGrouping { it.copy(groups = hundred, collapsedIds = hundred.map { g -> g.id }.toSet() + LABELS_ALBUM_GROUP) }
            await("GroupQA001")
            val visibleGroupNames = nodes().mapNotNull { it.text?.toString() }.filter { it.matches(Regex("GroupQA[0-9]{3}")) }
            check(visibleGroupNames.size in 2..40) { "Not a bounded lazy picker: ${visibleGroupNames.size} visible groups" }
            screenshot("picker-100-groups")
            val homeDensity = settings.gridColumns.first()
            val albumsDensity = settings.albumGridColumns.first()
            pinchPicker(0.4f)
            repeat(30) { if (settings.pageGridColumns("groups:picker").first() != 6) settle(100) }
            check(settings.pageGridColumns("groups:picker").first() == 6) { "Pinch did not reach 6 columns" }
            screenshot("picker-six-columns")
            check(settings.gridColumns.first() == homeDensity && settings.albumGridColumns.first() == albumsDensity)
            check(settings.albumGrouping.first().groups.all { it.paths.isEmpty() }) { "Pinch clicked a group" }
            pinchPicker(2.5f)
            repeat(30) { if (settings.pageGridColumns("groups:picker").first() != 2) settle(100) }
            check(settings.pageGridColumns("groups:picker").first() == 2) { "Spread did not reach 2 columns" }
            screenshot("picker-two-columns")
            settings.setPageGridColumns("groups:picker", 3)
            settle(500)
            click("Search groups")
            enterQuery("groupqa099")
            hideKeyboard(activity!!); await("GroupQA099")
            check(find("GroupQA098") == null) // Earlier home-grid groups remain visible above the sheet.
            screenshot("picker-search-one-of-100")
            click("GroupQA099")
            val assigned = settings.albumGrouping.first { it.groups.any { g -> g.id == "scale:99" && paths[0] in g.paths } }
            check(assigned.groups.single { paths[0] in it.paths }.id == "scale:99")
            await("Joined"); dismissPicker(); click("Clear selection")
            settings.updateAlbumGrouping { beforeScale }; settle(500)
            // A new name can create and assign without another destination-selection round trip.
            longPress(names[0]); click("More"); click("Add to group"); click("Search groups")
            val newName = "NewGroup$token"
            enterQuery(newName)
            hideKeyboard(activity!!); await("Create group “$newName”")
            screenshot("picker-empty-create-from-search")
            click("Create group “$newName”")
            val created = settings.albumGrouping.first { it.groups.any { g -> g.name == newName && paths[0] in g.paths } }
            await("Joined")
            dismissPicker(); click("Clear selection")
            longPress(names[0]); click("More"); click("Add to group")
            await(newName); await("Joined")
            screenshot("picker-current-and-remove")
            click(newName)
            check(settings.albumGrouping.first { state -> state.groups.none { paths[0] in it.paths } }.groups.any { it.name == newName })
            dismissPicker(); click("Clear selection")
            // Dismissing retains both selection and membership.
            longPress(names[1]); click("More"); click("Add to group")
            val beforeCancel = settings.albumGrouping.first()
            dismissPicker(); await("1 selected")
            check(settings.albumGrouping.first() == beforeCancel)
            click("Clear selection")
            check(fixtures.all { uri -> context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true })
            "Album groups PASS: UI create/persist, multiple-open, shared columns 2/3/4/6, labels, restart, album route, selection, dark theme, real system font 150%, dissolve, 100-group bounded search, exact assignment, create-from-search, remove, cancel; all 5 originals readable. Evidence: $output"
        } catch (failure: Throwable) {
            screenshot("failed")
            throw failure
        } finally {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.0").close()
            settings.updateAlbumGrouping { original }
            settings.setAlbumSortOrder(originalSort)
            settings.setAlbumGridColumns(originalColumns)
            settings.setPageGridColumns("groups:picker", originalPickerColumns)
            fixtures.forEach { context.contentResolver.delete(it, null, null) }
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }
    private fun pinchPicker(factor: Float) {
        var node = find("GroupQA001")!!
        while (!node.isClickable && node.parent != null) node = node.parent
        val card = bounds(node)
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        val cx = width / 2f
        val cy = card.top + card.width() / 2f
        val start = width * (if (factor < 1f) .28f else .11f)
        fun send(action: Int, radius: Float, count: Int) {
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val props = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { MotionEvent.PointerCoords().apply { x = cx + (if (it == 0) -radius else radius); y = cy; pressure = 1f; size = 1f } }
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).apply {
                instrumentation.sendPointerSync(this); recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, start, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start, 2)
        repeat(30) { i -> send(MotionEvent.ACTION_MOVE, start * (1f + (factor - 1f) * (i + 1) / 30f), 2); SystemClock.sleep(20) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start * factor, 2)
        send(MotionEvent.ACTION_UP, start * factor, 1)
        settle(500)
    }
    private fun enterQuery(query: String) {
        val field = nodes().last { it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }))
        settle(400)
    }
    private fun dismissPicker() { back(); back(); await("1 selected") }
    private fun hideKeyboard(activity: MainActivity) {
        instrumentation.runOnMainSync {
            val input = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        settle(400)
    }
    private fun verifyTopStretch(member: String) {
        var node = find(member)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        val rect = bounds(node)
        val x = rect.centerX()
        val rest = instrumentation.uiAutomation.takeScreenshot()!!
        val coverColor = rest.getPixel(x, rect.top + rect.width() / 2)
        val backgroundColor = rest.getPixel(x, rect.top - 8)
        fun close(a: Int, b: Int): Boolean = listOf(0, 8, 16).all { shift ->
            kotlin.math.abs(((a ushr shift) and 255) - ((b ushr shift) and 255)) <= 10
        }
        fun edge(bitmap: Bitmap, color: Int): Int = ((rect.top - 120).coerceAtLeast(0)..(rect.top + 160)).firstOrNull { y ->
            close(bitmap.getPixel(x, y), color) && close(bitmap.getPixel(x, y + 1), color)
        } ?: error("Missing color edge during stretch")
        val restingCover = edge(rest, coverColor)
        val restingBackground = edge(rest, backgroundColor)
        event(MotionEvent.ACTION_DOWN, 520f, 450f)
        repeat(20) { i -> event(MotionEvent.ACTION_MOVE, 520f, 450f + (i + 1) * 55f); SystemClock.sleep(20) }
        SystemClock.sleep(120)
        val held = instrumentation.uiAutomation.takeScreenshot()!!
        val heldCover = edge(held, coverColor)
        val heldBackground = edge(held, backgroundColor)
        File(output, "08-top-stretch-held.png").outputStream().use { held.compress(Bitmap.CompressFormat.PNG, 100, it) }
        event(MotionEvent.ACTION_UP, 520f, 1550f); settle(700)
        val drift = kotlin.math.abs((heldCover - heldBackground) - (restingCover - restingBackground))
        File(output, "stretch-metrics.txt").writeText("rest cover=$restingCover background=$restingBackground; held cover=$heldCover background=$heldBackground; relative drift=$drift px")
        rest.recycle(); held.recycle()
        check(drift <= 6) { "Background drifts from album during top stretch: $drift px" }
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
