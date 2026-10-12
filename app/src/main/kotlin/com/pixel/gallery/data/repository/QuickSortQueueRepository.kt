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
                    val recoveredItems = job.items.map { item ->
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
                    job.copy(items = recoveredItems,
                        status = when {
                            job.cancelRequested -> QuickSortJobStatus.Cancelled
                            !unfinished -> if (job.isTerminal) job.status else QuickSortJobStatus.Completed
                            job.status == QuickSortJobStatus.NeedsPermission -> QuickSortJobStatus.NeedsPermission
                            else -> QuickSortJobStatus.Paused
                        }, permissionGranted = false)
                }
                mutex.withLock { persist(recoveredJobs) }
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

    suspend fun enqueue(entries: List<MediaEntry>, destination: TransferDestination): QuickSortEnqueueResult = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            state.value.error?.let { return@withLock QuickSortEnqueueResult.Rejected(it) }
            if (destination.documentUri != null) return@withLock QuickSortEnqueueResult.Rejected(
                "Quick sort requires a local album that can preserve original timestamps")
            if (!File(destination.path).isDirectory) return@withLock QuickSortEnqueueResult.Rejected("Destination album is unavailable")
            val eligible = entries.filterNot { isSameTransferDirectory(it.path, destination.path) }
            val accepted = selectUnreservedQuickSortEntries(eligible, state.value)
            if (accepted.isEmpty()) return@withLock QuickSortEnqueueResult.Rejected("Items are already queued or already in this album")
            if (state.value.pendingCount + accepted.size > MAX_PENDING_ITEMS || state.value.jobs.count { !it.isTerminal } >= MAX_PENDING_JOBS) {
                return@withLock QuickSortEnqueueResult.Rejected("Queue is full; wait for a batch to finish")
            }
            val id = UUID.randomUUID().toString()
            val job = QuickSortJob(id, destination, accepted.map(::QuickSortQueueItem), System.currentTimeMillis())
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

    suspend fun retry(jobId: String): QuickSortEnqueueResult {
        ready.await()
        val job = state.value.jobs.firstOrNull { it.id == jobId }
            ?: return QuickSortEnqueueResult.Rejected("Batch is no longer available")
        // Recovery with a target present requires explicit inspection, never a blind duplicate move.
        val failures = job.items.filter { it.status == QuickSortItemStatus.Failed }
        val ambiguous = failures.any { item -> item.checkpoint?.let { File(it.targetPath).exists() } == true }
        if (ambiguous) return QuickSortEnqueueResult.Rejected("An interrupted destination still exists; review both files before retrying")
        return enqueue(failures.map { it.entry }, job.destination)
    }

    /** Called by the service. A second service start cannot create a second writer. */
    suspend fun runUntilIdle() = executionMutex.withLock {
        ready.await()
        if (state.value.error != null) return@withLock
        mutex.withLock { mutableState.value = state.value.copy(serviceActive = true) }
        var changesSinceSync = 0
        var lastSync = System.currentTimeMillis()
        try {
            while (!pauseRequested && currentCoroutineContext().isActive) {
                val job = mutex.withLock {
                    val next = state.value.jobs.firstOrNull { !it.isTerminal } ?: return@withLock null
                    if (next.cancelRequested) {
                        updateJob(next.id, ::cancelQuickSortJob)
                        return@withLock null
                    }
                    if (next.status == QuickSortJobStatus.NeedsPermission) return@withLock null
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
                    if (!pauseRequested && state.value.needsPermission == null && state.value.jobs.any { !it.isTerminal }) continue
                    break
                }
                val itemIndex = job.items.indexOfFirst { it.status == QuickSortItemStatus.Queued }
                if (itemIndex < 0) {
                    mutex.withLock { updateJob(job.id) { it.copy(status = if (it.cancelRequested) QuickSortJobStatus.Cancelled else QuickSortJobStatus.Completed) } }
                    if (changesSinceSync > 0) { runCatching { media.syncWhenMediaMutationsIdle(force = true) }; changesSinceSync = 0 }
                    continue
                }
                val entry = job.items[itemIndex].entry
                mutex.withLock { updateItem(job.id, itemIndex) { it.copy(status = QuickSortItemStatus.Running) } }
                // Cancellation and FGS shutdown stop at the next file boundary, never halfway through source deletion.
                withContext(NonCancellable) {
                    try {
                        if (isSameTransferDirectory(entry.path, job.destination.path)) {
                            mutex.withLock { updateItem(job.id, itemIndex) { it.copy(status = QuickSortItemStatus.Skipped) } }
                        } else {
                            val receipt = media.moveForQuickSort(entry, job.destination, "${job.id}-$itemIndex") { checkpoint ->
                                mutex.withLock { updateItem(job.id, itemIndex) { it.copy(checkpoint = checkpoint) } }
                            }
                            mutex.withLock { updateItem(job.id, itemIndex) { it.copy(status = QuickSortItemStatus.Succeeded, receipt = receipt) } }
                            changesSinceSync++
                        }
                    } catch (error: Exception) {
                        mutex.withLock { updateItem(job.id, itemIndex) { it.copy(status = QuickSortItemStatus.Failed,
                            error = error.message ?: "Move failed; inspect original and destination") } }
                    }
                }
                if (changesSinceSync >= 20 || (changesSinceSync > 0 && System.currentTimeMillis() - lastSync >= 2_000)) {
                    runCatching { media.syncWhenMediaMutationsIdle(force = true) }
                    changesSinceSync = 0
                    lastSync = System.currentTimeMillis()
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (changesSinceSync > 0) runCatching { media.syncWhenMediaMutationsIdle(force = true) }
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
            }
        }
    }

    fun requestPause() { pauseRequested = true }
    internal fun resumeFromService() { pauseRequested = false }

    private fun failQueued(jobId: String, reason: String) = updateJob(jobId) { job ->
        job.copy(status = QuickSortJobStatus.Completed, permissionToken = null,
            items = job.items.map { if (it.status == QuickSortItemStatus.Queued) it.copy(status = QuickSortItemStatus.Failed, error = reason) else it })
    }

    private fun updateItem(jobId: String, index: Int, change: (QuickSortQueueItem) -> QuickSortQueueItem) = updateJob(jobId) { job ->
        val items = job.items.mapIndexed { position, item -> if (position == index) change(item) else item }
        job.copy(items = items, firstSucceededAt = job.firstSucceededAt ?: System.currentTimeMillis()
            .takeIf { items.any { it.status == QuickSortItemStatus.Succeeded } })
    }

    private fun updateJob(jobId: String, change: (QuickSortJob) -> QuickSortJob) =
        persist(state.value.jobs.map { if (it.id == jobId) change(it) else it })

    private fun trimHistory(jobs: List<QuickSortJob>): List<QuickSortJob> {
        val retained = jobs.filter { it.isTerminal }.takeLast(30).mapTo(mutableSetOf()) { it.id }
        return jobs.filter { !it.isTerminal || it.id in retained || it.items.any { item -> item.status == QuickSortItemStatus.Failed } }
    }

    /** Commit to disk before publishing. Call only while holding mutex. */
    private fun persist(jobs: List<QuickSortJob>) {
        val albumUses = quickSortAlbumUses(state.value.albumUses, jobs, System.currentTimeMillis())
        val output = journal.startWrite()
        try {
            output.write(gson.toJson(QuickSortQueueJournal(jobs = jobs, albumUses = albumUses)).toByteArray(Charsets.UTF_8))
            journal.finishWrite(output)
        } catch (error: Exception) {
            journal.failWrite(output)
            mutableState.value = state.value.copy(error = "Could not persist queue; execution stopped: ${error.message}")
            throw error
        }
        mutableState.value = state.value.copy(jobs = jobs, albumUses = albumUses, loaded = true)
    }

    companion object {
        const val MAX_PENDING_ITEMS = 1000
        const val MAX_PENDING_JOBS = 20
    }
}
