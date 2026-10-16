package com.pixel.gallery.integration

import android.app.Instrumentation
import android.os.SystemClock
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.ui.viewmodel.AlbumSortOrder
import com.pixel.gallery.ui.viewmodel.MediaBrowseIndex
import com.pixel.gallery.ui.viewmodel.PhotoSortOrder
import java.io.File
import java.util.Locale

/** Approximate retained Java heap deltas after GC; excludes source records and peak allocations. */
internal class MediaBrowseMemoryChecks(private val instrumentation: Instrumentation) {
    @Volatile private var source = emptyList<MediaEntry>()
    @Volatile private var retained: MediaBrowseIndex? = null

    fun exercise(): String {
        // Initialize classes, date formatting and album ordering before the baseline.
        source = fixtures(1000)
        buildIndex()
        visitAlbums(400)
        retained = null
        source = fixtures(150_000)
        val report = mutableListOf(
            "150000 synthetic records, 400 albums, daily grouping; retained Java heap after GC.",
            "Source MediaEntry records already loaded in baseline; no thumbnails or rendering.",
        )
        repeat(3) { run ->
            retained = null
            val baseline = usedHeap()
            buildIndex()
            val library = usedHeap()
            visitAlbums(10)
            val tenAlbums = usedHeap()
            visitAlbums(400)
            val allAlbums = usedHeap()
            check(source.size == 150_000)
            check(retained!!.library[MediaTypeFilter.ALL].media.photos.size == source.size)
            report += "Run ${run + 1}: baseline ${mib(baseline)} MiB; library cache +${mib(library - baseline)} MiB; " +
                "with 10 visited albums +${mib(tenAlbums - baseline)} MiB; with all 400 visited +${mib(allAlbums - baseline)} MiB."
        }
        retained = null
        source = emptyList()
        report += "Heap deltas are approximate; not process RSS, peak rebuild memory, or an old-vs-new net difference."
        return report.joinToString("\n").also {
            File(instrumentation.targetContext.cacheDir, "media-browse-memory.txt").writeText(it)
        }
    }

    private fun buildIndex() {
        retained = MediaBrowseIndex.build(source, 3, PhotoSortOrder.DATE_DESC).also { index ->
            for (type in MediaTypeFilter.entries) index.library[type].albums(AlbumSortOrder.NAME_ASC)
        }
    }

    private fun visitAlbums(count: Int) {
        for (album in 0 until count) retained!!.album("/storage/emulated/0/DCIM/BenchAlbum$album")
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return List(5) {
            runtime.gc()
            System.runFinalization()
            runtime.gc()
            SystemClock.sleep(100)
            runtime.totalMemory() - runtime.freeMemory()
        }.min()
    }

    private fun fixtures(count: Int) = List(count) { index ->
        val video = index % 3 == 0
        val timestamp = 1_710_000_000_000L - (index / 60) * 86_400_000L - index % 60 * 1000L
        MediaEntry(index.toLong(), "content://benchmark/$index",
            "/storage/emulated/0/DCIM/BenchAlbum${index % 400}/file-$index.${if (video) "mp4" else "jpg"}",
            if (video) "video/mp4" else "image/jpeg", 100, 100, 0, 1000, 1, timestamp,
            sourceDateTakenMillis = timestamp, bestTimestamp = timestamp)
    }

    private fun mib(bytes: Long) = String.format(Locale.US, "%.2f", bytes / 1_048_576.0)
}
