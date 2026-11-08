package com.pixel.gallery.integration

import android.app.Instrumentation
import android.os.Debug
import com.google.gson.Gson
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Algorithm heap/GC measurement on ART; no Activity/bitmap or phone-flicker claim. */
internal class CollectionHeapChecks(private val runner: Instrumentation) {
    private var retained: Any? = null
    private val runtime = Runtime.getRuntime()
    private fun used() = runtime.totalMemory() - runtime.freeMemory()
    private fun settle() { repeat(3) { runtime.gc(); Thread.sleep(100) } }

    fun exercise(): String {
        val media = (1L..131_014L).map { id -> MediaEntry(
            id, "content://cover-heap/$id", "/Album${id % 179}/$id.jpg",
            "image/jpeg", 100, 100, 0, 100, 1, 1,
        ) }
        val albums = (0 until 179).map { Album("Album$it", "/Album$it", "auto", 1) }
        val groups = AlbumGrouping((0 until 8).map { g ->
            AlbumGroup("g$g", "Group$g", albums.filterIndexed { i, _ -> i % 8 == g }.map { it.path })
        })
        val requested = setOf(media[0].uri, media[1].uri)
        val records = mutableListOf<Map<String, Any>>()
        fun measure(mode: String, windows: Int) {
            retained = null
            settle()
            val before = used()
            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            val gc = Debug.getRuntimeStat("art.gc.gc-count").toLong()
            val peak = AtomicLong(before)
            val running = AtomicBoolean(true)
            val sampler = Thread({
                while (running.get()) { peak.accumulateAndGet(used(), ::maxOf); Thread.sleep(1) }
            }, "cover-heap-sampler").apply { start() }
            try {
                retained = List(windows) {
                    if (mode == "old") {
                        val visible = media.filter { !it.isTrashed }
                        val byAlbum = visible.groupBy { it.path.substringBeforeLast('/') }
                        val candidates = groups.groups.associate { group ->
                            group.id to group.paths.flatMap { byAlbum[it].orEmpty() }
                        }
                        // Previous cover index was temporary; keep only its selected result.
                        val byUri = visible.filterNot { it.isTrashed }.associateBy { it.uri }
                        Triple(visible, candidates, requested.mapNotNull(byUri::get))
                    } else {
                        Triple(emptyList<MediaEntry>(),
                            groupCoverCandidates(media, albums, groups, emptyList(), emptySet()),
                            findCoverMedia(media, requested).values.toList())
                    }
                }
                peak.accumulateAndGet(used(), ::maxOf)
            } finally {
                running.set(false)
                sampler.join()
            }
            val allocatedDelta = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated
            val gcDelta = Debug.getRuntimeStat("art.gc.gc-count").toLong() - gc
            val end = used()
            val gcBeforeSettle = Debug.getRuntimeStat("art.gc.gc-count").toLong()
            settle()
            val afterGc = used()
            val forcedGcCount = Debug.getRuntimeStat("art.gc.gc-count").toLong() - gcBeforeSettle
            check(forcedGcCount > 0) { "GC did not run; retained heap measurement is invalid" }
            check((retained as List<*>).size == windows)
            records += mapOf("mode" to mode, "windows" to windows, "before" to before,
                "sampledPeak" to peak.get(), "beforeForcedGc" to end, "afterForcedGc" to afterGc,
                "retainedDelta" to afterGc - before, "allocated" to allocatedDelta,
                "naturalGcCount" to gcDelta, "forcedGcCount" to forcedGcCount)
        }
        try {
            listOf(4, 12).forEach { windows ->
                repeat(3) { measure("old", windows); measure("new", windows) }
            }
            val result = mapOf("recordCount" to media.size, "heapMax" to runtime.maxMemory(),
                "scope" to "ART algorithms only; shared synthetic media; no Activity or image renderer",
                "records" to records)
            File(runner.targetContext.cacheDir, "collection-heap.json").writeText(Gson().toJson(result))
            return "12 ART heap trials saved: old/new algorithms, 4/12 retained copies, 131014 records; no whole-app or phone low-mem assertion."
        } finally { retained = null }
    }
}
