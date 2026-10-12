package com.pixel.gallery.integration

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.room.Room
import androidx.exifinterface.media.ExifInterface
import com.google.gson.Gson
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.entity.FavouriteEntry
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.QuickSortQueueRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume

/** Self-contained instrumentation; no production media or production queue is submitted for mutation. */
class QuickSortSafetyInstrumentation : Instrumentation() {
    private var confirmationSuite = false
    private var scrollbarSuite = false
    private var uiSuite = false
    private var duplicateSuite = false
    override fun onCreate(arguments: Bundle?) {
        confirmationSuite = arguments?.getString("suite") == "confirmations"
        scrollbarSuite = arguments?.getString("suite") == "scrollbar"
        uiSuite = arguments?.getString("suite") == "redesign"
        duplicateSuite = arguments?.getString("suite") == "duplicates"
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            val report = when {
                confirmationSuite -> ConfirmationTouchChecks(this).exercise()
                duplicateSuite -> DuplicateTouchChecks(this).exercise()
                uiSuite -> RedesignTouchChecks(this).exercise()
                scrollbarSuite -> ScrollbarTouchChecks(this).exercise()
                else -> runBlocking { exercise() }
            }
            val suite = when { confirmationSuite -> "Confirmation and transfer UI"; duplicateSuite -> "Duplicate emulator"; uiSuite -> "UI redesign"; scrollbarSuite -> "Scrollbar touch"; else -> "Quick-sort synthetic safety" }
            results.putString("stream", "\n$suite checks passed:\n$report\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", "\n${when { duplicateSuite -> "Duplicate emulator"; uiSuite -> "UI redesign"; scrollbarSuite -> "Scrollbar touch"; else -> "Quick-sort safety" }} check failed:\n${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private suspend fun exercise(): String {
        val token = UUID.randomUUID().toString()
        val rootRelative = "Pictures/PixelQuickSortTest_$token/"
        val createdUris = mutableListOf<Uri>()
        val isolated = IsolatedContext(targetContext, token)
        val database = Room.inMemoryDatabaseBuilder(isolated, GalleryDatabase::class.java)
            .addCallback(LABEL_DATABASE_CALLBACK).build()
        val repository = MediaRepository(isolated, database.mediaDao(), SettingsRepository(isolated))
        val report = mutableListOf<String>()

        suspend fun synthetic(name: String, subdirectory: String, color: Int): MediaEntry {
            val resolver = targetContext.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rootRelative + subdirectory + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Could not create synthetic fixture")
            createdUris += uri
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            bitmap.recycle()
            fun currentPath(): String = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
                check(it.moveToFirst()); it.getString(0)
            }
            // MediaProvider derives datetaken from actual JPEG metadata and ignores direct field edits.
            val exifTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(CAPTURE_TIME))
            ExifInterface(currentPath()).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifTime)
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifTime)
                setAttribute(ExifInterface.TAG_DATETIME, exifTime)
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, "+00:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME, "+00:00")
                saveAttributes()
            }
            restoreMediaModifiedTime(File(currentPath()), MODIFIED_TIME)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val path = currentPath()
            val file = File(path)
            check(file.canonicalPath.contains("PixelQuickSortTest_$token")) { "Fixture escaped isolated directory" }
            restoreMediaModifiedTime(file, MODIFIED_TIME)
            val scannedUri = withTimeout(15_000) {
                suspendCancellableCoroutine<Uri?> { continuation ->
                    android.media.MediaScannerConnection.scanFile(targetContext, arrayOf(path), arrayOf("image/jpeg")) { _, scanned ->
                        if (continuation.isActive) continuation.resume(scanned)
                    }
                }
            } ?: error("Synthetic fixture could not be scanned")
            createdUris += scannedUri
            val times = resolver.query(scannedUri, arrayOf(MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Images.Media.DATE_TAKEN), null, null, null)!!.use {
                check(it.moveToFirst()); Triple(it.getLong(0), it.getLong(1) * 1000, it.getLong(2))
            }
            check(times.second == MODIFIED_TIME) { "Fixture source mtime was not indexed: ${times.second}" }
            check(times.third == CAPTURE_TIME) { "Fixture EXIF capture time was not indexed: ${times.third}" }
            return MediaEntry(scannedUri.lastPathSegment!!.toLong(), scannedUri.toString(), path, "image/jpeg", 16, 16, 0,
                file.length(), times.first, times.second, times.third, bestTimestamp = times.third)
        }

        try {
            val original = synthetic("same-name.jpg", "Source", 0xffff0000.toInt())
            val existing = synthetic("same-name.jpg", "Target", 0xff00ff00.toInt())
            val originalFingerprint = fingerprintMediaFile(File(original.path))
            val existingFingerprint = fingerprintMediaFile(File(existing.path))
            val destinationPath = File(existing.path).parent!!
            val destination = TransferDestination(destinationPath, "Synthetic target", destinationPath)
            database.mediaDao().insertAll(listOf(original, existing))
            database.mediaDao().addFavourite(FavouriteEntry(original.contentId))
            val checkpoints = mutableListOf<QuickMoveCheckpoint>()
            val receipt = repository.moveForQuickSort(original, destination, "synthetic-$token") { checkpoints += it }
            createdUris += Uri.parse(receipt.targetUri)
            check(!File(original.path).exists()) { "MOVE left source behind" }
            check(File(receipt.targetPath).name != "same-name.jpg") { "Conflict replaced existing name" }
            verifyMediaFile(File(receipt.targetPath), originalFingerprint, verifyTime = true)
            verifyMediaFile(File(existing.path), existingFingerprint, verifyTime = true)
            check(receipt.captureTimeVerified && receipt.modifiedTimeVerified && receipt.contentVerified)
            check(database.mediaDao().isFavourite(receipt.targetId).first()) { "Favourite/label mapping was lost" }
            check(checkpoints.first().stage == QuickMoveStage.Planned && checkpoints.last().stage == QuickMoveStage.Completed)
            report += "MOVE removed only synthetic source; equal-name target preserved; SHA-256, mtime, DATE_TAKEN and label checked"
            val recovered = repository.recoverQuickMove(checkpoints.last())
            check(recovered.targetPath == receipt.targetPath)
            report += "Committed checkpoint recovery verified destination and repaired labels without deleting files"

            val interruptedSource = synthetic("interrupted-source.jpg", "Source", 0xff663399.toInt())
            val interruptedTarget = synthetic("interrupted-target.jpg", "Target", 0xff663399.toInt())
            val interruptedFingerprint = fingerprintMediaFile(File(interruptedSource.path))
            val interruptedCheckpoint = QuickMoveCheckpoint(interruptedSource, interruptedTarget.path, null,
                interruptedFingerprint.modifiedMillis, interruptedFingerprint.bytes, interruptedFingerprint.sha256,
                QuickMoveStage.TargetReady, interruptedTarget.uri)
            val recovery = runCatching { repository.recoverQuickMove(interruptedCheckpoint) }
            check(recovery.isFailure)
            verifyMediaFile(File(interruptedSource.path), interruptedFingerprint, verifyTime = true)
            verifyMediaFile(File(interruptedTarget.path), interruptedFingerprint, verifyTime = true)
            report += "Interrupted transfer with both copies present retained both and refused destructive replay"

            val queued = synthetic("queued.jpg", "Source", 0xff0000ff.toInt())
            val queue = QuickSortQueueRepository(isolated, repository)
            queue.state.first { it.loaded }
            check(queue.state.value.jobs.isEmpty()) { "Fixture unexpectedly reached another queue" }
            val accepted = queue.enqueue(listOf(queued), destination) as QuickSortEnqueueResult.Accepted
            check(queue.enqueue(listOf(queued), destination) is QuickSortEnqueueResult.Rejected)
            val journalFile = File(isolated.filesDir, "quick-sort-queue.json")
            check(journalFile.isFile && journalFile.readText().contains(accepted.jobId))
            val resumedQueue = QuickSortQueueRepository(isolated, repository)
            resumedQueue.state.first { it.loaded }
            check(queued.contentId in resumedQueue.state.value.reservedIds)
            resumedQueue.cancel(accepted.jobId)
            check(resumedQueue.state.value.reservedIds.isEmpty())
            check(File(queued.path).exists()) { "Cancelling unstarted job modified source" }
            report += "Atomic journal readback, reconstructed queue reservations, duplicate rejection and pending cancellation checked"

            val run = resumedQueue.enqueue(listOf(queued), destination) as QuickSortEnqueueResult.Accepted
            resumedQueue.runUntilIdle()
            val job = resumedQueue.state.value.jobs.first { it.id == run.jobId }
            if (job.status == QuickSortJobStatus.NeedsPermission) {
                check(File(queued.path).exists())
                report += "Queue safely paused for system permission; service/UI authorization requires separate UI validation"
            } else {
                check(job.failed == 0 && job.completed == 1) { job.items.joinToString { it.error.orEmpty() } }
                val queuedReceipt = requireNotNull(job.items.single().receipt)
                createdUris += Uri.parse(queuedReceipt.targetUri)
                check(!File(queued.path).exists())
                report += "Persisted queue executed exactly one synthetic MOVE and saved a success receipt"
                val use = resumedQueue.state.value.albumUses.single { it.jobId == run.jobId }
                check(use.destinationKey == destination.stableKey && use.usedAt == job.firstSucceededAt)
                check(resumedQueue.state.value.albumUses.none { it.jobId == accepted.jobId })
                val reloadedUsage = QuickSortQueueRepository(isolated, repository)
                reloadedUsage.state.first { it.loaded }
                check(reloadedUsage.state.value.albumUses == resumedQueue.state.value.albumUses)
                report += "Only successful batches count as album uses; first-success time and usage survive journal reload"
            }

            // Reconstruct an interrupted on-disk batch in the isolated journal, never the application's queue.
            val interruptedJob = QuickSortJob("interrupted-$token", destination,
                listOf(QuickSortQueueItem(interruptedSource, QuickSortItemStatus.Running, interruptedCheckpoint)),
                System.currentTimeMillis(), QuickSortJobStatus.Running)
            val atomicJournal = android.util.AtomicFile(journalFile)
            val output = atomicJournal.startWrite()
            try {
                output.write(Gson().toJson(QuickSortQueueJournal(jobs = listOf(interruptedJob))).toByteArray())
                atomicJournal.finishWrite(output)
            } catch (error: Exception) { atomicJournal.failWrite(output); throw error }
            val recoveredQueue = QuickSortQueueRepository(isolated, repository)
            recoveredQueue.state.first { it.loaded }
            val recoveredItem = recoveredQueue.state.value.jobs.single().items.single()
            check(recoveredItem.status == QuickSortItemStatus.Failed && recoveredItem.error!!.contains("both"))
            check(File(interruptedSource.path).exists() && File(interruptedTarget.path).exists())
            report += "Startup journal recovery retained ambiguous copies and persisted a review failure"
            return report.joinToString("\n")
        } finally {
            // URIs are created by this invocation only. No broad collection deletion or folder traversal.
            createdUris.distinct().forEach { runCatching { targetContext.contentResolver.delete(it, null, null) } }
            // Repository observers have application lifetime; do not close an in-use in-memory database here.
        }
    }

    private class IsolatedContext(base: Context, token: String) : ContextWrapper(base) {
        private val isolatedFiles = File(base.filesDir, "quick-sort-safety-test/$token").apply { mkdirs() }
        private val isolatedCache = File(base.cacheDir, "quick-sort-safety-test/$token").apply { mkdirs() }
        override fun getFilesDir(): File = isolatedFiles
        override fun getCacheDir(): File = isolatedCache
        override fun getApplicationContext(): Context = this
    }

    companion object {
        private const val MODIFIED_TIME = 1_600_000_000_000L
        private const val CAPTURE_TIME = 1_590_000_000_000L
    }
}
