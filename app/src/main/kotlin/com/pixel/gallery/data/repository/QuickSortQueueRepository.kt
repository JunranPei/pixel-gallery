package com.pixel.gallery.data.repository

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.*
import com.pixel.gallery.services.QuickSortService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Application-owned, persisted FIFO. Only the foreground service owns the actual execution loop. */
@Singleton
class QuickSortQueueRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media: MediaRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val executionMutex = Mutex()
    private val gson = Gson()
    private val journal = AtomicFile(File(context.filesDir, "quick-sort-queue.json"))
    private val ready = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(QuickSortQueueState())
    val state: StateFlow<QuickSortQueueState> = mutableState.asStateFlow()
    private val pendingFailure = MutableStateFlow<QuickSortMoveFailure?>(null)
    val failure: StateFlow<QuickSortMoveFailure?> = pendingFailure.asStateFlow()
    fun dismissFailure(shown: QuickSortMoveFailure) { pendingFailure.compareAndSet(shown, null) }

    private fun reportFinishedFailures(previous: List<QuickSortJob>, jobs: List<QuickSortJob>) {
        val finished = jobs.filter { job -> job.isTerminal && job.failed > 0 &&
            previous.any { it.id == job.id && !it.isTerminal } }
        val failures = finished.flatMap { it.items }.filter { it.status == QuickSortItemStatus.Failed }
        if (failures.isEmpty()) return
        val reason = failures.map { it.error }.distinct().singleOrNull()
        val current = pendingFailure.value
        pendingFailure.value = QuickSortMoveFailure((current?.count ?: 0) + failures.size,
            if (current == null || current.reason == reason) reason else null)
    }

    private var claimedPermissionToken: String? = null
    @Volatile private var pauseRequested = false

    init {
        scope.launch {
            try {
                val persisted = if (journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()) {
                    journal.openRead().bufferedReader().use { gson.fromJson(it, QuickSortQueueJournal::class.java) }
                        ?: error("Queue journal is empty")
                } else QuickSortQueueJournal()
                require(persisted.version == 1) { "Unsupported queue journal version" }
                mutableState.value = state.value.copy(albumUses = persisted.albumUses.orEmpty())
                var recoveredJobs = persisted.jobs
                // No destructive replay: recover a committed target or leave both copies for review.
                recoveredJobs = recoveredJobs.map { job ->
                    val recoveredItems = job.items.map { saved ->
                        var item = saved
                        val replacement = item.replacementCheckpoint
                        val restored = item.replacementRestoreCheckpoint
                        if (restored != null && restored.stage >= QuickMoveStage.SourceRemoved && !File(restored.source.path).exists()) {
                            item = runCatching { media.recoverQuickMove(restored) }.fold(
                                onSuccess = { item.copy(replacementReleased = true, replacementRestoreReceipt = it) },
                                onFailure = { item.copy(status = QuickSortItemStatus.Failed, error = "Replacement restoration needs review: ${it.message}") })
                        } else if (replacement != null && replacement.stage >= QuickMoveStage.SourceRemoved &&
                            item.replacementReceipt == null && !item.replacementReleased) {
                            item = runCatching { media.recoverQuickMove(replacement) }.fold(
                                onSuccess = { item.copy(replacementReceipt = it) },
                                onFailure = { item.copy(status = QuickSortItemStatus.Failed, error = "Replacement backup needs review: ${it.message}") })
                        }
                        val needsRepair = item.status == QuickSortItemStatus.Running ||
                            (item.status == QuickSortItemStatus.Failed && item.checkpoint?.let {
                                it.stage >= QuickMoveStage.SourceRemoved && !File(it.source.path).exists()
                            } == true)
                        if (!needsRepair) item else {
                            val checkpoint = item.checkpoint
                            if (checkpoint == null) item.copy(status = QuickSortItemStatus.Queued)
                            else runCatching { media.recoverQuickMove(checkpoint) }.fold(
                                onSuccess = { item.copy(status = QuickSortItemStatus.Succeeded, receipt = it, error = null) },
                                onFailure = { item.copy(status = QuickSortItemStatus.Failed, error = it.message ?: "Interrupted move needs review") },
                            )
                        }
                    }
                    val unfinished = recoveredItems.any { it.status == QuickSortItemStatus.Queued }
                    job.copy(items = recoveredItems, sessionClosed = job.sessionClosed ||
                        (job.sessionId != null && job.sessionProcessId != processSessionId),
                        status = when {
                            job.cancelRequested -> QuickSortJobStatus.Cancelled
                            !unfinished -> if (job.isTerminal) job.status else QuickSortJobStatus.Completed
                            job.status == QuickSortJobStatus.NeedsPermission -> QuickSortJobStatus.NeedsPermission
                            job.status == QuickSortJobStatus.NeedsConflictDecision -> QuickSortJobStatus.NeedsConflictDecision
                            else -> QuickSortJobStatus.Paused
                        }, permissionGranted = false)
                }
                for (job in recoveredJobs) for ((index, item) in job.items.withIndex()) {
                    if (item.status == QuickSortItemStatus.Succeeded && item.retryOrigin != null) {
                        recoveredJobs = resolveQuickSortRetrySuccess(recoveredJobs, job.id, index)
                    }
                }
                recoveredJobs = resolveLegacyQuickSortFailures(recoveredJobs)
                reportFinishedFailures(persisted.jobs, recoveredJobs)
                mutex.withLock { persist(trimHistory(recoveredJobs)) }
                cleanupClosedSessionBackups()
                if (recoveredJobs.any { job -> job.items.any { it.receipt != null } }) {
                    runCatching { media.syncWhenMediaMutationsIdle(force = true) }
                }
            } catch (error: Exception) {
                // Preserve unreadable journals. Never replace evidence with an empty queue.
                mutableState.value = QuickSortQueueState(loaded = true,
                    error = "Saved quick-sort queue needs attention: ${error.message}")
            } finally {
                ready.complete(Unit)
            }
        }
    }

    suspend fun enqueue(entries: List<MediaEntry>, destination: TransferDestination, sessionId: String? = null): QuickSortEnqueueResult = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            state.value.error?.let { return@withLock QuickSortEnqueueResult.Rejected(it) }
            if (destination.documentUri != null) return@withLock QuickSortEnqueueResult.Rejected(
                "Quick sort requires a local album that can preserve original timestamps")
            if (!File(destination.path).isDirectory) return@withLock QuickSortEnqueueResult.Rejected("Destination album is unavailable")
            if (entries.isEmpty()) return@withLock QuickSortEnqueueResult.Rejected("No files selected")
            val eligible = entries.filterNot { isSameTransferDirectory(it.path, destination.path) }
            if (eligible.isEmpty()) return@withLock QuickSortEnqueueResult.Rejected("Items are already in the destination album")
            val accepted = selectUnreservedQuickSortEntries(eligible, state.value)
            if (accepted.isEmpty()) return@withLock QuickSortEnqueueResult.Rejected("Items are already being moved")
            if (state.value.pendingCount + accepted.size > MAX_PENDING_ITEMS || state.value.jobs.count { !it.isTerminal } >= MAX_PENDING_JOBS) {
                return@withLock QuickSortEnqueueResult.Rejected("Queue is full; wait for a batch to finish")
            }
            val id = UUID.randomUUID().toString()
            val job = QuickSortJob(id, destination, accepted.map { QuickSortQueueItem(it) }, System.currentTimeMillis(),
                sessionId = sessionId, sessionProcessId = processSessionId)
            try {
                persist(trimHistory(state.value.jobs) + job)
                QuickSortEnqueueResult.Accepted(id, accepted.size, entries.size - accepted.size, accepted.mapTo(mutableSetOf()) { it.contentId })
            } catch (error: Exception) {
                QuickSortEnqueueResult.Rejected("Could not save queue: ${error.message}")
            }
        }
    }

    /** Call only while an Activity is foreground. Android may reject a background FGS launch. */
    fun startFromForeground(): Boolean = runCatching {
        pauseRequested = false
        ContextCompat.startForegroundService(context, Intent(context, QuickSortService::class.java))
        true
    }.getOrDefault(false)

    /** A token is delivered at most once per process until explicitly released/answered. */
    suspend fun nextPermission(): QuickSortPermissionRequest? = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val job = state.value.needsPermission ?: return@withLock null
            val token = job.permissionToken ?: return@withLock null
            if (claimedPermissionToken != null) return@withLock null
            try {
                val request = media.createTransferWriteRequest(job.items.filter { it.status == QuickSortItemStatus.Queued }.map { it.entry })
                if (request == null) {
                    updateJob(job.id) { it.copy(permissionGranted = true, permissionToken = null, status = QuickSortJobStatus.Queued) }
                    return@withLock null
                }
                claimedPermissionToken = token
                QuickSortPermissionRequest(job.id, token, request)
            } catch (error: Exception) {
                failQueued(job.id, "Could not request storage permission: ${error.message}")
                null
            }
        }
    }

    suspend fun releasePermission(token: String) = mutex.withLock {
        releaseClaim(token)
    }

    fun releasePermissionFromOwner(token: String) { scope.launch { releasePermission(token) } }

    private fun releaseClaim(token: String) {
        if (claimedPermissionToken == token) {
            claimedPermissionToken = null
            mutableState.value = state.value.copy(permissionRevision = state.value.permissionRevision + 1)
        }
    }

    suspend fun authorize(jobId: String, token: String, granted: Boolean) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            releaseClaim(token)
            val job = state.value.jobs.firstOrNull { it.id == jobId } ?: return@withLock
            if (!matchesQuickSortPermission(job, jobId, token)) return@withLock
            if (!granted) {
                failQueued(jobId, "Storage permission was not granted; files were kept")
            } else {
                // Android 10 may grant one URI at a time; check the rest before any mutations.
                val another = try {
                    if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q)
                        media.createTransferWriteRequest(job.items.filter { it.status == QuickSortItemStatus.Queued }.map { it.entry }) else null
                } catch (error: Exception) {
                    failQueued(jobId, "Storage permission could not be verified: ${error.message}")
                    return@withLock
                }
                updateJob(jobId) { it.copy(permissionGranted = another == null,
                    permissionToken = if (another == null) null else UUID.randomUUID().toString(),
                    status = if (another == null) QuickSortJobStatus.Queued else QuickSortJobStatus.NeedsPermission) }
            }
        }
    }

    suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val job = state.value.jobs.firstOrNull { it.id == jobId } ?: return@withLock
            if (job.isTerminal) return@withLock
            // A different window can cancel a batch while its system dialog is still open.
            // Keep the dialog owner's claim until its callback/release, preventing a second dialog.
            updateJob(jobId, ::cancelQuickSortJob)
        }
    }

    suspend fun requestUndo(jobId: String, sessionId: String): String? = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val job = latestUndoableQuickSortJob(state.value, sessionId)
            if (job?.id != jobId) return@withLock "This batch is no longer available to undo"
            if (state.value.pendingCount + job.completed > MAX_PENDING_ITEMS) return@withLock "Wait for the queued moves to finish before undoing"
            updateJob(job.id) { cancelQuickSortJob(it).copy(undoRequested = true) }
            null
        }
    }

    suspend fun resolveConflict(jobId: String, policy: ConflictPolicy) = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val job = state.value.jobs.firstOrNull { it.id == jobId } ?: return@withLock
            if (job.status != QuickSortJobStatus.NeedsConflictDecision) return@withLock
            updateJob(job.id) { it.copy(status = QuickSortJobStatus.Queued, conflictPolicy = policy) }
        }
    }

    fun finishSessionFromOwner(sessionId: String) {
        scope.launch {
            ready.await()
            mutex.withLock { persist(state.value.jobs.map { if (it.sessionId == sessionId) it.copy(sessionClosed = true) else it }) }
            executionMutex.withLock { cleanupClosedSessionBackups() }
        }
    }

    private suspend fun cleanupClosedSessionBackups() {
        val jobs = state.value.jobs.filter { it.sessionClosed && it.isTerminal && !it.undoRequested && it.undoOfJobId == null }
        for (job in jobs) for ((index, item) in job.items.withIndex()) {
            if (item.status != QuickSortItemStatus.Succeeded || item.replacementReceipt == null || item.replacementReleased) continue
            runCatching { media.releaseQuickSortReplacementBackup(item) }.onSuccess { removed ->
                if (removed) mutex.withLock { updateItem(job.id, index) { it.copy(replacementReleased = true) } }
            }.onFailure { android.util.Log.w("QuickSortQueue", "Replacement backup retained for review", it) }
        }
    }

    private suspend fun prepareReplacement(job: QuickSortJob, index: Int, destination: TransferDestination) {
        val item = state.value.jobs.first { it.id == job.id }.items[index]
        if (item.replacementReceipt != null) return
        if (item.replacementCheckpoint != null) error("Interrupted replacement needs review; files were kept")
        val target = File(destination.path, File(item.entry.path).name)
        if (!target.exists()) return
        val existing = media.quickSortReplacementEntry(target.path, item.entry.sourceMimeType)
        val directory = File(target.parentFile, "PixelGallery-QuickSortUndo/${job.id}-$index")
        check(directory.mkdirs() || directory.isDirectory) { "Could not create replacement backup" }
        val receipt = media.moveForQuickSort(existing,
            TransferDestination(directory.path, directory.name, directory.path), "${job.id}-$index-backup",
            conflictPolicy = null) { checkpoint ->
            mutex.withLock { updateItem(job.id, index) { it.copy(replacementCheckpoint = checkpoint) } }
        }
        mutex.withLock { updateItem(job.id, index) { it.copy(replacementReceipt = receipt) } }
    }

    private suspend fun restoreFailedReplacement(jobId: String, index: Int): String? {
        val item = state.value.jobs.first { it.id == jobId }.items[index]
        val checkpoint = item.replacementCheckpoint ?: return null
        val receipt = item.replacementReceipt ?: return null
        if (item.replacementReleased || !File(item.entry.path).exists() || item.checkpoint?.stage?.let { it >= QuickMoveStage.SourceRemoved } == true) return null
        return runCatching {
            val source = checkpoint.source.copy(contentId = receipt.targetId, uri = receipt.targetUri, path = receipt.targetPath)
            val directory = File(checkpoint.source.path).parentFile ?: error("Original destination is unavailable")
            val restored = media.moveForQuickSort(source, TransferDestination(directory.path, directory.name, directory.path),
                "$jobId-$index-rollback", restoreCheckpoint = checkpoint) { restoring ->
                mutex.withLock { updateItem(jobId, index) { it.copy(replacementRestoreCheckpoint = restoring) } }
            }
            mutex.withLock { updateItem(jobId, index) { it.copy(replacementReleased = true, replacementRestoreReceipt = restored) } }
            null
        }.getOrElse { "Existing destination is retained at ${receipt.targetPath}; restoration needs review: ${it.message}" }
    }

    /** Materialize reversal only after the in-flight move has committed its receipt. */
    private fun scheduleRequestedUndos() {
        val pending = state.value.jobs.filter { it.undoRequested && it.undoJobId == null &&
            it.items.none { item -> item.status == QuickSortItemStatus.Running } }
        pending.forEach { original ->
            val id = UUID.randomUUID().toString()
            val items = quickSortUndoItems(original)
            val undo = QuickSortJob(id, original.destination, items, System.currentTimeMillis(),
                status = if (items.isEmpty()) QuickSortJobStatus.Completed else QuickSortJobStatus.Queued,
                sessionId = original.sessionId, undoOfJobId = original.id, sessionProcessId = original.sessionProcessId)
            persist(state.value.jobs.map { if (it.id == original.id) it.copy(undoJobId = id) else it } + undo)
        }
    }

    private fun conflictCount(job: QuickSortJob): Int {
        val names = mutableSetOf<String>()
        return job.items.count { item ->
            item.status == QuickSortItemStatus.Queued && item.restoreCheckpoint == null &&
                !isSameTransferDirectory(item.entry.path, job.destination.path) &&
                (File(job.destination.path, File(item.entry.path).name).exists() || !names.add(File(item.entry.path).name))
        }
    }

    suspend fun runUntilIdle() = executionMutex.withLock {
        ready.await()
        if (state.value.error != null) return@withLock
        mutex.withLock { mutableState.value = state.value.copy(serviceActive = true) }
        val transientRetries = mutableSetOf<String>()
        try {
            while (!pauseRequested && currentCoroutineContext().isActive) {
                val job = mutex.withLock {
                    scheduleRequestedUndos()
                    val next = state.value.jobs.firstOrNull { !it.isTerminal } ?: return@withLock null
                    if (next.cancelRequested) {
                        updateJob(next.id, ::cancelQuickSortJob)
                        return@withLock null
                    }
                    if (next.status == QuickSortJobStatus.NeedsPermission || next.status == QuickSortJobStatus.NeedsConflictDecision) return@withLock null
                    if (next.undoOfJobId == null && next.conflictPolicy == null) {
                        val conflicts = conflictCount(next)
                        if (conflicts > 0) {
                            updateJob(next.id) { it.copy(status = QuickSortJobStatus.NeedsConflictDecision, conflictCount = conflicts) }
                            return@withLock null
                        }
                    }
                    if (!next.permissionGranted) {
                        try {
                            val request = media.createTransferWriteRequest(next.items.filter { it.status == QuickSortItemStatus.Queued }.map { it.entry })
                            if (request != null) {
                                updateJob(next.id) { it.copy(status = QuickSortJobStatus.NeedsPermission, permissionToken = UUID.randomUUID().toString()) }
                                return@withLock null
                            }
                        } catch (error: Exception) {
                            failQueued(next.id, error.message ?: "Storage permission could not be requested")
                            return@withLock null
                        }
                    }
                    updateJob(next.id) { it.copy(status = QuickSortJobStatus.Running, permissionGranted = true) }
                    state.value.jobs.first { it.id == next.id }
                }
                if (job == null) {
                    if (!pauseRequested && state.value.needsPermission == null && state.value.needsConflictDecision == null && state.value.jobs.any { !it.isTerminal }) continue
                    break
                }
                val itemIndex = job.items.indexOfFirst { it.status == QuickSortItemStatus.Queued }
                if (itemIndex < 0) {
                    mutex.withLock { updateJob(job.id) { it.copy(status = if (it.cancelRequested) QuickSortJobStatus.Cancelled else QuickSortJobStatus.Completed) } }
                    continue
                }
                val entry = job.items[itemIndex].entry
                mutex.withLock { updateItem(job.id, itemIndex) { it.copy(status = QuickSortItemStatus.Running) } }
                var retryTransientFailure = false
                // Cancellation and FGS shutdown stop at the next file boundary, never halfway through source deletion.
                withContext(NonCancellable) {
                    try {
                        val destination = job.items[itemIndex].restoreCheckpoint?.source?.path?.let { path ->
                            val parent = File(path).parentFile ?: error("Original album is unavailable")
                            TransferDestination(parent.path, parent.name, parent.path)
                        } ?: job.destination
                        if (isSameTransferDirectory(entry.path, destination.path)) {
                            mutex.withLock { updateItem(job.id, itemIndex) { it.copy(status = QuickSortItemStatus.Skipped) } }
                        } else {
                            if (job.conflictPolicy == ConflictPolicy.REPLACE && job.items[itemIndex].restoreCheckpoint == null) {
                                prepareReplacement(job, itemIndex, destination)
                            }
                            val receipt = media.moveForQuickSort(entry, destination, "${job.id}-$itemIndex",
                                restoreCheckpoint = job.items[itemIndex].restoreCheckpoint,
                                conflictPolicy = job.conflictPolicy) { checkpoint ->
                                mutex.withLock { updateItem(job.id, itemIndex) { it.copy(checkpoint = checkpoint) } }
                            }
                            mutex.withLock { updateItem(job.id, itemIndex) { it.copy(status = QuickSortItemStatus.Succeeded, receipt = receipt) } }
                        }
                    } catch (error: QuickSortNameConflictException) {
                        val rollbackError = if (job.conflictPolicy == ConflictPolicy.SKIP) restoreFailedReplacement(job.id, itemIndex) else null
                        mutex.withLock {
                            updateItem(job.id, itemIndex) { it.copy(status = if (rollbackError != null) QuickSortItemStatus.Failed
                                else if (job.conflictPolicy == ConflictPolicy.SKIP) QuickSortItemStatus.Skipped else QuickSortItemStatus.Queued,
                                error = rollbackError) }
                            if (job.conflictPolicy != ConflictPolicy.SKIP) updateJob(job.id) {
                                it.copy(status = QuickSortJobStatus.NeedsConflictDecision, conflictCount = 1)
                            }
                        }
                    } catch (error: Exception) {
                        val retryKey = "${job.id}:$itemIndex"
                        if (retryKey !in transientRetries && isTransientQuickMoveFailure(error)) {
                            transientRetries += retryKey
                            retryTransientFailure = true
                            mutex.withLock { updateItem(job.id, itemIndex) { it.copy(status = QuickSortItemStatus.Queued, error = null) } }
                        } else {
                            val rollbackError = restoreFailedReplacement(job.id, itemIndex)
                            mutex.withLock { updateItem(job.id, itemIndex) { it.copy(status = QuickSortItemStatus.Failed,
                                error = rollbackError ?: error.message ?: "Move failed; inspect original and destination") } }
                        }
                    }
                }
                if (retryTransientFailure) {
                    runCatching { media.syncWhenMediaMutationsIdle(force = true) }
                    delay(250L)
                    continue
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val jobs = state.value.jobs.map { job ->
                        if (job.status == QuickSortJobStatus.Running) job.copy(status = when {
                            job.cancelRequested -> QuickSortJobStatus.Cancelled
                            job.items.none { it.status == QuickSortItemStatus.Queued } -> QuickSortJobStatus.Completed
                            else -> QuickSortJobStatus.Paused
                        }) else job
                    }
                    persist(jobs)
                    mutableState.value = state.value.copy(serviceActive = false)
                }
                cleanupClosedSessionBackups()
            }
        }
    }

    fun requestPause() { pauseRequested = true }
    internal fun resumeFromService() { pauseRequested = false }

    private fun failQueued(jobId: String, reason: String) = updateJob(jobId) { job ->
        job.copy(status = QuickSortJobStatus.Completed, permissionToken = null,
            items = job.items.map { if (it.status == QuickSortItemStatus.Queued) it.copy(status = QuickSortItemStatus.Failed, error = reason) else it })
    }

    private fun isTransientQuickMoveFailure(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase(java.util.Locale.ROOT)
        return "source media record is unavailable" in message ||
            "original media timestamps could not be read" in message
    }

    private fun updateItem(jobId: String, index: Int, change: (QuickSortQueueItem) -> QuickSortQueueItem) {
        val jobs = state.value.jobs.map { job ->
            if (job.id != jobId) job else {
                val items = job.items.mapIndexed { position, item -> if (position == index) change(item) else item }
                job.copy(items = items, firstSucceededAt = job.firstSucceededAt ?: System.currentTimeMillis()
                    .takeIf { items.any { it.status == QuickSortItemStatus.Succeeded } })
            }
        }
        persist(trimHistory(resolveQuickSortRetrySuccess(jobs, jobId, index)))
    }

    private fun updateJob(jobId: String, change: (QuickSortJob) -> QuickSortJob) =
        persist(state.value.jobs.map { if (it.id == jobId) change(it) else it })

    private fun trimHistory(jobs: List<QuickSortJob>): List<QuickSortJob> {
        val retained = jobs.filter { it.isTerminal }.takeLast(30).mapTo(mutableSetOf()) { it.id }
        return jobs.filter { !it.isTerminal || it.id in retained ||
            (it.undoRequested && it.undoJobId == null) || quickSortNeedsRecovery(it) }
    }

    /** Commit to disk before publishing. Call only while holding mutex. */
    private fun persist(jobs: List<QuickSortJob>) {
        val albumUses = quickSortAlbumUses(state.value.albumUses, jobs, System.currentTimeMillis())
        val output = journal.startWrite()
        try {
            // Persist only unfinished moves, current-session undo, and incomplete
            // recovery checkpoints. Finished failures have no saved task history.
            val savedJobs = quickSortJobsToSave(jobs)
            output.write(gson.toJson(QuickSortQueueJournal(jobs = savedJobs, albumUses = albumUses)).toByteArray(Charsets.UTF_8))
            journal.finishWrite(output)
        } catch (error: Exception) {
            journal.failWrite(output)
            mutableState.value = state.value.copy(error = "Could not persist queue; execution stopped: ${error.message}")
            throw error
        }
        reportFinishedFailures(state.value.jobs, jobs)
        mutableState.value = state.value.copy(jobs = trimHistory(jobs), albumUses = albumUses, loaded = true)
    }

    companion object {
        private val processSessionId = UUID.randomUUID().toString()
        const val MAX_PENDING_ITEMS = 1000
        const val MAX_PENDING_JOBS = 20
    }
}
