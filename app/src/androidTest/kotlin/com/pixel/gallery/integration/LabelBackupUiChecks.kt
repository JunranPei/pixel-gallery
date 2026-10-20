package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.settings.LabelBackupSettingsBlock
import com.pixel.gallery.ui.settings.SettingsGroup
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.LabelBackupViewModel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real production entry/dialog and Android document picker, with an independent label database. */
internal class LabelBackupUiChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val output = File(context.cacheDir, "label-backup-ui-qa").apply { mkdirs() }
    private var down = 0L

    fun exercise(): String = runBlocking {
        val token = UUID.randomUUID().toString().take(8)
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(output, "$token/files").apply { mkdirs() }
            override fun getCacheDir() = File(output, "$token/cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(isolated, GalleryDatabase::class.java).addCallback(LABEL_DATABASE_CALLBACK).build()
        val media = MediaRepository(isolated, db.mediaDao(), SettingsRepository(isolated))
        val labels = LabelRepository(db, db.labelDao())
        val repository = LabelBackupRepository(isolated, db, media)
        val viewModel = LabelBackupViewModel(repository, isolated)
        val owned = mutableListOf<Uri>()
        val files = mutableListOf<File>()
        val visible = mutableStateOf(true)
        fun download(name: String, data: ByteArray): Uri {
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            owned += uri
            context.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        fun image(name: String, color: Int): Uri {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/BackupUi_$token/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            owned += uri
            context.contentResolver.openOutputStream(uri)!!.use { stream ->
                Bitmap.createBitmap(31, 29, Bitmap.Config.ARGB_8888).apply { eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, stream); recycle() }
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        val source = image("source-$token.png", 0xff123478.toInt())
        val duplicateA = image("copy-a-$token.png", 0xffbb2244.toInt())
        val duplicateB = image("copy-b-$token.png", 0xffbb2244.toInt())
        media.syncWhenMediaMutationsIdle(force = true)
        val id = source.lastPathSegment!!.toLong()
        val tagName = "界面备份 📷 $token"
        val tag = labels.createLabel(tagName)
        labels.createLabel("空标签 $token")
        labels.applyEdits(setOf(id), LabelEditRequest(changes = mapOf(tag to true, 1L to true)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync { activity.setContent { PixelGalleryTheme(dynamicColor = false, darkTheme = false) {
                if (visible.value) SettingsGroup(itemCount = 1, modifier = Modifier.padding(top = 100.dp, start = 20.dp, end = 20.dp)) {
                    LabelBackupSettingsBlock(viewModel)
                } else Text("Other settings page")
            } } }
            await("Label backup")
            val beforeCancel = db.labelDao().getMemberships().toSet()
            click("Label backup"); click("Export labels"); await("SAVE"); back(); await("Label backup")
            check(viewModel.state.value.exportResult == null)
            click("Label backup"); click("Import labels"); awaitPicker(); back(); await("Label backup")
            check(viewModel.state.value.preview == null && beforeCancel == db.labelDao().getMemberships().toSet())

            val exportName = "backup-ui-$token.json"
            click("Label backup"); click("Export labels"); await("SAVE")
            chooseDownloads()
            val field = nodes().first { it.className?.toString() == "android.widget.EditText" }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, exportName)
            }))
            click("SAVE"); await("Labels exported")
            val exported = File("/sdcard/Download/$exportName"); files += exported
            check(exported.isFile)
            val saved = LabelBackupJson.read(exported.reader())
            check(saved.labels.size == 3 && saved.media.size == 1 && saved.media.single().labelKeys.size == 2)
            snapshot("export-success"); click("OK")
            val originalBytes = exported.readBytes()
            click("Label backup"); click("Export labels"); await("SAVE"); chooseDownloads()
            val sameNameField = nodes().first { it.className?.toString() == "android.widget.EditText" }
            check(sameNameField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, exportName)
            }))
            click("SAVE"); await("Labels exported")
            val copies = exported.parentFile!!.listFiles()!!.filter { it.name.startsWith("backup-ui-$token") && it.extension == "json" }
            check(copies.size == 2 && exported.readBytes().contentEquals(originalBytes))
            files += copies.filter { it != exported }
            click("OK")
            labels.deleteLabel(tag)
            db.labelDao().removeMemberships(1L, listOf(id))
            labels.createLabel("Local retained $token")
            fun openImport(filename: String) { click("Label backup"); click("Import labels"); awaitPicker(); chooseDownloads(); click(filename) }
            openImport(exportName); await("Import preview"); await("Matched 1 of 1 files")
            snapshot("valid-preview")
            click("Restore labels"); await("Labels restored")
            val restored = db.labelDao().getLabels().single { it.name == tagName }
            check(db.labelDao().getMemberships().any { it.contentId == id && it.labelId == restored.id })
            check(db.labelDao().getMemberships().any { it.contentId == id && it.labelId == 1L })
            check(db.labelDao().getLabels().any { it.name == "Local retained $token" })
            snapshot("restore-success"); click("OK")
            openImport(exportName); await("Import preview"); click("Restore labels"); await("Labels restored")
            check(viewModel.state.value.importResult?.labelsAdded == 0 && viewModel.state.value.importResult?.linksAdded == 0)
            click("OK")

            val duplicateEntry = db.mediaDao().getEntriesByIds(listOf(duplicateA.lastPathSegment!!.toLong())).single()
            val dupeFile = File(duplicateEntry.path)
            val dupeHash = MessageDigest.getInstance("SHA-256").digest(dupeFile.readBytes()).joinToString("") { "%02x".format(it) }
            val partial = saved.copy(media = saved.media + listOf(
                BackupMedia("missing", "d".repeat(64), 777, "missing.png", "Old/missing.png", saved.media.single().labelKeys),
                BackupMedia("ambiguous", dupeHash, dupeFile.length(), "old-copy.png", "Old/old-copy.png", saved.media.single().labelKeys)))
            val partialName = "partial-ui-$token.json"
            download(partialName, LabelBackupJson.gson.toJson(partial).toByteArray())
            val beforePartial = db.labelDao().getMemberships().toSet()
            openImport(partialName); await("Matched 1 of 3 files"); await("Not found: 1"); await("Copies could not be distinguished: 1")
            snapshot("partial-preview")
            click("Unmatched files"); await("Old/missing.png"); await("Old/old-copy.png")
            snapshot("unmatched-details"); click("OK"); click("Cancel")
            check(beforePartial == db.labelDao().getMemberships().toSet())
            openImport(partialName); await("Matched 1 of 3 files"); click("Restore labels"); await("Labels restored")
            check(viewModel.state.value.importResult?.media == 1)
            val duplicateIds = setOf(duplicateA.lastPathSegment!!.toLong(), duplicateB.lastPathSegment!!.toLong())
            check(db.labelDao().getMemberships().none { it.contentId in duplicateIds })
            click("OK")

            val damagedName = "damaged-ui-$token.json"
            download(damagedName, exported.readBytes().dropLast(12).toByteArray())
            openImport(damagedName); await("Label backup failed")
            check(viewModel.state.value.preview == null)
            check(beforePartial == db.labelDao().getMemberships().toSet())
            snapshot("damaged-error"); click("OK")
            openImport(exportName); await("Import preview"); click("Cancel")
            // Repeated starts are guarded synchronously; leaving the page does not corrupt a pending result.
            val input = download("retry-ui-$token.json", exported.readBytes())
            instrumentation.runOnMainSync { viewModel.preview(input); viewModel.preview(input); visible.value = false }
            repeat(100) { if (viewModel.state.value.operation != null) settle(100) }
            check(viewModel.state.value.preview != null && viewModel.state.value.error == null)
            instrumentation.runOnMainSync { visible.value = true }
            await("Import preview"); snapshot("returned-preview")
            instrumentation.runOnMainSync { viewModel.apply(); viewModel.apply() }
            await("Labels restored")
            check(viewModel.state.value.importResult?.linksAdded == 0)
            click("OK")
            return@runBlocking "PASS system Export/Import cancellation leaves data unchanged; same-name export creates a second file and retains the first; real SAF JSON export and restore Unicode/Favourites; repeated restore adds zero links; mixed matched/missing/ambiguous preview and detail dialog; cancel preview changes nothing; partial apply skips duplicates; damaged JSON shows error; valid retry succeeds; repeated starts and page leave/return retain one operation. Screenshots: $output"
        } finally {
            owned.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            files.forEach { it.delete() }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    private fun awaitPicker() {
        repeat(60) { if (nodes().any { it.packageName?.toString()?.contains("documentsui") == true }) return; settle(150) }
        error("Document picker unavailable")
    }
    private fun chooseDownloads() {
        if (find("Show roots") != null) {
            click("Show roots")
            await("Downloads")
            // The toolbar underneath a drawer can also say Downloads. The drawer is appended last.
            val destination = nodes().last { it.text?.toString() == "Downloads" }
            clickNode(destination)
            settle(700)
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) { if (node != null) { result += node; for (i in 0 until node.childCount) visit(node.getChild(i)) } }
        visit(instrumentation.uiAutomation.rootInActiveWindow); return result
    }
    private fun find(label: String) = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun await(label: String) {
        repeat(100) { if (find(label) != null) return; settle(150) }
        snapshot("failed"); error("Missing $label: ${nodes().map { "${it.text}:${it.contentDescription}" }}")
    }
    private fun click(label: String) { await(label); clickNode(find(label)!!) }
    private fun clickNode(node: AccessibilityNodeInfo) {
        if (node.packageName?.toString()?.contains("documentsui") == true) {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    settle(600); return
                }
                target = target.parent
            }
        }
        val r = Rect().also(node::getBoundsInScreen)
        event(MotionEvent.ACTION_DOWN,r.centerX().toFloat(),r.centerY().toFloat())
        event(MotionEvent.ACTION_UP,r.centerX().toFloat(),r.centerY().toFloat()); settle(350)
    }
    private fun back() {
        val now = SystemClock.uptimeMillis()
        check(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
        check(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
        settle(350)
    }
    private fun event(action: Int,x: Float,y: Float) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply {
            source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(this, true)); recycle()
        }
    }
    private fun settle(ms: Long) { SystemClock.sleep(ms); instrumentation.waitForIdleSync() }
    private fun snapshot(name: String) { settle(200); instrumentation.uiAutomation.takeScreenshot().apply {
        File(output,"$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) }; recycle()
    } }
}
