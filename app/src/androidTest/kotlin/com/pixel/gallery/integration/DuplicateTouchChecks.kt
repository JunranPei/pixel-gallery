package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import com.pixel.gallery.MainActivity
import com.pixel.gallery.R
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.DuplicateRepository
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.DeduplicationScope
import com.pixel.gallery.ui.deduplication.*
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** Exercises the real MediaStore scanner, review screen, ViewModel and recycle-bin update. */
internal class DuplicateTouchChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val output = File(context.cacheDir, "duplicate-qa").apply { mkdirs() }
    private val dark = mutableStateOf(true)
    private val created = mutableListOf<Uri>()
    private var downTime = 0L

    fun exercise(): String = runBlocking {
        val token = UUID.randomUUID().toString()
        val bytesFile = File(output, "fixture-$token.jpg")
        Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff6d5599.toInt())
            bytesFile.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 95, it) }
            recycle()
        }
        ExifInterface(bytesFile).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2019:05:21 12:00:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
            saveAttributes()
        }
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .addCallback(LABEL_DATABASE_CALLBACK).build()
        val mediaRepository = MediaRepository(context, database.mediaDao(), SettingsRepository(context))
        val repository = DuplicateRepository(context, mediaRepository)
        lateinit var viewModel: DuplicateViewModel
        var activity: MainActivity? = null
        try {
            // Identical EXIF and bytes; the original has an older file timestamp, despite sorting last by name.
            val older = fixture(token, "Z-original-2020.jpg", bytesFile, 1_600_000_000_000L)
            val newer = fixture(token, "A-copy-2025.jpg", bytesFile, 1_750_000_000_000L)
            check(older.sourceDateTakenMillis == newer.sourceDateTakenMillis && older.sourceDateTakenMillis!! > 0)
            check(older.dateModifiedMillis < newer.dateModifiedMillis)
            for (input in listOf(listOf(newer, older), listOf(older, newer))) {
                check(repository.scan(input, DeduplicationScope.ALL_FILES) {}.groups.single().keeper.contentId == older.contentId)
            }
            instrumentation.runOnMainSync {
                viewModel = DuplicateViewModel(repository)
                viewModel.start(listOf(newer, older), DeduplicationScope.ALL_FILES, "Duplicate QA")
            }
            await { viewModel.state.value.phase == DuplicatePhase.REVIEW }
            check(viewModel.state.value.result!!.groups.single().keeper.contentId == older.contentId)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync {
                activity!!.setContent {
                    PixelGalleryTheme(darkTheme = dark.value, dynamicColor = false) {
                        val state by viewModel.state.collectAsState()
                        DuplicateReviewDialog(state, {}, viewModel::toggle, viewModel::confirm)
                    }
                }
            }
            settle(1200)
            find("取消")?.let { tap(bounds(it)) }
            settle(500)
            check(find(context.getString(R.string.duplicate_keeper_rule)) != null)
            check(find(context.getString(R.string.duplicate_recommended)) != null)
            check(bounds(findRequired("Z-original-2020.jpg")).top < bounds(findRequired("A-copy-2025.jpg")).top)
            screenshot("review-dark", 0x121212)
            instrumentation.runOnMainSync { dark.value = false }
            settle(350)
            screenshot("review-light", 0xffffff)
            tap(bounds(findRequired(context.getString(R.string.duplicate_select_copies))))
            await { viewModel.state.value.selectedIds == setOf(newer.contentId) }
            instrumentation.runOnMainSync { dark.value = true }
            settle(350)
            screenshot("newer-copy-selected-dark", 0x121212)
            tap(bounds(findRequired(context.getString(R.string.duplicate_move_to_bin))))
            settle(300)
            check(find(context.getString(R.string.duplicate_confirm_title)) != null)
            screenshot("confirm-dark")
            tap(bounds(findRequired(context.getString(R.string.duplicate_move_to_bin))))
            await { viewModel.state.value.phase in setOf(DuplicatePhase.COMPLETE, DuplicatePhase.ERROR) }
            check(viewModel.state.value.phase == DuplicatePhase.COMPLETE) { viewModel.state.value.error.orEmpty() }
            check(viewModel.state.value.removedCount == 1)
            check(trashed(newer.uri) && !trashed(older.uri))
            check(File(older.path).readBytes().contentEquals(bytesFile.readBytes()))
            screenshot("complete-dark")
            "Real identical JPEGs: earlier modified copy retained with equal EXIF capture time; input order has no effect.\n" +
                "Review shows the keeper rule and earlier original first; select copies selects only the newer copy.\n" +
                "Dark/light review surfaces verified as #121212/#FFFFFF, including the populated result and bottom bar.\n" +
                "Real confirmation and recycle-bin update completed: newer copy IS_TRASHED=1, original IS_TRASHED=0 with bytes intact.\n" +
                "Screenshots: ${output.absolutePath}"
        } finally {
            created.forEach { runCatching { resolver.delete(it, null, null) } }
            bytesFile.delete()
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
        }
    }

    private suspend fun fixture(token: String, name: String, source: File, modified: Long): MediaEntry {
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DuplicateQA_$token/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        created += uri
        resolver.openOutputStream(uri)!!.use { it.write(source.readBytes()) }
        fun currentPath() = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
            check(it.moveToFirst()); it.getString(0)
        }
        var path = currentPath()
        check(path.contains("DuplicateQA_$token"))
        check(File(path).setLastModified(modified))
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        path = currentPath()
        check(File(path).setLastModified(modified))
        val scanned = withTimeout(15_000) {
            suspendCancellableCoroutine<Uri?> { continuation ->
                android.media.MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg")) { _, found ->
                    if (continuation.isActive) continuation.resume(found)
                }
            }
        } ?: error("Fixture scan failed")
        if (scanned !in created) created += scanned
        return resolver.query(scanned, arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN), null, null, null)!!.use {
            check(it.moveToFirst())
            MediaEntry(scanned.lastPathSegment!!.toLong(), scanned.toString(), path, "image/jpeg", 160, 120, 0,
                File(path).length(), it.getLong(0), it.getLong(1) * 1000, it.getLong(2), bestTimestamp = it.getLong(2))
        }
    }
    private fun trashed(uri: String): Boolean = resolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.IS_TRASHED),
        Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }, null)!!.use {
        check(it.moveToFirst()); it.getInt(0) == 1
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { "Timed out awaiting duplicate state" }; settle(100) }
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) find(text, node.getChild(index))?.let { return it }
        return null
    }
    private fun findRequired(text: String): AccessibilityNodeInfo {
        instrumentation.uiAutomation.clearCache()
        return checkNotNull(find(text)) { "Missing UI text: $text" }
    }
    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)
    private fun tap(bounds: Rect) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            MotionEvent.obtain(downTime, now, action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(this); recycle()
            }
        }
    }
    private fun settle(millis: Long) { SystemClock.sleep(millis); instrumentation.waitForIdleSync() }
    private fun screenshot(name: String, background: Int? = null) {
        settle(250)
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Empty gutter beside the populated card, above the bottom bar.
            if (background != null) check(bitmap.getPixel(4, bitmap.height * 3 / 4) and 0xffffff == background) {
                "Unexpected duplicate review background"
            }
        }
    }
}
