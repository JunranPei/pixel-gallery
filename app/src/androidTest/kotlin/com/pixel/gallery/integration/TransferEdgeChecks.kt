package com.pixel.gallery.integration

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.room.Room
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.model.*
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real operations against a UUID-owned media tree and an independent database/settings. */
internal class TransferEdgeChecks(private val context: Context, private val filter: String? = null) {
    fun exercise(): String = runBlocking { execute() }
    private suspend fun execute(): String = coroutineScope {
        val token = UUID.randomUUID().toString()
        val relative = "Pictures/TransferEdge_$token/"
        val root = File(android.os.Environment.getExternalStorageDirectory(), relative).canonicalFile
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.filesDir, "transfer-edge/$token/files").apply { mkdirs() }
            override fun getCacheDir() = File(context.cacheDir, "transfer-edge/$token/cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(isolated, GalleryDatabase::class.java).addCallback(LABEL_DATABASE_CALLBACK).build()
        val repo = MediaRepository(isolated, db.mediaDao(), SettingsRepository(isolated))
        val tags = LabelRepository(db, db.labelDao())
        val resolver = context.contentResolver
        val owned = mutableListOf<Uri>()
        val report = mutableListOf<String>()
        var serial = 0
        var failures = 0
        val log = File(context.cacheDir, "transfer-edge-results.txt")
        fun hash(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun destination(name: String): TransferDestination {
            val dir = File(root, name).apply { mkdirs() }
            return TransferDestination(dir.canonicalPath, name, dir.canonicalPath)
        }
        suspend fun fixture(folder: String, name: String = "file-${++serial}.png", extension: String = "png", large: Boolean = false): MediaEntry {
            val mime = when (extension) { "mp4" -> "video/mp4"; "jpg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/png" }
            val collection = if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative + folder + "/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Fixture insert failed")
            owned += uri
            resolver.openOutputStream(uri)!!.use { output ->
                if (extension in setOf("gif", "mp4", "jpg", "webp")) {
                    val sample = File(android.os.Environment.getExternalStorageDirectory(), "Pictures/GalleryQA20261020")
                        .walkTopDown().first { it.isFile && it.extension.equals(extension, true) }
                    sample.inputStream().use { it.copyTo(output) }
                } else {
                    Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(0xff336699.toInt()); setPixel(0,0,0xff000000.toInt() or ++serial)
                        check(compress(Bitmap.CompressFormat.PNG,100,output)); recycle()
                    }
                }
                if (large) repeat(129) { output.write(ByteArray(65536) { index -> (index % 251).toByte() }) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },null,null)
            val path = resolver.query(uri,arrayOf(MediaStore.MediaColumns.DATA),null,null,null)!!.use { check(it.moveToFirst()); it.getString(0) }
            check(File(path).canonicalPath.startsWith(root.path + File.separator))
            check(File(path).setLastModified(1_600_000_000_000L))
            repo.syncWhenMediaMutationsIdle(force = true)
            return db.mediaDao().getEntriesByIds(listOf(uri.lastPathSegment!!.toLong())).single()
        }
        suspend fun labels(entry: MediaEntry): Set<Long> = db.labelDao().getMemberships().filter { it.contentId == entry.contentId }.map { it.labelId }.toSet()
        suspend fun addTags(entry: MediaEntry): Set<Long> {
            val tag = tags.createLabel("Transfer ${++serial}")
            val fav = db.labelDao().getLabels().single { it.systemKind == "FAVOURITES" }.id
            tags.applyEdits(setOf(entry.contentId),LabelEditRequest(changes=mapOf(tag to true, fav to true)))
            return setOf(tag,fav)
        }
        suspend fun targetEntry(file: File): MediaEntry = db.mediaDao().getEntriesByIds(
            resolver.query(MediaStore.Files.getContentUri("external"),arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DATA + " = ?",arrayOf(file.path),null)!!.use { c -> buildList { while(c.moveToNext()) add(c.getLong(0)) } }
        ).single()
        suspend fun transfer(entries: List<MediaEntry>, dest: TransferDestination, mode: TransferMode, policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH): TransferSummary {
            val progress = mutableListOf<TransferProgress>()
            val result = repo.transferMedia(entries,dest,mode,policy) { progress += it }
            if(progress.isNotEmpty()) check(progress.last().completed == entries.size && progress.last().total == entries.size)
            else check(result.failed==entries.size)
            check(result.succeeded + result.skipped + result.failed == entries.size)
            return result
        }
        suspend fun case(name: String, body: suspend () -> Unit) {
            if (filter != null && !name.contains(filter)) return
            try { body(); report += "PASS $name" }
            catch (error: Throwable) { if(error is CancellationException) throw error; failures++; report += "FAIL $name: ${error.stackTraceToString()}" }
            log.writeText(report.joinToString("\n"))
        }
        try {
            for (mode in TransferMode.entries) case("T0${if(mode==TransferMode.COPY)1 else 2} $mode real formats content time indexes and labels") {
                for (ext in listOf("png","jpg","webp","gif","mp4")) {
                    val source = fixture("$mode-$ext", "中文 📷.final.$ext",ext)
                    val sourceFile = File(source.path); val before = hash(sourceFile);val modified = sourceFile.lastModified()
                    val memberships = addTags(source)
                    val dest = destination("Target-$mode-$ext")
                    val result = transfer(listOf(source),dest,mode)
                    check(result.succeeded==1 && result.failed==0) { result.toString() }
                    val target = File(dest.path,sourceFile.name)
                    check(target.isFile && hash(target)==before);check(target.lastModified()==modified) { "$mode $ext changed mtime ${target.lastModified()} != $modified" }
                    val indexed = targetEntry(target);check(indexed.sourceMimeType==source.sourceMimeType)
                    check(indexed.sourceDateTakenMillis==source.sourceDateTakenMillis) { "Capture timestamp changed" }
                    if(mode==TransferMode.COPY) { check(sourceFile.exists() && hash(sourceFile)==before); check(labels(source)==memberships) }
                    else { check(!sourceFile.exists());check(labels(indexed)==memberships) { "MOVE lost label/favourite" } }
                }
            }
            for(mode in TransferMode.entries) for(policy in ConflictPolicy.entries) case("T03 $mode $policy actual filename conflict") {
                val source = fixture("Source-${mode}-${policy}","same.png")
                val occupied = fixture("Target-${mode}-${policy}","same.png")
                val from = File(source.path);val old = File(occupied.path);val fromHash=hash(from);val oldHash=hash(old)
                val memberships=addTags(source)
                val result=transfer(listOf(source),destination("Target-${mode}-${policy}"),mode,policy)
                when(policy) {
                    ConflictPolicy.SKIP -> { check(result.skipped==1);check(hash(from)==fromHash && hash(old)==oldHash) }
                    ConflictPolicy.KEEP_BOTH -> { check(result.succeeded==1) { result.toString() };check(hash(File(old.parentFile,"same (1).png"))==fromHash);check(hash(old)==oldHash) }
                    ConflictPolicy.REPLACE -> { check(result.succeeded==1) { result.toString() };check(hash(old)==fromHash); if(mode==TransferMode.MOVE) check(labels(targetEntry(old)).containsAll(memberships)) }
                }
                check(from.exists()==(mode==TransferMode.COPY || policy==ConflictPolicy.SKIP))
            }
            case("T04 source directory aliases skip every conflict policy") {
                val source=fixture("Same");val file=File(source.path);val before=hash(file);val count=file.parentFile!!.list()!!.size
                for(mode in TransferMode.entries) for(policy in ConflictPolicy.entries) {
                    val alias=TransferDestination("alias","Same",File(file.parentFile,"../Same").path)
                    check(transfer(listOf(source),alias,mode,policy).skipped==1)
                    check(hash(file)==before && file.parentFile!!.list()!!.size==count)
                }
            }
            case("T05 two sources with same name and existing suffixes") {
                val a=fixture("A","holiday.final.png");val b=fixture("B","holiday.final.png")
                val old=fixture("Naming","holiday.final.png");val old1=fixture("Naming","holiday.final (1).png")
                val fingerprints=listOf(a,b,old,old1).associate { it.path to hash(File(it.path)) }
                val result=transfer(listOf(a,b),destination("Naming"),TransferMode.COPY)
                check(result.succeeded==2);check(hash(File(root,"Naming/holiday.final (2).png"))==fingerprints[a.path])
                check(hash(File(root,"Naming/holiday.final (3).png"))==fingerprints[b.path])
                fingerprints.forEach { (path,h) -> check(hash(File(path))==h) }
            }
            case("T06 empty batch missing sources and partial completion") {
                check(transfer(emptyList(),destination("Empty"),TransferMode.COPY).succeeded==0)
                for(mode in TransferMode.entries) {
                    val gone=fixture("Missing-$mode");val valid=fixture("Valid-$mode")
                    resolver.delete(Uri.parse(gone.uri),null,null)
                    val result=transfer(listOf(gone,valid),destination("Partial-$mode"),mode)
                    check(result.succeeded==1 && result.failed==1 && result.failures.single().entry==gone)
                    check(File(root,"Partial-$mode/${File(valid.path).name}").exists())
                }
            }
            case("T06 repeated copy source follows explicit per-item semantics") {
                val source=fixture("Repeated");val before=hash(File(source.path));val dest=destination("RepeatedTarget")
                check(transfer(listOf(source,source),dest,TransferMode.COPY).succeeded==2)
                check(File(dest.path).listFiles()!!.count { it.isFile && hash(it)==before }==2)
                check(hash(File(source.path))==before)
            }
            case("T07 unavailable target and stale cached path") {
                val source=fixture("Invalid");val before=hash(File(source.path));val blocked=File(root,"blocked").apply { writeText("keep") }
                val bad=transfer(listOf(source),TransferDestination("bad","bad",blocked.path),TransferMode.MOVE)
                check(bad.failed==1);check(blocked.readText()=="keep" && hash(File(source.path))==before)
                val changed=source.copy(path=File(root,"outdated.png").path)
                check(transfer(listOf(changed),destination("StalePath"),TransferMode.COPY).succeeded==1)
                check(hash(File(source.path))==before)
            }
            case("T01 large real PNG spans many copy buffers") {
                val source=fixture("Large",large=true);val before=hash(File(source.path))
                check(File(source.path).length()>8L*1024*1024)
                val dest=destination("LargeTarget");check(transfer(listOf(source),dest,TransferMode.COPY).succeeded==1)
                check(hash(File(dest.path,File(source.path).name))==before && hash(File(source.path))==before)
            }
            case("T07 zero byte indexed source is safe") {
                val source=fixture("Zero");File(source.path).writeBytes(byteArrayOf())
                val dest=destination("ZeroTarget");val result=transfer(listOf(source),dest,TransferMode.COPY)
                check(File(source.path).exists() && File(source.path).length()==0L)
                if(result.succeeded==1) check(File(dest.path,File(source.path).name).length()==0L)
                else check(result.failed==1)
            }
            case("T07 replace must not hide or overwrite a directory with the same name") {
                for(mode in TransferMode.entries) {
                    val source=fixture("DirectorySource-$mode","same.png");val before=hash(File(source.path))
                    val dest=destination("DirectoryTarget-$mode");val occupied=File(dest.path,"same.png").apply { mkdirs() }
                    val child=File(occupied,"keep.txt").apply { writeText("existing-folder-content") }
                    val result=transfer(listOf(source),dest,mode,ConflictPolicy.REPLACE)
                    check(result.failed==1) { "REPLACE accepted an existing directory: $result" }
                    check(occupied.isDirectory && child.readText()=="existing-folder-content" && hash(File(source.path))==before)
                }
            }
            for(mode in TransferMode.entries) case("T08 $mode cancellation at initial progress starts no item") {
                val source=fixture("Cancel-$mode");val before=hash(File(source.path));val dest=destination("CancelTarget-$mode")
                supervisorScope {
                    val op=async(start=CoroutineStart.LAZY) {
                        val job=currentCoroutineContext().job
                        repo.transferMedia(listOf(source),dest,mode,ConflictPolicy.KEEP_BOTH) { if(it.completed==0) job.cancel() }
                    }
                    op.start();check(runCatching { op.await() }.exceptionOrNull() is CancellationException)
                }
                check(File(source.path).exists() && hash(File(source.path))==before) { "Cancelled $mode modified source before first item" }
                check(File(dest.path).listFiles().orEmpty().isEmpty()) { "Cancelled $mode created a destination before first item" }
            }
            for(mode in TransferMode.entries) case("T08 $mode cancellation between items retains completed item only") {
                val a=fixture("Between-$mode");val b=fixture("Between-$mode");val before=hash(File(b.path));val dest=destination("BetweenTarget-$mode")
                supervisorScope {
                    val op=async { val job=currentCoroutineContext().job
                        repo.transferMedia(listOf(a,b),dest,mode,ConflictPolicy.KEEP_BOTH) { if(it.completed==1) job.cancel() } }
                    check(runCatching { op.await() }.exceptionOrNull() is CancellationException)
                }
                check(File(dest.path,File(a.path).name).exists())
                check(File(b.path).exists() && hash(File(b.path))==before) { "Cancelled $mode started second item" }
                check(!File(dest.path,File(b.path).name).exists())
            }
            val authority="com.pixel.gallery.transfer.edge.test.documents"
            fun saf(name: String,fault: String): TransferDestination {
                val dest=destination(name)
                resolver.call(Uri.parse("content://$authority"),"configure",null,Bundle().apply { putString("root",dest.path);putString("fault",fault) })
                val tree=DocumentsContract.buildTreeDocumentUri(authority,"root")
                return dest.copy(documentUri=DocumentsContract.buildDocumentUriUsingTree(tree,"root").toString())
            }
            for(mode in TransferMode.entries) case("T09 SAF $mode real documents and changed rename URI") {
                val source=fixture("SafSource-$mode");val before=hash(File(source.path));val memberships=addTags(source)
                val dest=saf("SafTarget-$mode","none")
                val result=transfer(listOf(source),dest,mode)
                check(result.succeeded==1) { result.toString() };val file=File(dest.path,File(source.path).name);check(hash(file)==before)
                check(File(source.path).exists()==(mode==TransferMode.COPY))
                if(mode==TransferMode.MOVE) check(labels(targetEntry(file))==memberships)
            }
            for(mode in TransferMode.entries) case("T08 $mode cancellation during current document finishes one safe item") {
                val a=fixture("During-$mode");val b=fixture("During-$mode");val ah=hash(File(a.path));val bh=hash(File(b.path))
                val memberships=addTags(a);val dest=saf("DuringTarget-$mode","slow")
                supervisorScope {
                    val op=async { repo.transferMedia(listOf(a,b),dest,mode,ConflictPolicy.KEEP_BOTH) {} }
                    withTimeout(10_000) { while(File(dest.path).listFiles().orEmpty().none { it.name.startsWith(".pixel-transfer-") }) delay(10) }
                    op.cancel();op.join()
                }
                val target=File(dest.path,File(a.path).name)
                check(target.isFile && hash(target)==ah);check(File(a.path).exists()==(mode==TransferMode.COPY))
                if(mode==TransferMode.MOVE) check(labels(targetEntry(target))==memberships)
                check(hash(File(b.path))==bh && !File(dest.path,File(b.path).name).exists())
                check(File(dest.path).listFiles()!!.size==1)
            }
            for(fault in listOf("write","corrupt","rename","size","permission")) case("T09 SAF $fault failure retains source and removes owned target") {
                val source=fixture("Fault-$fault");val before=hash(File(source.path));val dest=saf("FaultTarget-$fault",fault)
                check(transfer(listOf(source),dest,TransferMode.MOVE).failed==1)
                check(hash(File(source.path))==before);check(File(dest.path).listFiles().orEmpty().isEmpty())
            }
            case("T09 SAF skip keep-both and unsupported replace") {
                val source=fixture("SafConflict","same.png");val old=fixture("SafConflictTarget","same.png")
                val before=hash(File(source.path));val oldHash=hash(File(old.path));val dest=saf("SafConflictTarget","none")
                check(transfer(listOf(source),dest,TransferMode.MOVE,ConflictPolicy.SKIP).skipped==1)
                check(transfer(listOf(source),dest,TransferMode.MOVE,ConflictPolicy.REPLACE).failed==1)
                check(hash(File(source.path))==before && hash(File(old.path))==oldHash)
                check(transfer(listOf(source),dest,TransferMode.COPY).succeeded==1)
                check(hash(File(dest.path,"same (1).png"))==before && hash(File(old.path))==oldHash)
            }
            case("T10 replacement journals recover every valid stage") {
                for(mode in TransferMode.entries) for(stage in ReplacementStage.entries) {
                    val folder=File(root,"Recovery-$mode-$stage").apply { mkdirs() }
                    val source=File(folder,"source.png");val target=File(folder,"target.png")
                    val temporary=File(folder,".pixel-transfer-test.tmp");val old=File(folder,".pixel-backup-test.tmp")
                    source.writeText("source-bytes");target.writeText("old-target")
                    when(stage) {
                        ReplacementStage.CREATED -> temporary.writeText("partial")
                        ReplacementStage.TEMP_READY -> temporary.writeText("source-bytes")
                        ReplacementStage.TARGET_BACKED_UP -> { check(target.renameTo(old));temporary.writeText("source-bytes") }
                        ReplacementStage.TARGET_COMMITTED,ReplacementStage.SOURCE_REMOVED -> { check(target.renameTo(old));target.writeText("source-bytes") }
                    }
                    if(stage==ReplacementStage.SOURCE_REMOVED && mode==TransferMode.MOVE) check(source.delete())
                    val childContext=object:ContextWrapper(isolated) {
                        override fun getFilesDir()=File(isolated.filesDir,"recovery-$mode-$stage").apply { mkdirs() }
                        override fun getApplicationContext():Context=this
                    }
                    val journal=File(childContext.filesDir,"media-transfer-journal/test.json").apply { parentFile!!.mkdirs() }
                    journal.writeText(com.google.gson.Gson().toJson(mapOf("sourcePath" to source.path,"targetPath" to target.path,
                        "temporaryPath" to temporary.path,"backupPath" to old.path,"mode" to mode.name,"stage" to stage.name)))
                    val recovery=MediaRepository(childContext,db.mediaDao(),SettingsRepository(childContext))
                    recovery.transferMedia(emptyList(),destination("RecoveryEmpty"),TransferMode.COPY,ConflictPolicy.SKIP) {}
                    val expected=if(stage==ReplacementStage.SOURCE_REMOVED || stage==ReplacementStage.TARGET_COMMITTED && mode==TransferMode.COPY) "source-bytes" else "old-target"
                    check(target.readText()==expected);check(!old.exists() && !temporary.exists() && !journal.exists())
                    if(source.exists())check(source.readText()=="source-bytes")
                }
            }
            case("T10 incomplete rollback journal must retain remaining target") {
                val folder=File(root,"RecoveryMissingBackup").apply { mkdirs() }
                val source=File(folder,"source.png").apply { writeText("source-bytes") }
                val target=File(folder,"target.png").apply { writeText("remaining-target") }
                val temporary=File(folder,".pixel-transfer-test.tmp")
                val old=File(folder,".pixel-backup-missing.tmp")
                val childContext=object:ContextWrapper(isolated) {
                    override fun getFilesDir()=File(isolated.filesDir,"recovery-missing").apply { mkdirs() }
                    override fun getApplicationContext():Context=this
                }
                val journal=File(childContext.filesDir,"media-transfer-journal/test.json").apply { parentFile!!.mkdirs() }
                journal.writeText(com.google.gson.Gson().toJson(mapOf("sourcePath" to source.path,"targetPath" to target.path,
                    "temporaryPath" to temporary.path,"backupPath" to old.path,"mode" to "MOVE","stage" to "TARGET_COMMITTED")))
                val recovery=MediaRepository(childContext,db.mediaDao(),SettingsRepository(childContext))
                recovery.transferMedia(emptyList(),destination("RecoveryMissingEmpty"),TransferMode.COPY,ConflictPolicy.SKIP) {}
                check(target.exists() && target.readText()=="remaining-target") { "Missing backup recovery deleted remaining target" }
                check(source.readText()=="source-bytes" && journal.exists())
            }
            check(failures==0) { report.joinToString("\n") }
            report.joinToString("\n")
        } finally {
            owned.distinct().forEach { runCatching { resolver.delete(it,null,null) } }
            resolver.query(MediaStore.Files.getContentUri("external"),arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DATA + " LIKE ?",arrayOf(root.path + "/%"),null)?.use { c ->
                val ids=buildList { while(c.moveToNext()) add(c.getLong(0)) }
                ids.forEach { id -> runCatching { resolver.delete(Uri.withAppendedPath(MediaStore.Files.getContentUri("external"),id.toString()),null,null) } }
            }
            // Only this run's canonical UUID tree, including unpublished fault-provider files.
            check(root.name=="TransferEdge_$token" && root.parentFile!!.canonicalPath==File(android.os.Environment.getExternalStorageDirectory(),"Pictures").canonicalPath)
            root.walkBottomUp().forEach { file -> check(file.canonicalPath==root.path || file.canonicalPath.startsWith(root.path+File.separator));file.delete() }
        }
    }
}
