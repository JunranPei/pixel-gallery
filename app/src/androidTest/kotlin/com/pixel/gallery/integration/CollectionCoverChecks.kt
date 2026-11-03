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
import kotlinx.coroutines.withTimeout
import com.pixel.gallery.ui.Screen
import com.pixel.gallery.ui.viewmodel.LabelsViewModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** UUID fixtures only. Exercise persistence through actual menus and the shared half-page. */
internal class CollectionCoverChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val output = File(context.filesDir, "collection-cover-qa").apply { mkdirs() }
    private var down = 0L
    fun exercise(): String = runBlocking {
        val settings = SettingsRepository(context)
        val originalGroups = settings.albumGrouping.first()
        val originalColumns = settings.albumGridColumns.first()
        val token = UUID.randomUUID().toString().take(6)
        val name = "AACover$token"
        val path = "/storage/emulated/0/Pictures/$name"
        val groupId = "cover:$token"
        val groupName = "Cover group $token"
        val albumKey = albumCoverKey(path)
        val groupKey = groupCoverKey(groupId)
        val fixtures = (0..8).map { index ->
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "cover-$index.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$name/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
                eraseColor(listOf(0xff607d8b, 0xffb98d9c, 0xff619b90)[index % 3].toInt())
            }
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        }
        var activity: MainActivity? = null
        lateinit var photos: PhotosViewModel
        lateinit var labels: LabelsViewModel
        var labelId: Long? = null
        fun launch(screen: Screen = Screen.Home, dark: Boolean = false) {
            instrumentation.runOnMainSync { activity?.finish() }
            settle(300)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync {
                photos = ViewModelProvider(activity!!)[PhotosViewModel::class.java]
                labels = ViewModelProvider(activity!!)[LabelsViewModel::class.java]
                activity!!.setContent { PixelGalleryTheme(dynamicColor = false, darkTheme = dark) {
                    androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                        MainScaffold(initialScreen = screen, initialHomeTab = 1, photosViewModel = photos)
                    }
                } }
                photos.refresh()
            }
            settle(1000)
            if (find("Background support") != null) click("Cancel")
        }
        suspend fun expectCover(key: String, uri: String?) {
            withTimeout(12000) { settings.collectionCovers.first { it[key] == uri } }
        }
        fun search(query: String) {
            val field = nodes().last { it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }))
            instrumentation.runOnMainSync {
                val input = activity!!.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                input.hideSoftInputFromWindow(activity!!.window.decorView.windowToken, 0)
            }
            settle(400)
        }
        fun firstPhoto(): AccessibilityNodeInfo = nodes().firstOrNull {
            val rect = bounds(it)
            it.isClickable && rect.top > 240 && rect.width() > 180 && kotlin.math.abs(rect.width() - rect.height()) < 20
        } ?: error("No full-page album photo tile")
        fun choosePhoto(): String {
            val rect = bounds(firstPhoto())
            event(MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat())
            event(MotionEvent.ACTION_UP, rect.centerX().toFloat(), rect.centerY().toFloat()); settle(500)
            await("Album settings"); await("Custom cover")
            val filename = nodes().mapNotNull { it.text?.toString() }.first { it.matches(Regex("cover-[0-8]\\.jpg")) }
            return fixtures[filename.removePrefix("cover-").substringBefore('.').toInt()].toString()
        }
        try {
            settings.updateAlbumGrouping { AlbumGrouping(listOf(AlbumGroup(groupId, groupName, listOf(path)))) }
            settings.setAlbumGridColumns(3)
            launch()
            await(name)
            longPress(name); click("Album settings"); await("Automatic")
            screenshot("settings-album-auto")
            click("Choose image"); await(name); settle(700)
            check(find("Save") == null) { "Old cover sheet still visible" }
            screenshot("album-original-photo-grid")
            val pickedAlbum = choosePhoto()
            screenshot("settings-album-draft")
            click("Cancel"); check(settings.collectionCovers.first()[albumKey] == null)
            click("Album settings"); click("Choose image"); await(name); settle(500)
            val savedAlbum = choosePhoto(); click("Save"); expectCover(albumKey, savedAlbum)
            withTimeout(12000) { photos.albums.first { list -> list.any { it.path == path && it.coverUri == savedAlbum } } }
            click("Clear selection")
            instrumentation.runOnMainSync { labels.createLabel("Cover label $token") }
            val label = withTimeout(12000) { labels.labelEntries.first { rows -> rows.any { it.name == "Cover label $token" } } }.single { it.name == "Cover label $token" }
            labelId = label.id
            withTimeout(12000) { labels.isSaving.first { !it } }
            val repositoryField = LabelsViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }
            val labelRepository = repositoryField.get(labels) as com.pixel.gallery.data.repository.LabelRepository
            labelRepository.applyEdits(fixtures.map { it.lastPathSegment!!.toLong() }.toSet(), LabelEditRequest(changes = mapOf(label.id to true)))
            withTimeout(12000) { labels.labels.first { rows -> rows.any { it.label.id == label.id && it.count == 9 } } }
            launch(Screen.Label(label.id))
            await("Cover label $token"); click("More"); click("Album settings"); click("Choose image")
            await("Cover label $token"); settle(500); screenshot("label-original-photo-grid")
            val savedLabel = choosePhoto(); click("Save"); expectCover(labelCoverKey(label.id), savedLabel)
            withTimeout(12000) { labels.labels.first { rows -> rows.any { it.label.id == label.id && it.cover?.uri == savedLabel } } }
            launch(dark = true)
            await(groupName); longPress(groupName); await("1 selected"); click("More"); await("Find duplicates within group"); screenshot("group-overflow-dedup")
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            click("Find duplicates within group")
            lateinit var dedup: com.pixel.gallery.ui.deduplication.DuplicateViewModel
            instrumentation.runOnMainSync { dedup = ViewModelProvider(activity!!)[com.pixel.gallery.ui.deduplication.DuplicateViewModel::class.java] }
            val scanned = withTimeout(20000) { dedup.state.first { it.phase == com.pixel.gallery.ui.deduplication.DuplicatePhase.REVIEW } }
            check(scanned.result!!.scannedCount == 9)
            check(scanned.result!!.groups.isNotEmpty())
            check(scanned.result!!.groups.flatMap { it.entries }.all { it.uri in fixtures.map { uri -> uri.toString() } })
            screenshot("group-dedup-scoped-results")
            instrumentation.runOnMainSync { dedup.dismiss() }; settle(500)
            check(find("Set cover") == null && find("Dissolve group") == null) { "Legacy action popup remains" }
            click("Album settings"); screenshot("settings-group-dark"); click("Choose image")
            await(groupName); settle(500); screenshot("group-original-photo-grid")
            val savedGroup = choosePhoto()
            val nameField = nodes().first { it.isEditable }
            check(nameField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$groupName renamed")
            }))
            click("Save"); expectCover(groupKey, savedGroup)
            check(settings.albumGrouping.first().groups.single { it.id == groupId }.name == "$groupName renamed")
            click("Album settings"); await("Custom cover"); click("Use auto"); click("Save"); expectCover(groupKey, null)
            launch(Screen.Label(label.id))
            await("Cover label $token"); click("More"); click("Album settings"); await("Custom cover")
            click("Use auto"); click("Save"); expectCover(labelCoverKey(label.id), null)
            launch()
            await(name); longPress(name); click("Album settings"); await("Custom cover")
            screenshot("settings-reopened"); click("Use auto"); click("Save"); expectCover(albumKey, null)
            check(fixtures.all { uri -> context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true })
            "Collection settings PASS: pencil edit dialog and group dedup overflow; real album photo grid; label/group native grids; draft cancel, save, restart and automatic reset; dark mode; original fixtures readable. Evidence: $output"
        } catch (failure: Throwable) { screenshot("failed"); throw failure }
        finally {
            settings.setCollectionCover(albumKey, null); settings.setCollectionCover(groupKey, null)
            labelId?.let { id ->
                settings.setCollectionCover(labelCoverKey(id), null)
                instrumentation.runOnMainSync { labels.deleteLabel(id) }
                withTimeout(12000) { labels.labelEntries.first { rows -> rows.none { it.id == id } } }
            }
            settings.updateAlbumGrouping { originalGroups }
            settings.setAlbumGridColumns(originalColumns)
            settings.setPageGridColumns("cover-picker:$albumKey", 3)
            fixtures.forEach { context.contentResolver.delete(it, null, null) }
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }
    private fun pinchPicker(factor: Float) {
        var node = find("cover-8.jpg")!!
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
