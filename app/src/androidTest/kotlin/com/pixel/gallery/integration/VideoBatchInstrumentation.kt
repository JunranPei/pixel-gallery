package com.pixel.gallery.integration

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.fingerprintMediaFile
import com.pixel.gallery.model.verifyMediaFile
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Real-emulator batch video move probe. It uses only an isolated MediaStore tree. */
class VideoBatchInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            result.putString("stream", runBlocking { exercise() })
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun exercise(): String {
        val token = UUID.randomUUID().toString()
        val root = "Movies/PixelVideoBatch_$token/"
        val sourceDir = "Source/"
        val targetDir = "Target/"
        val isolated = IsolatedContext(targetContext, token)
        val db = Room.inMemoryDatabaseBuilder(isolated, GalleryDatabase::class.java)
            .addCallback(LABEL_DATABASE_CALLBACK).build()
        val repository = MediaRepository(isolated, db.mediaDao(), SettingsRepository(isolated))
        val created = mutableListOf<android.net.Uri>()
        val entries = (1..20).map { index ->
            val uri = targetContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "batch-$index.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, root + sourceDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("insert video $index failed")
            created += uri
            targetContext.contentResolver.openOutputStream(uri)!!.use { output ->
                output.write(ByteArray(32 * 1024) { (it + index).toByte() })
            }
            check(targetContext.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1)
            val path = targetContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
                check(it.moveToFirst()); it.getString(0)
            }
            val file = File(path)
            MediaEntry(uri.lastPathSegment!!.toLong(), uri.toString(), path, "video/mp4", 320, 240, 0,
                file.length(), 1L, file.lastModified(), null, durationMillis = 2_000L,
                bestTimestamp = file.lastModified())
        }
        val destinationPath = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "PixelVideoBatchTarget_$token",
        ).apply { mkdirs() }
        val destination = TransferDestination(destinationPath.path, destinationPath.name, destinationPath.path)
        try {
            db.mediaDao().insertAll(entries)
            val receipts = entries.mapIndexed { index, entry ->
                val before = fingerprintMediaFile(File(entry.path))
                val receipt = repository.moveForQuickSort(entry, destination, "video-batch-$index") {}
                check(!File(entry.path).exists())
                verifyMediaFile(File(receipt.targetPath), before, verifyTime = true)
                created += android.net.Uri.parse(receipt.targetUri)
                receipt
            }
            check(receipts.size == 20)
            return "Moved ${receipts.size} videos sequentially in one batch; all SHA-256 and source removal checks passed"
        } finally {
            created.distinct().forEach { runCatching { targetContext.contentResolver.delete(it, null, null) } }
            destinationPath.deleteRecursively()
        }
    }

    private class IsolatedContext(base: Context, token: String) : ContextWrapper(base) {
        private val files = File(base.filesDir, "video-batch/$token").apply { mkdirs() }
        override fun getFilesDir(): File = files
        override fun getApplicationContext(): Context = this
    }
}
