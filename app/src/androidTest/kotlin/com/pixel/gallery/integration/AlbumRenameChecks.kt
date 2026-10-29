package com.pixel.gallery.integration

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.room.Room
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.data.repository.LabelRepository
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.fingerprintMediaFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class AlbumRenameChecks(private val context: Context) {
    fun exercise(): String = runBlocking {
        val token = UUID.randomUUID().toString()
        val root = File("/storage/emulated/0/Pictures/AlbumRenameQA_$token").apply { check(mkdirs()) }
        val source = File(root, "Original").apply { check(mkdir()) }
        val nested = File(source, "Nested").apply { check(mkdir()) }
        val existing = File(root, "Existing").apply { check(mkdir()) }
        val image = File(source, "sample.jpg")
        val child = File(nested, "child.jpg")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff448866.toInt()) }
        listOf(image, child).forEach { file -> file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
        bitmap.recycle()
        val note = File(source, "notes.txt").apply { writeText("Preserve non-media files") }
        val marker = File(existing, "keep.txt").apply { writeText("Do not overwrite") }
        val originals = listOf(image, child, note).associate { it.relativeTo(source).path to fingerprintMediaFile(it).sha256 }
        val isolated = object : ContextWrapper(context) {
            private val files = File(context.filesDir, "album-rename-test/$token").apply { mkdirs() }
            override fun getFilesDir() = files
            override fun getApplicationContext(): Context = this
        }
        val settings = SettingsRepository(isolated)
        val db = Room.inMemoryDatabaseBuilder(isolated, GalleryDatabase::class.java).addCallback(LABEL_DATABASE_CALLBACK).build()
        val repository = MediaRepository(isolated, db.mediaDao(), settings)
        fun scan(file: File): Uri? {
            val latch = CountDownLatch(1)
            var uri: Uri? = null
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, result -> uri = result; latch.countDown() }
            check(latch.await(20, TimeUnit.SECONDS))
            return uri
        }
        check(scan(image) != null); check(scan(child) != null)
        repository.syncWhenMediaMutationsIdle(force = true)
        val entries = db.mediaDao().getAllEntriesPaged().filter { it.path.startsWith(source.path + "/") }
        check(entries.size == 2)
        val labels = LabelRepository(db, db.labelDao())
        val labelId = labels.createLabel("Rename QA $token")
        db.labelDao().addMemberships(entries.map { MediaLabelEntry(labelId, it.contentId) })
        settings.setQuickSortTargets(listOf(TransferDestination(source.path, "Original", source.path)))
        settings.addHiddenFolder(nested.path)
        check(runCatching { repository.renameAlbum(source.path, "Existing") }.isFailure)
        check(runCatching { repository.renameAlbum(source.path, "../Escape") }.isFailure)
        check(marker.readText() == "Do not overwrite" && image.isFile && note.isFile)
        repository.renameAlbum(source.path, "Original")
        check(image.isFile)
        repository.renameAlbum(source.path, "Renamed Album")
        val target = File(root, "Renamed Album")
        check(!source.exists() && target.isDirectory)
        originals.forEach { (path, hash) -> check(fingerprintMediaFile(File(target, path)).sha256 == hash) }
        val renamed = db.mediaDao().getAllEntriesPaged().filter { it.path.startsWith(target.path + "/") }
        check(renamed.size == 2)
        check(db.labelDao().getMemberships().filter { it.labelId == labelId }.map { it.contentId }.toSet() == renamed.map { it.contentId }.toSet())
        check(settings.quickSortPreferences.first().targets.single().path == target.path)
        check(settings.hiddenFolders.first() == setOf(File(target, "Nested").path))
        renamed.forEach { entry -> context.contentResolver.openInputStream(Uri.parse(entry.uri))!!.use { check(it.read() >= 0) } }
        repository.renameAlbum(target.path, "Original")
        originals.forEach { (path, hash) -> check(fingerprintMediaFile(File(source, path)).sha256 == hash) }
        check(settings.quickSortPreferences.first().targets.single().path == source.path)
        check(settings.hiddenFolders.first() == setOf(nested.path))
        check(marker.readText() == "Do not overwrite")
        labels.renameLabel(labelId, "Renamed label $token")
        check(db.labelDao().getLabels().single { it.id == labelId }.name == "Renamed label $token")
        check(db.labelDao().getMemberships().count { it.labelId == labelId } == 2)
        labels.deleteLabel(labelId)
        check(db.labelDao().getMemberships().none { it.labelId == labelId })
        check(image.isFile && child.isFile)
        return@runBlocking "PASS: album rename and rename-back preserve two JPEG hashes, nested directory, non-media file, readable media URIs, label membership, quick-sort targets and hidden subfolder. Existing/invalid names rejected; same-name no-op. Label rename retains memberships; deletion keeps files. Fixtures: $root"
    }
}
