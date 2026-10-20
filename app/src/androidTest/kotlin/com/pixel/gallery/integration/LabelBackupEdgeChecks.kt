package com.pixel.gallery.integration

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.room.withTransaction
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.model.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Independent Room database/settings; only media and documents owned by this run are removed. */
internal class LabelBackupEdgeChecks(private val context: Context, private val filter: String? = null) {
    suspend fun execute(): String = coroutineScope {
        val token = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.filesDir, "backup-edge/$token/files").apply { mkdirs() }
            override fun getCacheDir() = File(context.cacheDir, "backup-edge/$token/cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        var onQuery: ((String) -> Unit)? = null
        val db = Room.inMemoryDatabaseBuilder(isolated, GalleryDatabase::class.java)
            .addCallback(LABEL_DATABASE_CALLBACK)
            .setQueryCallback({ sql, _ -> onQuery?.invoke(sql) }, Executor { it.run() }).build()
        val settings = SettingsRepository(isolated)
        val media = MediaRepository(isolated, db.mediaDao(), settings)
        val labels = LabelRepository(db, db.labelDao())
        val backup = LabelBackupRepository(isolated, db, media)
        val ownedMedia = mutableListOf<Uri>()
        val ownedDocuments = mutableListOf<File>()
        val report = mutableListOf<String>()
        var failures = 0
        var serial = 0
        val logFile = File(context.cacheDir, "label-backup-edge-results.txt")

        suspend fun state() = db.labelDao().getLabels() to db.labelDao().getMemberships().toSet()
        suspend fun reset() { db.withTransaction {
            db.openHelper.writableDatabase.execSQL("DELETE FROM media_labels")
            db.openHelper.writableDatabase.execSQL("DELETE FROM labels WHERE systemKind IS NULL")
        } }
        suspend fun case(name: String, body: suspend () -> Unit) {
            if (filter != null && !name.contains(filter)) return
            reset()
            try { body(); report += "PASS $name" }
            catch (error: Throwable) {
                if (error is CancellationException) throw error
                failures++; report += "FAIL $name: ${error.stackTraceToString()}"
            }
            onQuery = null
            logFile.writeText(report.joinToString("\n"))
        }
        fun document(name: String, bytes: ByteArray = byteArrayOf()): Pair<File, Uri> {
            val file = File(isolated.cacheDir, "${++serial}-$name").apply { writeBytes(bytes) }
            ownedDocuments += file
            return file to FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }
        fun json(source: LabelBackup) = document("backup.json", LabelBackupJson.gson.toJson(source).toByteArray())
        fun empty(vararg tags: BackupLabel) = LabelBackup(exportedAt = "2026-10-20T00:00:00Z", labels = tags.toList(), media = emptyList())
        suspend fun fixture(subdir: String = "Source"): Long {
            val index = ++serial
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "edge-$index.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LabelBackupEdge_$token/$subdir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            ownedMedia += uri
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(0xff123456.toInt())
                    setPixel(0, 0, 0xff000000.toInt() or index)
                    check(compress(Bitmap.CompressFormat.PNG, 100, output)); recycle()
                }
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri.lastPathSegment!!.toLong()
        }
        suspend fun source(): Pair<Long, Uri> {
            val id = fixture()
            media.syncWhenMediaMutationsIdle(force = true)
            val tag = labels.createLabel("Source $serial")
            labels.applyEdits(setOf(id), LabelEditRequest(changes = mapOf(tag to true)))
            val (_, uri) = document("source.json")
            backup.export(uri) { _, _ -> }
            return id to uri
        }
        suspend fun expectFailure(block: suspend () -> Unit) {
            val before = state()
            val result = runCatching { block() }
            check(result.isFailure) { "Operation unexpectedly succeeded" }
            check(result.exceptionOrNull() !is CancellationException)
            check(before == state()) { "Failure changed labels or memberships" }
        }
        try {
            case("D01 empty backup changes nothing") {
                val before = state()
                val result = backup.apply(backup.preview(json(empty()).second) { _, _ -> })
                check(result == LabelImportResult(0, 0, 0)); check(before == state())
            }
            case("D01 labels only preserve empty labels and normalize existing names") {
                val existing = labels.createLabel("Café")
                val doc = json(empty(BackupLabel("same", " Ｃａｆe\u0301 "), BackupLabel("new", "空标签 📷")))
                val result = backup.apply(backup.preview(doc.second) { _, _ -> })
                check(result.labelsAdded == 1 && result.linksAdded == 0)
                check(db.labelDao().getLabels().any { it.id == existing && it.name == "Café" })
            }
            case("D02 Favourites maps by system kind across language and backup keys") {
                val doc = json(empty(BackupLabel("foreign-key-999", "收藏", "FAVOURITES")))
                val result = backup.apply(backup.preview(doc.second) { _, _ -> })
                check(result.labelsAdded == 0); check(db.labelDao().getLabels().single().id == 1L)
            }
            case("D03 merge keeps local extras and repeated preview or apply is idempotent") {
                val (id, uri) = source()
                val local = labels.createLabel("Local extra")
                labels.applyEdits(setOf(id), LabelEditRequest(changes = mapOf(local to true)))
                val before = state()
                val preview = backup.preview(uri) { _, _ -> }
                repeat(2) { check(backup.apply(preview).linksAdded == 0) }
                check(backup.apply(backup.preview(uri) { _, _ -> }).labelsAdded == 0)
                check(before == state())
            }
            case("D04 same size changed bytes with preserved timestamp aborts atomically") {
                val (id, uri) = source()
                val preview = backup.preview(uri) { _, _ -> }
                val entry = db.mediaDao().getEntriesByIds(listOf(id)).single()
                val file = File(entry.path); val modified = file.lastModified(); val bytes = file.readBytes()
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                file.writeBytes(bytes); check(file.setLastModified(modified))
                expectFailure { backup.apply(preview) }
            }
            case("D04 deleted after preview rejects all writes") {
                val (id, uri) = source(); val preview = backup.preview(uri) { _, _ -> }
                val entry = db.mediaDao().getEntriesByIds(listOf(id)).single()
                context.contentResolver.delete(Uri.parse(entry.uri), null, null)
                expectFailure { backup.apply(preview) }
            }
            case("D04 trashed after preview rejects all writes") {
                val (id, uri) = source(); val preview = backup.preview(uri) { _, _ -> }
                val entry = db.mediaDao().getEntriesByIds(listOf(id)).single()
                context.contentResolver.update(Uri.parse(entry.uri), ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
                expectFailure { backup.apply(preview) }
            }
            case("D04 excluded after preview rejects all writes") {
                val (id, uri) = source(); val preview = backup.preview(uri) { _, _ -> }
                val tagId = db.labelDao().getMemberships().single().labelId
                db.labelDao().removeMemberships(tagId, listOf(id))
                val directory = db.mediaDao().getEntriesByIds(listOf(id)).single().path.substringBeforeLast('/') + "/"
                settings.addExcludedFolder(directory)
                try { expectFailure { backup.apply(preview) } } finally { settings.removeExcludedFolder(directory) }
            }
            case("D04 changed second file prevents partial restore of first file") {
                val ids = setOf(fixture("Pair"), fixture("Pair"))
                media.syncWhenMediaMutationsIdle(force = true)
                val tag = labels.createLabel("Two-file atomic restore")
                labels.applyEdits(ids, LabelEditRequest(changes = mapOf(tag to true)))
                val doc = document("two-files.json"); backup.export(doc.second) { _, _ -> }
                db.labelDao().removeMemberships(tag, ids.toList())
                val preview = backup.preview(doc.second) { _, _ -> }
                val changed = File(db.mediaDao().getEntriesByIds(listOf(ids.last())).single().path)
                changed.appendBytes(byteArrayOf(1))
                expectFailure { backup.apply(preview) }
            }
            case("additional D04 moving after preview requires a fresh scan") {
                val (id, doc) = source()
                val preview = backup.preview(doc) { _, _ -> }
                val entry = db.mediaDao().getEntriesByIds(listOf(id)).single()
                context.contentResolver.update(Uri.parse(entry.uri), ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LabelBackupEdge_$token/MovedAfterPreview/")
                }, null, null)
                media.syncWhenMediaMutationsIdle(force = true)
                check(db.mediaDao().getEntriesByIds(listOf(id)).single().path != entry.path)
                expectFailure { backup.apply(preview) }
                check(backup.preview(doc) { _, _ -> }.resolution.matches.values.toSet() == setOf(id))
            }
            case("additional M02 visually identical recompressed photo is not auto labelled") {
                val (id, doc) = source()
                val entry = db.mediaDao().getEntriesByIds(listOf(id)).single()
                val bitmap = BitmapFactory.decodeFile(entry.path)!!
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "recompressed-$serial.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LabelBackupEdge_$token/Recompressed/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
                ownedMedia += uri
                context.contentResolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 40, it)) }
                bitmap.recycle()
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                context.contentResolver.delete(Uri.parse(entry.uri), null, null)
                val before = state()
                val preview = backup.preview(doc) { _, _ -> }
                check(preview.resolution.matches.isEmpty() && preview.resolution.missing.size == 1)
                check(backup.apply(preview).linksAdded == 0)
                check(before == state())
                check(db.labelDao().getMemberships().none { it.contentId == uri.lastPathSegment!!.toLong() })
            }
            case("roundtrip D03 re-export after restore uses new label IDs and preserves local labels") {
                val (id, originalUri) = source()
                val oldTag = db.labelDao().getLabels().single { it.systemKind == null }
                labels.deleteLabel(oldTag.id)
                labels.createLabel("Local extra after migration")
                check(backup.apply(backup.preview(originalUri) { _, _ -> }).linksAdded == 1)
                val newTag = db.labelDao().getLabels().single { it.name == oldTag.name }
                check(newTag.id != oldTag.id)
                val document = document("re-export.json")
                val exported = backup.export(document.second) { _, _ -> }
                check(exported.media == 1 && exported.labels == 3)
                val parsed = LabelBackupJson.read(document.first.reader())
                val names = parsed.labels.associate { it.key to it.name }
                check(parsed.media.single().labelKeys.map { names.getValue(it) } == listOf(oldTag.name))
                check(parsed.labels.any { it.name == "Local extra after migration" })
                check(backup.preview(document.second) { _, _ -> }.resolution.matches.values.toSet() == setOf(id))
                check(backup.apply(backup.preview(document.second) { _, _ -> }).linksAdded == 0)
            }
            case("D05 system name conflict rolls back earlier label inserts") {
                val doc = json(empty(BackupLabel("first", "Should roll back"), BackupLabel("collision", "Favourites")))
                expectFailure { backup.apply(backup.preview(doc.second) { _, _ -> }) }
            }
            case("D05 database failure rolls back earlier label inserts") {
                val sql = db.openHelper.writableDatabase
                sql.execSQL("CREATE TRIGGER edge_injected_failure BEFORE INSERT ON labels WHEN NEW.name = 'Injected failure' BEGIN SELECT RAISE(ABORT, 'Injected test failure'); END")
                try {
                    val doc = json(empty(BackupLabel("a", "Before failure"), BackupLabel("b", "Injected failure")))
                    expectFailure { backup.apply(backup.preview(doc.second) { _, _ -> }) }
                } finally { sql.execSQL("DROP TRIGGER edge_injected_failure") }
            }
            case("D06 invalid UTF8 and oversized inputs reject without changes") {
                val template = LabelBackupJson.gson.toJson(empty(BackupLabel("tag", "INVALID_UTF8_MARKER")))
                val badBytes = template.substringBefore("INVALID_UTF8_MARKER").toByteArray() +
                    byteArrayOf(0xc3.toByte(), 0x28) + template.substringAfter("INVALID_UTF8_MARKER").toByteArray()
                val bad = document("utf8.json", badBytes)
                expectFailure { backup.preview(bad.second) { _, _ -> } }
                val large = document("large.json")
                large.first.outputStream().use { stream ->
                    // Valid JSON plus legal whitespace: only the byte limit should reject this document.
                    stream.write(LabelBackupJson.gson.toJson(empty()).toByteArray())
                    val chunk = ByteArray(8192) { 32 }; repeat(8192) { stream.write(chunk) }
                }
                expectFailure { backup.preview(large.second) { _, _ -> } }
                large.first.delete()
            }
            case("D06 missing or inaccessible document and invalid output URI fail cleanly") {
                val missing = document("removed.json"); missing.first.delete()
                expectFailure { backup.preview(missing.second) { _, _ -> } }
                expectFailure { backup.preview(Uri.parse("content://no.such.provider/backup.json")) { _, _ -> } }
                expectFailure { backup.export(Uri.parse("content://no.such.provider/backup.json")) { _, _ -> } }
                val directoryUri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", isolated.cacheDir)
                expectFailure { backup.export(directoryUri) { _, _ -> } }
            }
            case("D06 unavailable or excluded source links are counted and not exported") {
                val (id, _) = source()
                val entry = db.mediaDao().getEntriesByIds(listOf(id)).single()
                settings.addExcludedFolder(entry.path.substringBeforeLast('/') + "/")
                try {
                    db.labelDao().addMemberships(listOf(MediaLabelEntry(1L, Long.MAX_VALUE)))
                    val result = backup.export(document("skipped.json").second) { _, _ -> }
                    check(result.media == 0 && result.skipped == 2)
                } finally { settings.removeExcludedFolder(entry.path.substringBeforeLast('/') + "/") }
            }
            case("D06 non-file document streams and injected read write permission failures") {
                val root = "content://com.pixel.gallery.labelbackup.edge.test.documents/"
                val preview = backup.preview(Uri.parse(root + "valid-stream")) { _, _ -> }
                check(preview.backup.media.isEmpty() && preview.backup.labels.isEmpty())
                expectFailure { backup.preview(Uri.parse(root + "read-failure")) { _, _ -> } }
                expectFailure { backup.preview(Uri.parse(root + "denied")) { _, _ -> } }
                expectFailure { backup.export(Uri.parse(root + "write-failure")) { _, _ -> } }
            }
            case("D07 cancelling scan leaves labels and memberships unchanged") {
                val (_, uri) = source(); val before = state()
                val job = launch(start = CoroutineStart.LAZY) {
                    val operation = currentCoroutineContext().job
                    backup.preview(uri) { _, _ -> operation.cancel() }
                }
                job.start(); job.join(); check(job.isCancelled); check(before == state())
            }
            case("D07 cancelling export before output leaves destination unchanged") {
                source(); val before = state(); val destination = document("cancelled.json", "existing".toByteArray())
                val job = launch(start = CoroutineStart.LAZY) {
                    val operation = currentCoroutineContext().job
                    backup.export(destination.second) { _, _ -> operation.cancel() }
                }
                job.start(); job.join(); check(job.isCancelled)
                check(before == state()); check(destination.first.readText() == "existing")
            }
            case("D07 cancellation during transaction rolls back label insertion") {
                val doc = json(empty(BackupLabel("a", "Cancelled insert A"), BackupLabel("b", "Cancelled insert B")))
                val preview = backup.preview(doc.second) { _, _ -> }; val before = state()
                val injected = AtomicBoolean(false)
                val job = launch(start = CoroutineStart.LAZY) { backup.apply(preview) }
                onQuery = { sql -> if (sql.startsWith("INSERT", ignoreCase = true) && sql.contains("labels") && injected.compareAndSet(false, true)) job.cancel() }
                job.start(); job.join(); onQuery = null
                check(injected.get()) { "Query callback was not invoked" }
                check(job.isCancelled) { "Injected operation was not cancelled" }
                check(before == state()) { "Transaction changed state after cancellation: before=$before after=${state()}" }
            }
            case("D01 real JPEG PNG WebP GIF and MP4 all export and restore") {
                media.syncWhenMediaMutationsIdle(force = true)
                val entries = db.mediaDao().getAllEntriesPaged().filter { it.path.contains("/GalleryQA20261020/") }
                    .groupBy { it.sourceMimeType }.values.map { it.first() }
                check(entries.size >= 5)
                val tag = labels.createLabel("Mixed media backup")
                val ids = entries.map { it.contentId }.toSet()
                labels.applyEdits(ids, LabelEditRequest(changes = mapOf(tag to true)))
                val doc = document("formats.json"); check(backup.export(doc.second) { _, _ -> }.media == entries.size)
                db.labelDao().removeMemberships(tag, ids.toList())
                val result = backup.apply(backup.preview(doc.second) { _, _ -> })
                check(result.linksAdded == entries.size)
            }
            case("P06 real batch over SQLite and membership chunk sizes restores 1001 files") {
                val ids = (1..1001).map { fixture("Bulk") }.toSet()
                media.syncWhenMediaMutationsIdle(force = true)
                val tag = labels.createLabel("Bulk 1001")
                labels.applyEdits(ids, LabelEditRequest(changes = mapOf(tag to true)))
                val doc = document("bulk.json"); check(backup.export(doc.second) { _, _ -> }.media == 1001)
                db.labelDao().removeMemberships(tag, ids.toList())
                val preview = backup.preview(doc.second) { _, _ -> }
                val sql = db.openHelper.writableDatabase
                sql.execSQL("CREATE TRIGGER edge_bulk_failure BEFORE INSERT ON media_labels WHEN NEW.contentId = ${ids.first()} BEGIN SELECT RAISE(ABORT, 'Injected bulk failure'); END")
                try { expectFailure { backup.apply(preview) } } finally { sql.execSQL("DROP TRIGGER edge_bulk_failure") }
                val result = backup.apply(preview)
                check(result.media == 1001 && result.linksAdded == 1001)
                check(backup.apply(backup.preview(doc.second) { _, _ -> }).linksAdded == 0)
            }
            check(failures == 0) { "$failures edge cases failed:\n${report.joinToString("\n")}" }
            report.joinToString("\n")
        } finally {
            onQuery = null
            ownedMedia.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            ownedDocuments.forEach { runCatching { it.delete() } }
        }
    }
    fun exercise(): String = runBlocking { execute() }
}
