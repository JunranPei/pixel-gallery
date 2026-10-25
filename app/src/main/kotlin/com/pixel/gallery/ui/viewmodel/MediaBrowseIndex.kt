package com.pixel.gallery.ui.viewmodel

import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.Album
import com.pixel.gallery.model.AlbumSortOrder
import com.pixel.gallery.model.MediaTypeFilter
import com.pixel.gallery.model.chronologicalTimestamp
import com.pixel.gallery.model.isInsideMediaFolder
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import com.pixel.gallery.utils.MimeTypes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

internal data class MediaBrowseResult(
    val photos: List<MediaEntry> = emptyList(),
    val gridItems: List<GridItem> = emptyList(),
    val positionLabels: List<String?> = emptyList(),
)

/** One immutable library generation. Category changes never sort or format dates. */
internal class MediaBrowseIndex private constructor(
    val library: MediaBrowseScope,
    private val records: List<BrowseRecord>,
    private val byAlbum: Map<String, List<BrowseRecord>>,
) {
    private val albumCache = ConcurrentHashMap<String, MediaBrowseScope>()
    private val hiddenFolderCache = ConcurrentHashMap<String, MediaBrowseScope>()

    /** Saved hidden roots can contain descendant directories as well as files. */
    fun hiddenFolder(path: String, checkCancelled: () -> Unit = {}): MediaBrowseScope =
        hiddenFolderCache.computeIfAbsent(path) {
            val entries = records.filterIndexed { index, row ->
                if (index % 512 == 0) checkCancelled()
                row.entry.path.isInsideMediaFolder(path)
            }
            MediaBrowseScope.build(entries, checkCancelled)
        }

    fun album(name: String, checkCancelled: () -> Unit = {}): MediaBrowseScope =
        albumCache.computeIfAbsent(name) {
            val entries = if ('/' in name || '\\' in name) {
                byAlbum[name.replace('\\', '/')].orEmpty()
            } else {
                // Legacy callers pass a folder name instead of its full path.
                records.filterIndexed { index, row ->
                    if (index % 512 == 0) checkCancelled()
                    row.albumName == name
                }
            }
            MediaBrowseScope.build(entries, checkCancelled)
        }

    companion object {
        val Empty = build(emptyList(), 3, PhotoSortOrder.DATE_DESC)

        /** Input already has the effective library sort order. */
        fun build(
            orderedEntries: List<MediaEntry>,
            columns: Int,
            sort: PhotoSortOrder,
            timeZone: TimeZone = TimeZone.getDefault(),
            checkCancelled: () -> Unit = {},
        ): MediaBrowseIndex {
            val dates = MediaDateBuckets(columns, sort, timeZone)
            val records = ArrayList<BrowseRecord>(orderedEntries.size)
            val albums = LinkedHashMap<String, AlbumRecords>()
            orderedEntries.forEachIndexed { index, entry ->
                if (index % 512 == 0) checkCancelled()
                val timestamp = entry.chronologicalTimestamp()
                val bucket = dates.at(timestamp)
                val path = if ('\\' in entry.path) entry.path.replace('\\', '/') else entry.path
                val slash = path.lastIndexOf('/')
                val parent = when {
                    slash == 0 -> "/"
                    slash > 0 -> path.substring(0, slash)
                    else -> ""
                }
                val album = albums.getOrPut(parent) {
                    AlbumRecords(parent, parent.substringAfterLast('/').ifBlank { "Unknown" })
                }
                val row = BrowseRecord(
                    entry = entry,
                    photo = GridItem.Photo(entry),
                    albumPath = album.path,
                    albumName = album.name,
                    timestamp = timestamp,
                    bucket = bucket,
                    type = when {
                        MimeTypes.isImage(entry.sourceMimeType) -> MediaTypeFilter.IMAGES
                        MimeTypes.isVideo(entry.sourceMimeType) -> MediaTypeFilter.VIDEOS
                        else -> MediaTypeFilter.ALL
                    },
                )
                records += row
                album.records.add(row)
            }
            return MediaBrowseIndex(
                MediaBrowseScope.build(records, checkCancelled), records, albums.mapValues { it.value.records },
            )
        }
    }
}

private class AlbumRecords(val path: String, val name: String) {
    val records = ArrayList<BrowseRecord>()
}

internal class MediaBrowseScope private constructor(private val views: List<MediaBrowseView>) {
    operator fun get(type: MediaTypeFilter): MediaBrowseView = views[type.ordinal]

    companion object {
        internal fun build(records: List<BrowseRecord>, checkCancelled: () -> Unit): MediaBrowseScope {
            val builders = List(MediaTypeFilter.entries.size) { BrowseViewBuilder() }
            records.forEachIndexed { index, row ->
                if (index % 512 == 0) checkCancelled()
                builders[MediaTypeFilter.ALL.ordinal].add(row)
                if (row.type != MediaTypeFilter.ALL) builders[row.type.ordinal].add(row)
            }
            return MediaBrowseScope(builders.map { it.finish() })
        }
    }
}

