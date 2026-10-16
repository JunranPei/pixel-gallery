package com.pixel.gallery.integration

import android.app.Instrumentation
import android.os.SystemClock
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.ui.viewmodel.*
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Calculation-only diagnostics, not a device-independent latency guarantee or a frame benchmark. */
internal class MediaBrowsePerformanceChecks(private val instrumentation: Instrumentation) {
    @Volatile private var sink = 0

    fun exercise(): String {
        val count = 150_000
        val entries = List(count) { index ->
            val video = index % 3 == 0
            val timestamp = 1_710_000_000_000L - (index / 60) * 86_400_000L - index % 60 * 1000L
            MediaEntry(index.toLong(), "content://benchmark/$index",
                "/storage/emulated/0/DCIM/BenchAlbum${index % 400}/file-$index.${if (video) "mp4" else "jpg"}",
                if (video) "video/mp4" else "image/jpeg", 100, 100, 0, 1000, 1, timestamp,
                sourceDateTakenMillis = timestamp, bestTimestamp = timestamp)
        }
        fun legacy(type: MediaTypeFilter): List<GridItem> {
            val photos = if (type == MediaTypeFilter.ALL) entries else entries.filter(type::matches)
            val albums = buildMediaAlbums(photos, AlbumSortOrder.NAME_ASC)
            val sorted = sortMediaEntries(photos, PhotoSortOrder.DATE_DESC)
            val format = SimpleDateFormat("MMMM d, yyyy", Locale.US)
            var previous = ""
            val grid = ArrayList<GridItem>()
            sorted.forEach { entry ->
                val title = format.format(entry.chronologicalTimestamp())
                if (previous != title) { grid += GridItem.Header(title, entry.chronologicalTimestamp()); previous = title }
                grid += GridItem.Photo(entry)
            }
            sink = grid.size + albums.size
            return grid
        }
        fun freshIndex(): MediaBrowseIndex = MediaBrowseIndex.build(entries, 3, PhotoSortOrder.DATE_DESC).also {
            for (type in MediaTypeFilter.entries) sink += it.library[type].albums(AlbumSortOrder.NAME_ASC).size
        }
        // Warm up both algorithms before collecting medians; fixture construction is excluded.
        repeat(2) { legacy(MediaTypeFilter.ALL); freshIndex() }
        val report = mutableListOf("150000 synthetic records, 400 albums; in-memory calculations only.")
        val firstBuild = medianMillis(5) { freshIndex() }
        report += "New index build (all 3 types and album metadata): ${fmt(firstBuild)} ms median (5 runs)."
        val index = freshIndex()
        for (type in MediaTypeFilter.entries) {
            val expected = legacy(type)
            check(expected == index.library[type].media.gridItems) { "Grid mismatch for $type" }
            val baseline = medianMillis(5) { legacy(type) }
            val cached = medianMillis(5) {
                repeat(1000) {
                    val view = index.library[type]
                    sink = view.media.photos.size + view.media.gridItems.size + view.albums(AlbumSortOrder.NAME_ASC).size
                }
            } / 1000
            report += "$type: previous full recompute ${fmt(baseline)} ms; cached selection ${fmt(cached)} ms per lookup."
        }
        val albumName = "/storage/emulated/0/DCIM/BenchAlbum17"
        val start = SystemClock.elapsedRealtimeNanos()
        val album = index.album(albumName)
        val firstAlbum = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        check(album[MediaTypeFilter.ALL].media.photos.size == 375)
        val hotAlbum = medianMillis(5) { repeat(1000) { sink = index.album(albumName)[MediaTypeFilter.IMAGES].media.photos.size } } / 1000
        report += "375-item album: first preparation ${fmt(firstAlbum)} ms; cached selection ${fmt(hotAlbum)} ms per lookup."
        report += "Debug/instrumentation environment; excludes MediaStore/Room loading, thumbnail decoding and Compose rendering."
        return report.joinToString("\n").also { text ->
            File(instrumentation.targetContext.cacheDir, "media-browse-performance.txt").writeText(text)
        }
    }

    private fun medianMillis(repeats: Int, action: () -> Unit): Double = List(repeats) {
        val start = SystemClock.elapsedRealtimeNanos()
        action()
        (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }.sorted()[repeats / 2]

    private fun fmt(value: Double) = String.format(Locale.US, "%.4f", value)
}
