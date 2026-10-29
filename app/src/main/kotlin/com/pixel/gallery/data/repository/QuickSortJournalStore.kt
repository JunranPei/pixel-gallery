package com.pixel.gallery.data.repository

import com.google.gson.Gson
import com.pixel.gallery.model.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/** Snapshot plus fsynced item deltas. Call under the queue mutex on an IO dispatcher. */
internal class QuickSortJournalStore(
    private val deltas: File,
    private val readSnapshot: () -> QuickSortQueueJournal,
    private val writeSnapshot: (QuickSortQueueJournal) -> Unit,
) {
    private val gson = Gson()
    private var revision = 0L
    val needsCompaction: Boolean get() = deltas.length() >= 8L * 1024 * 1024

    fun load(): QuickSortQueueJournal {
        var snapshot = readSnapshot()
        require(snapshot.version == 1) { "Unsupported queue journal version" }
        revision = snapshot.revision
        if (!deltas.exists() || deltas.length() == 0L) return snapshot
        val completeTail = RandomAccessFile(deltas, "r").use { file ->
            file.seek(file.length() - 1)
            file.read() == '\n'.code
        }
        var validBytes = 0L
        deltas.bufferedReader(Charsets.UTF_8).use { input ->
            var line = input.readLine()
            while (line != null) {
                val following = input.readLine()
                // No subsequent mutation was authorized by an incomplete final write.
                if (following == null && !completeTail) break
                val separator = line.lastIndexOf('\t')
                check(separator > 0) { "Invalid queue delta" }
                val json = line.substring(0, separator)
                val payload = json.toByteArray(Charsets.UTF_8)
                check(checksum(payload) == line.substring(separator + 1)) { "Queue delta checksum failed" }
                val delta = gson.fromJson(json, ItemDelta::class.java)
                if (delta.revision > revision) {
                    check(delta.revision == revision + 1) { "Queue delta revision gap" }
                    val index = snapshot.jobs.indexOfFirst { it.id == delta.jobId }
                    check(index >= 0) { "Queue delta has no matching batch" }
                    val job = snapshot.jobs[index]
                    check(delta.itemIndex in job.items.indices) { "Queue delta item is out of range" }
                    val items = job.items.toMutableList().apply { set(delta.itemIndex, delta.item) }
                    val jobs = snapshot.jobs.toMutableList().apply {
                        set(index, job.copy(items = items, firstSucceededAt = delta.firstSucceededAt))
                    }
                    revision = delta.revision
                    snapshot = snapshot.copy(jobs = jobs, albumUses = delta.albumUses ?: snapshot.albumUses,
                        revision = revision)
                }
                validBytes += line.toByteArray(Charsets.UTF_8).size + 1
                line = following
            }
        }
        if (!completeTail) RandomAccessFile(deltas, "rw").use { file ->
            file.setLength(validBytes)
            file.fd.sync()
        }
        return snapshot
    }

    fun saveSnapshot(snapshot: QuickSortQueueJournal) {
        val next = revision + 1
        // Old deltas are harmless after this atomic snapshot, even if truncation is interrupted.
        writeSnapshot(snapshot.copy(revision = next))
        revision = next
        if (deltas.exists()) RandomAccessFile(deltas, "rw").use { file ->
            file.setLength(0)
            file.fd.sync()
        }
    }

    fun saveItem(job: QuickSortJob, itemIndex: Int, albumUses: List<QuickSortAlbumUse>?) {
        val next = revision + 1
        val delta = ItemDelta(next, job.id, itemIndex, job.items[itemIndex], job.firstSucceededAt, albumUses)
        val payload = gson.toJson(delta).toByteArray(Charsets.UTF_8)
        val suffix = "\t${checksum(payload)}\n".toByteArray(Charsets.UTF_8)
        val before = deltas.length()
        try {
            FileOutputStream(deltas, true).use { output ->
                output.write(payload)
                output.write(suffix)
                output.fd.sync()
            }
            revision = next
        } catch (failure: IOException) {
            runCatching { RandomAccessFile(deltas, "rw").use { it.setLength(before); it.fd.sync() } }
                .onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }

    private fun checksum(bytes: ByteArray): String = CRC32().apply { update(bytes) }.value.toString(16)
    private data class ItemDelta(
        val revision: Long,
        val jobId: String,
        val itemIndex: Int,
        val item: QuickSortQueueItem,
        val firstSucceededAt: Long?,
        val albumUses: List<QuickSortAlbumUse>?,
    )
}
