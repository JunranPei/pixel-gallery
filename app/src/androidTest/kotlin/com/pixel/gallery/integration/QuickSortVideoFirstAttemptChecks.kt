package com.pixel.gallery.integration

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

/** One real video per operation, new UUID files and isolated settings/database. Never hides first failures by retrying. */
internal class QuickSortVideoFirstAttemptChecks(private val context: Context, private val filter: String? = null) {
    fun exercise():String=runBlocking {
        val token=UUID.randomUUID().toString()
        val relative="Movies/QuickSortVideoFirst_$token/"
        val root=File(Environment.getExternalStorageDirectory(),relative).canonicalFile
        val resolver=context.contentResolver
        val isolated=object:ContextWrapper(context) {
            override fun getFilesDir()=File(context.filesDir,"video-first/$token/files").apply { mkdirs() }
            override fun getCacheDir()=File(context.cacheDir,"video-first/$token/cache").apply { mkdirs() }
            override fun getApplicationContext():Context=this
        }
        val db=Room.inMemoryDatabaseBuilder(isolated,GalleryDatabase::class.java).addCallback(LABEL_DATABASE_CALLBACK).build()
        val repo=MediaRepository(isolated,db.mediaDao(),SettingsRepository(isolated))
        val owned=mutableListOf<Uri>();val report=mutableListOf<String>();var failures=0
        report+="INFO process=${android.os.Process.myPid()} sdk=${android.os.Build.VERSION.SDK_INT} UUID=$token"
        val output=File(context.cacheDir,"quicksort-video-first-results.txt")
        fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        fun times(uri:Uri):Triple<Long,Long,Long?> = resolver.query(uri,arrayOf(MediaStore.MediaColumns.DATE_ADDED,MediaStore.MediaColumns.DATE_MODIFIED,MediaStore.Video.Media.DATE_TAKEN),null,null,null)!!.use {
            check(it.moveToFirst());Triple(it.getLong(0),it.getLong(1)*1000L,if(it.isNull(2))null else it.getLong(2))
        }
        suspend fun scan(path:String):Uri=withTimeout(15_000) { suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context,arrayOf(path),null) { _,uri -> if(cont.isActive) { if(uri==null)cont.cancel(IllegalStateException("Video scan failed")) else cont.resume(uri) } }
        } }
        val samples=File(Environment.getExternalStorageDirectory(),"Download/QuickSortVideoFirstInputs20261020").listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        try {
            for(input in samples) for(state in listOf("stable","fresh","refresh","queue") + if(input.extension=="mov") listOf("conflict-undo","changed-target") else emptyList()) {
                val name="$state ${input.name}"
                if(filter!=null && !name.contains(filter))continue
                val folder="${state}-${input.name}";val sourceFolder=relative+folder+"/Source/"
                val uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,input.name);put(MediaStore.MediaColumns.MIME_TYPE,if(input.extension=="mov")"video/quicktime" else "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,sourceFolder);put(MediaStore.MediaColumns.IS_PENDING,1)
                })!!;owned+=uri
                resolver.openOutputStream(uri)!!.use { out -> input.inputStream().use { it.copyTo(out) } }
                resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
                val path=resolver.query(uri,arrayOf(MediaStore.MediaColumns.DATA),null,null,null)!!.use { check(it.moveToFirst());it.getString(0) }
                val source=File(path);check(source.canonicalPath.startsWith(root.path+"/"));check(source.setLastModified(1_600_000_000_123L))
                if(state=="stable" || state=="queue") scan(path)
                val original=times(uri);val before=hash(source);val modified=source.lastModified()
                val embedded=MediaMetadataRetriever().use { it.setDataSource(path);check(it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)=="yes");it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) }
                val indexedMime=resolver.getType(uri) ?: error("Missing indexed video MIME")
                val entry=MediaEntry(uri.lastPathSegment!!.toLong(),uri.toString(),path,indexedMime,320,240,0,source.length(),original.first,modified,original.third,durationMillis=1000L,bestTimestamp=modified)
                db.mediaDao().insertAll(listOf(entry));db.labelDao().addMemberships(listOf(MediaLabelEntry(1L,entry.contentId)))
                val directory=File(root,"$folder/Target").apply { mkdirs() };val destination=TransferDestination(directory.path,directory.name,directory.path)
                val occupied=if(state=="conflict-undo") File(directory,source.name).apply {
                    writeBytes(samples.first { it.name=="h264-no-date.mp4" }.readBytes());scan(absolutePath)
                } else null
                val occupiedHash=occupied?.let(::hash)
                val checkpoints=mutableListOf<QuickMoveCheckpoint>()
                try {
                    val receipt=if(state=="queue") {
                        val queue=QuickSortQueueRepository(isolated,repo);queue.state.first { it.loaded }
                        val accepted=queue.enqueue(listOf(entry),destination) as QuickSortEnqueueResult.Accepted
                        queue.runUntilIdle()
                        val job=queue.state.value.jobs.single { it.id==accepted.jobId }
                        check(job.completed==1 && job.failed==0) { job.items.single().error?:job.status.toString() }
                        requireNotNull(job.items.single().receipt)
                    } else repo.moveForQuickSort(entry,destination,"video-first-$token-${report.size}") { checkpoint ->
                        checkpoints+=checkpoint
                        if(state=="refresh" && checkpoint.stage==QuickMoveStage.Planned) scan(path)
                        if(state=="changed-target" && checkpoint.stage==QuickMoveStage.TargetReady) {
                            val target=File(checkpoint.targetPath);val bytes=target.readBytes();bytes[0]=(bytes[0].toInt() xor 1).toByte();target.writeBytes(bytes)
                        }
                    }
                    owned+=Uri.parse(receipt.targetUri)
                    check(!source.exists());val target=File(receipt.targetPath)
                    check(hash(target)==before && target.lastModified()==modified)
                    check(target.name==(if(state=="conflict-undo") "${source.nameWithoutExtension} (1).${source.extension}" else source.name)) { "Unrequested filename change ${source.name} -> ${target.name}" }
                    check(db.labelDao().getMemberships().any { it.contentId==receipt.targetId && it.labelId==1L })
                    val finalTimes=times(Uri.parse(receipt.targetUri))
                    if(state=="conflict-undo") {
                        check(hash(occupied!!)==occupiedHash)
                        val moved=entry.copy(contentId=receipt.targetId,uri=receipt.targetUri,path=receipt.targetPath)
                        val originalDir=source.parentFile!!
                        val undo=repo.moveForQuickSort(moved,TransferDestination(originalDir.path,originalDir.name,originalDir.path),"undo-video-$token",restoreCheckpoint=checkpoints.last()) {}
                        owned+=Uri.parse(undo.targetUri)
                        check(source.isFile && hash(source)==before && source.lastModified()==modified && !target.exists())
                        check(hash(occupied)==occupiedHash && db.labelDao().getMemberships().any { it.contentId==undo.targetId && it.labelId==1L })
                    }
                    report+="PASS $name original=$original embedded=$embedded final=$finalTimes"
                } catch(error:Throwable) {
                    if(error is CancellationException)throw error
                    if(state=="changed-target" && checkpoints.any { it.stage==QuickMoveStage.TargetReady } && source.isFile && hash(source)==before && directory.listFiles().orEmpty().isEmpty()) {
                        check(db.labelDao().getMemberships().any { it.contentId==entry.contentId && it.labelId==1L })
                        report+="PASS $name corrupt target rejected before source removal; source bytes and label retained"
                        output.writeText(report.joinToString("\n"));continue
                    }
                    failures++
                    val remaining=File(root,folder).walkTopDown().filter { it.isFile }.toList()
                    report+="FAIL $name sourceName=${source.name} mime=$indexedMime original=$original embedded=$embedded after=${runCatching { times(uri) }.getOrNull()} stages=${checkpoints.map { it.stage }} remaining=${remaining.map { it.relativeTo(root).path to (hash(it)==before) }}: ${error.stackTraceToString()}"
                    output.writeText(report.joinToString("\n"))
                    check(remaining.any { hash(it)==before }) { "No verified original content remains for $name: ${error.message}" }
                }
                output.writeText(report.joinToString("\n"))
            }
            check(failures==0) { report.joinToString("\n") }
            report.joinToString("\n")
        } finally {
            owned.distinct().forEach { runCatching { resolver.delete(it,null,null) } }
            resolver.query(MediaStore.Files.getContentUri("external"),arrayOf(MediaStore.MediaColumns._ID),MediaStore.MediaColumns.DATA+" LIKE ?",arrayOf(root.path+"/%"),null)?.use { c ->
                val ids=buildList { while(c.moveToNext())add(c.getLong(0)) };ids.forEach { id -> runCatching { resolver.delete(Uri.withAppendedPath(MediaStore.Files.getContentUri("external"),id.toString()),null,null) } }
            }
            check(root.name=="QuickSortVideoFirst_$token" && root.parentFile!!.canonicalPath==File(Environment.getExternalStorageDirectory(),"Movies").canonicalPath)
            root.walkBottomUp().forEach { f -> check(f.canonicalPath==root.path || f.canonicalPath.startsWith(root.path+"/"));f.delete() }
        }
    }
}
