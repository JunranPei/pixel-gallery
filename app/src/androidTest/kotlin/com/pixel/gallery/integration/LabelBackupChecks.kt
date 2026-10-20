package com.pixel.gallery.integration

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.room.Room
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.model.LabelEditRequest
import com.pixel.gallery.model.LabelBackupJson
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/** Real JSON I/O and Room transactions using this invocation's synthetic media only. */
internal class LabelBackupChecks(private val context: Context) {
    fun exercise(): String = runBlocking {
        val token = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.filesDir, "label-backup-test/$token/files").apply { mkdirs() }
            override fun getCacheDir() = File(context.cacheDir, "label-backup-test/$token/cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(isolated, GalleryDatabase::class.java).addCallback(LABEL_DATABASE_CALLBACK).build()
        val media = MediaRepository(isolated, db.mediaDao(), SettingsRepository(isolated))
        val labels = LabelRepository(db, db.labelDao())
        val transfer = LabelBackupRepository(isolated, db, media)
        val created = mutableListOf<Uri>()
        val files = mutableListOf<File>()
        val report = mutableListOf<String>()
        suspend fun fixture(name: String, subdir: String, bytes: ByteArray? = null, color: Int = 0xff345678.toInt()): Uri {
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LabelBackupTest_$token/$subdir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            created += uri
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                if (bytes != null) output.write(bytes) else Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(color); check(compress(Bitmap.CompressFormat.JPEG, 95, output)); recycle()
                }
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        fun document(name: String): Pair<File, Uri> {
            val file = File(isolated.cacheDir, name).apply { writeText("") }
            files += file
            return file to FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }
        try {
            val source = fixture("original.jpg", "Source")
            media.syncWhenMediaMutationsIdle(force = true)
            val entry = db.mediaDao().getEntriesByIds(listOf(source.lastPathSegment!!.toLong())).single()
            val tag = labels.createLabel("迁移测试 $token")
            val empty = labels.createLabel("空标签 $token")
            labels.applyEdits(setOf(entry.contentId), LabelEditRequest(changes = mapOf(tag to true, 1L to true)))
            val (backupFile, backupUri) = document("backup.json")
            val exported = transfer.export(backupUri) { _, _ -> }
            check(exported.media == 1 && exported.labels == 3 && exported.skipped == 0)
            val json = LabelBackupJson.read(backupFile.reader())
            check(json.media.single().labelKeys.size == 2)
            check(json.labels.any { it.name.startsWith("空标签") })
            report += "Real JSON export preserves Unicode, empty labels and Favourites."

            val bytes = File(entry.path).readBytes()
            val destination = fixture("renamed.jpg", "NewFolder", bytes)
            val unrelated = fixture("original.jpg", "Other", color = 0xffaa1122.toInt())
            context.contentResolver.delete(source, null, null)
            db.labelDao().removeMemberships(1, listOf(entry.contentId))
            labels.deleteLabel(tag); labels.deleteLabel(empty)
            // Force different local label IDs and an existing name that must be reused.
            labels.createLabel("Existing label $token")
            val existingTag = labels.createLabel("迁移测试 $token")
            val preview = transfer.preview(backupUri) { _, _ -> }
            val destinationId = destination.lastPathSegment!!.toLong()
            check(preview.resolution.matches.values.toSet() == setOf(destinationId))
            check(preview.resolution.missing.isEmpty() && preview.resolution.ambiguous.isEmpty())
            val restored = transfer.apply(preview)
            check(restored.media == 1 && restored.labelsAdded == 1 && restored.linksAdded == 2)
            val restoredLinks = db.labelDao().getMemberships()
            check(restoredLinks.any { it.contentId == destinationId && it.labelId == existingTag })
            check(restoredLinks.any { it.contentId == destinationId && it.labelId == 1L })
            check(restoredLinks.none { it.contentId == unrelated.lastPathSegment!!.toLong() })
            val again = transfer.apply(transfer.preview(backupUri) { _, _ -> })
            check(again.labelsAdded == 0 && again.linksAdded == 0)
            report += "Renamed file, moved album and new MediaStore ID restore correctly; same-name different bytes stay unlabelled; repeated import is idempotent."

            val duplicate = fixture("another-name.jpg", "SecondCopy", bytes)
            val ambiguous = transfer.preview(backupUri) { _, _ -> }
            check(ambiguous.resolution.matches.isEmpty() && ambiguous.resolution.ambiguous.size == 1)
            report += "Two renamed identical copies remain unresolved instead of receiving arbitrary labels."
            context.contentResolver.delete(duplicate, null, null)
            val stale = transfer.preview(backupUri) { _, _ -> }
            val before = db.labelDao().getMemberships().toSet()
            context.contentResolver.openOutputStream(destination, "wt")!!.use { it.write(byteArrayOf(1, 2, 3)) }
            try { transfer.apply(stale); error("Accepted changed contents") } catch (_: ChangedLabelMediaException) { }
            check(before == db.labelDao().getMemberships().toSet())
            report += "A file modified after preview aborts before all database writes."

            val (badFile, badUri) = document("damaged.json")
            badFile.writeText(backupFile.readText().dropLast(20))
            try { transfer.preview(badUri) { _, _ -> }; error("Accepted damaged backup") } catch (_: InvalidLabelBackupException) { }
            check(before == db.labelDao().getMemberships().toSet())
            report += "Truncated JSON is rejected without altering existing memberships."
            report.joinToString("\n")
        } finally {
            created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            files.forEach { it.delete() }
        }
    }
}