internal class MediaBrowseView(
    val media: MediaBrowseResult,
    private val albumRows: List<Album>,
) {
    private val albumOrders = AlbumSortOrder.entries.associateWith { sort ->
        lazy { sortMediaAlbums(albumRows, sort) }
    }

    fun albums(sort: AlbumSortOrder): List<Album> = albumOrders.getValue(sort).value

    fun search(
        query: String,
        checkCancelled: () -> Unit = {},
        matches: (MediaEntry, String) -> Boolean,
    ): MediaBrowseResult {
        val normalized = query.trim()
        if (normalized.isEmpty()) return media
        val photos = ArrayList<MediaEntry>()
        val items = ArrayList<GridItem>()
        val labels = ArrayList<String?>()
        var header: GridItem.Header? = null
        var dateLabel: String? = null
        media.gridItems.forEachIndexed { index, item ->
            if (index % 512 == 0) checkCancelled()
            when (item) {
                is GridItem.Header -> { header = item; dateLabel = item.title }
                is GridItem.Photo -> if (matches(item.entry, normalized)) {
                    header?.let { items += it; labels += it.title }
                    header = null
                    items += item
                    photos += item.entry
                    labels += dateLabel
                }
            }
        }
        return MediaBrowseResult(photos, items, labels)
    }
}

internal data class DateBucket(val key: Long, val title: String)

internal data class BrowseRecord(
    val entry: MediaEntry,
    val photo: GridItem.Photo,
    val albumPath: String,
    val albumName: String,
    val timestamp: Long,
    val bucket: DateBucket?,
    val type: MediaTypeFilter,
)

private class BrowseViewBuilder {
    private val photos = ArrayList<MediaEntry>()
    private val items = ArrayList<GridItem>()
    private val labels = ArrayList<String?>()
    private val albums = LinkedHashMap<String, AlbumAccumulator>()
    private var lastDate: Long? = null

    fun add(row: BrowseRecord) {
        row.bucket?.let { date ->
            if (lastDate != date.key) {
                items += GridItem.Header(date.title, row.timestamp)
                labels += date.title
                lastDate = date.key
            }
        }
        photos += row.entry
        items += row.photo
        labels += row.bucket?.title
        albums.getOrPut(row.albumPath) {
            AlbumAccumulator(row.albumName, row.albumPath, row.entry.uri)
        }.add(row.timestamp)
    }

    fun finish() = MediaBrowseView(MediaBrowseResult(photos, items, labels), albums.values.map { it.finish() })
}

private class AlbumAccumulator(private val name: String, private val path: String, private val cover: String) {
    private var count = 0
    private var modified = Long.MIN_VALUE
    fun add(timestamp: Long) { count++; modified = maxOf(modified, timestamp) }
    fun finish() = Album(name, path, cover, count, modified)
}

/** Calendar is reused; expensive title formatting happens once per distinct day/month. */
private class MediaDateBuckets(columns: Int, sort: PhotoSortOrder, timeZone: TimeZone) {
    private val enabled = sort == PhotoSortOrder.DATE_ASC || sort == PhotoSortOrder.DATE_DESC
    private val monthly = columns >= 6
    private val calendar = Calendar.getInstance(timeZone, Locale.US)
    private val formatter = SimpleDateFormat(if (monthly) "MMMM yyyy" else "MMMM d, yyyy", Locale.US)
        .apply { this.timeZone = timeZone }
    private val buckets = HashMap<Long, DateBucket>()

    fun at(timestamp: Long): DateBucket? {
        if (!enabled) return null
        calendar.timeInMillis = timestamp
        val key = calendar.get(Calendar.ERA).toLong() * 10_000_000L +
            calendar.get(Calendar.YEAR).toLong() * 512L + calendar.get(Calendar.MONTH) * 32L +
            (if (monthly) 0 else calendar.get(Calendar.DAY_OF_MONTH))
        return buckets.getOrPut(key) { DateBucket(key, formatter.format(timestamp)) }
    }
}

internal fun groupOrderedMedia(
    orderedEntries: List<MediaEntry>,
    columns: Int,
    sort: PhotoSortOrder,
    timeZone: TimeZone = TimeZone.getDefault(),
    timestampOf: (MediaEntry) -> Long = MediaEntry::chronologicalTimestamp,
): List<GridItem> {
    val dates = MediaDateBuckets(columns, sort, timeZone)
    val items = ArrayList<GridItem>(orderedEntries.size)
    var lastDate: Long? = null
    orderedEntries.forEach { entry ->
        val timestamp = timestampOf(entry)
        if (timestamp <= 0L) {
            if (lastDate != Long.MIN_VALUE) {
                items += GridItem.Header("Unknown date", 0L)
                lastDate = Long.MIN_VALUE
            }
        } else {
            dates.at(timestamp)?.let { date ->
                if (lastDate != date.key) {
                    items += GridItem.Header(date.title, timestamp)
                    lastDate = date.key
                }
            }
        }
        items += GridItem.Photo(entry)
    }
    return items
}
