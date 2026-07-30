package com.pixel.gallery.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Low-overhead, per-entry diagnostics used by the power comparison build. */
internal object ViewerLoadMetrics {
    private const val tag = "ViewerLoadMetrics"
    const val isEnabled: Boolean = true

    private val sessions = ConcurrentHashMap<String, Session>()
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val activeEntry = AtomicReference<Entry?>()
    private val frameStats = ConcurrentHashMap<Long, FrameStats>()
    private val activeWorks = ConcurrentHashMap<Long, WorkToken>()
    private val nextEntryId = AtomicLong()
    private val nextSessionId = AtomicLong()
    private val nextPreviewId = AtomicLong()
    private val nextWorkId = AtomicLong()

    data class PreviewToken(
        val id: Long,
        val imageKey: String,
        val startedAtNanos: Long,
        val entryId: Long,
    )

    data class WorkToken(
        val id: Long,
        val type: String,
        val imageKey: String,
        val startedAtNanos: Long,
        val entryId: Long,
    )

    fun entryRequested(
        context: Context,
        contentId: Long,
        source: String,
        sourceItems: Int,
        detail: String = "",
    ): Long {
        if (!isEnabled) return 0L
        val entry = Entry(
            id = nextEntryId.incrementAndGet(),
            requestedAtNanos = SystemClock.elapsedRealtimeNanos(),
            contentId = contentId,
            source = source,
        )
        entries[entry.id] = entry
        activeEntry.set(entry)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginAsyncSection("ViewerEntry:$contentId", entry.id.toInt())
        }
        Log.i(
            tag,
            "ENTRY_REQUEST entry=${entry.id} contentId=$contentId source=$source items=$sourceItems " +
                "thread=${threadLabel()} $detail ${snapshotDetail()}"
        )
        powerSampleForEntry(context, entry.id, "click")
        return entry.id
    }

    fun ensureEntry(
        context: Context,
        contentId: Long,
        source: String,
        sourceItems: Int,
    ): Long {
        val existing = activeEntry.get()
        if (existing != null && existing.contentId == contentId) return existing.id
        return entryRequested(context, contentId, source, sourceItems, "synthetic=true")
    }

    fun currentEntryId(): Long = activeEntry.get()?.id ?: 0L

    fun event(
        name: String,
        detail: String = "",
        imageKey: String? = null,
        entryId: Long = currentEntryId(),
    ) {
        if (!isEnabled) return
        Log.i(
            tag,
            "EVENT entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms name=$name " +
                "key=${imageKey ?: "none"} thread=${threadLabel()} $detail"
        )
    }

    fun checkpoint(context: Context, entryId: Long, label: String) {
        if (!isEnabled) return
        Log.i(
            tag,
            "CHECKPOINT entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms at=$label " +
                snapshotDetail()
        )
        powerSampleForEntry(context, entryId, label)
    }

    fun workStarted(type: String, imageKey: String, detail: String = ""): WorkToken {
        val token = WorkToken(
            id = nextWorkId.incrementAndGet(),
            type = type,
            imageKey = imageKey,
            startedAtNanos = SystemClock.elapsedRealtimeNanos(),
            entryId = currentEntryId(),
        )
        activeWorks[token.id] = token
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginAsyncSection("Viewer:${token.type}", token.id.toInt())
        }
        Log.i(
            tag,
            "WORK_START entry=${token.entryId} sinceClick=${sinceEntryMs(token.entryId)}ms " +
                "work=${token.id} type=$type key=$imageKey thread=${threadLabel()} $detail"
        )
        return token
    }

    fun workReady(token: WorkToken, source: String = "", detail: String = "") {
        finishWork(token, "READY", source, detail)
    }

    fun workFailed(token: WorkToken, error: String) {
        finishWork(token, "FAILED", "", "error=$error")
    }

    fun workCleared(token: WorkToken, reason: String) {
        finishWork(token, "CLEAR", "", "reason=$reason")
    }

    private fun finishWork(
        token: WorkToken,
        outcome: String,
        source: String,
        detail: String,
    ) {
        if (!activeWorks.remove(token.id, token)) {
            Log.i(
                tag,
                "WORK_DUPLICATE_FINISH entry=${token.entryId} work=${token.id} type=${token.type} " +
                    "duration=${elapsedMs(token.startedAtNanos)}ms outcome=$outcome source=$source detail=$detail"
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection("Viewer:${token.type}", token.id.toInt())
        }
        Log.i(
            tag,
            "WORK_$outcome entry=${token.entryId} sinceClick=${sinceEntryMs(token.entryId)}ms " +
                "work=${token.id} type=${token.type} key=${token.imageKey} " +
                "duration=${elapsedMs(token.startedAtNanos)}ms source=$source thread=${threadLabel()} $detail"
        )
    }

    fun attachFrameMetrics(activity: Activity, entryId: Long): Window.OnFrameMetricsAvailableListener? {
        if (!isEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.N || entryId == 0L) return null
        val stats = FrameStats()
        frameStats[entryId] = stats
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            val totalNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (totalNs <= 0L) return@OnFrameMetricsAvailableListener
            val totalMs = totalNs / 1_000_000L
            stats.frames.incrementAndGet()
            stats.totalNs.addAndGet(totalNs)
            stats.maxNs.updateAndGet { old -> maxOf(old, totalNs) }
            if (totalMs >= 24L) {
                stats.slowFrames.incrementAndGet()
                Log.i(
                    tag,
                    "FRAME_SLOW entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms total=${totalMs}ms " +
                        "layout=${metricMs(metrics, FrameMetrics.LAYOUT_MEASURE_DURATION)}ms " +
                        "draw=${metricMs(metrics, FrameMetrics.DRAW_DURATION)}ms " +
                        "sync=${metricMs(metrics, FrameMetrics.SYNC_DURATION)}ms " +
                        "command=${metricMs(metrics, FrameMetrics.COMMAND_ISSUE_DURATION)}ms " +
                        "swap=${metricMs(metrics, FrameMetrics.SWAP_BUFFERS_DURATION)}ms dropped=$dropped"
                )
            }
        }
        activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        event("FRAME_MONITOR_ATTACH", entryId = entryId)
        return listener
    }

    fun detachFrameMetrics(
        activity: Activity,
        entryId: Long,
        listener: Window.OnFrameMetricsAvailableListener?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && listener != null) {
            activity.window.removeOnFrameMetricsAvailableListener(listener)
        }
        val stats = frameStats.remove(entryId)
        if (stats != null) {
            val frames = stats.frames.get()
            val averageMs = if (frames == 0) 0.0 else stats.totalNs.get().toDouble() / frames / 1_000_000.0
            event(
                "FRAME_SUMMARY",
                detail = "frames=$frames slow=${stats.slowFrames.get()} " +
                    "averageMs=${String.format(Locale.US, "%.2f", averageMs)} " +
                    "maxMs=${stats.maxNs.get() / 1_000_000L}",
                entryId = entryId,
            )
        }
    }

    fun entryEnded(context: Context, entryId: Long, reason: String) {
        checkpoint(context, entryId, "entry-end:$reason")
        val entry = entries.remove(entryId)
        if (entry != null) {
            val remaining = activeWorks.values.filter { it.entryId == entryId }
            if (remaining.isNotEmpty()) {
                Log.i(
                    tag,
                    "WORK_STILL_ACTIVE entry=$entryId count=${remaining.size} " +
                        "items=${remaining.joinToString(limit = 20) { "${it.id}:${it.type}:${it.imageKey}" }}"
                )
            }
            activeEntry.compareAndSet(entry, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.endAsyncSection("ViewerEntry:${entry.contentId}", entry.id.toInt())
            }
            Log.i(
                tag,
                "ENTRY_END entry=$entryId total=${elapsedMs(entry.requestedAtNanos)}ms " +
                    "contentId=${entry.contentId} source=${entry.source} reason=$reason ${snapshotDetail()}"
            )
        }
    }

    fun begin(context: Context, imageKey: String, detail: String = ""): Long {
        if (!isEnabled) return 0L
        val id = nextSessionId.incrementAndGet()
        val memory = memorySnapshot()
        val entryId = currentEntryId()
        sessions[imageKey] = Session(id, entryId, SystemClock.elapsedRealtimeNanos(), memory)
        Log.i(
            tag,
            "BEGIN entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms session=$id key=$imageKey " +
                "$detail javaHeap=${memory.javaUsed} nativeHeap=${memory.nativeUsed}"
        )
        powerSample(context, imageKey, id, "0ms")
        return id
    }

    fun currentSessionId(imageKey: String): Long = sessions[imageKey]?.id ?: 0L

    fun powerSample(context: Context, imageKey: String, sessionId: Long, label: String) {
        if (sessions[imageKey]?.id != sessionId) return
        val sample = readPower(context)
        Log.i(
            tag,
            "POWER session=$sessionId key=$imageKey at=$label currentUa=${sample.currentUa ?: "unsupported"} " +
                "voltageMv=${sample.voltageMv ?: "unsupported"} estimatedMw=${sample.estimatedMw ?: "unsupported"}"
        )
    }

    fun previewStarted(
        imageKey: String,
        activeAtStart: Boolean,
        viewWidth: Int,
        viewHeight: Int,
        modelType: String,
        detail: String,
    ): PreviewToken {
        val entryId = currentEntryId()
        val token = PreviewToken(
            id = nextPreviewId.incrementAndGet(),
            imageKey = imageKey,
            startedAtNanos = SystemClock.elapsedRealtimeNanos(),
            entryId = entryId,
        )
        Log.i(
            tag,
            "PREVIEW_START entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms request=${token.id} " +
                "key=$imageKey active=$activeAtStart view=${viewWidth}x$viewHeight model=$modelType " +
                "thread=${threadLabel()} $detail"
        )
        return token
    }

    fun previewReady(token: PreviewToken, source: String, detail: String) {
        sessions[token.imageKey]?.let {
            it.previewMs.compareAndSet(-1L, elapsedMs(it.startedAtNanos))
            it.previewSource = source
        }
        Log.i(
            tag,
            "PREVIEW_READY entry=${token.entryId} sinceClick=${sinceEntryMs(token.entryId)}ms " +
                "request=${token.id} key=${token.imageKey} source=$source " +
                "duration=${elapsedMs(token.startedAtNanos)}ms thread=${threadLabel()} $detail"
        )
    }

    fun previewFailed(token: PreviewToken, error: String) {
        Log.i(
            tag,
            "PREVIEW_FAILED entry=${token.entryId} sinceClick=${sinceEntryMs(token.entryId)}ms " +
                "request=${token.id} key=${token.imageKey} duration=${elapsedMs(token.startedAtNanos)}ms " +
                "thread=${threadLabel()} error=$error"
        )
    }

    fun previewCleared(token: PreviewToken, reason: String) {
        Log.i(
            tag,
            "PREVIEW_CLEAR entry=${token.entryId} sinceClick=${sinceEntryMs(token.entryId)}ms " +
                "request=${token.id} key=${token.imageKey} duration=${elapsedMs(token.startedAtNanos)}ms " +
                "thread=${threadLabel()} reason=$reason"
        )
    }

    fun tilesScheduled(imageKey: String) = sessions[imageKey]?.let {
        it.tilesScheduledMs.compareAndSet(-1L, elapsedMs(it.startedAtNanos))
    }

    fun tilesReady(imageKey: String) = sessions[imageKey]?.let {
        it.tilesReadyMs.compareAndSet(-1L, elapsedMs(it.startedAtNanos))
    }

    fun cacheRead(imageKey: String, sessionId: Long, hit: Boolean, durationMs: Long) =
        session(imageKey, sessionId, "cacheRead")?.let {
            if (hit) it.cacheHits.incrementAndGet() else it.cacheMisses.incrementAndGet()
            it.cacheReadMs.addAndGet(durationMs)
        }

    fun regionDecoded(
        imageKey: String,
        sessionId: Long,
        rect: String,
        requestedSample: Int,
        actualSample: Int,
        outputPixels: Long,
        durationMs: Long,
    ) = session(imageKey, sessionId, "regionDecoded")?.let {
        it.regionDecodes.incrementAndGet()
        it.regionDecodeMs.addAndGet(durationMs)
        it.regionOutputPixels.addAndGet(outputPixels)
        it.firstRegion.compareAndSet(null, "$rect sample=$requestedSample->$actualSample output=${outputPixels}px")
    }

    fun tileWritten(imageKey: String, sessionId: Long, durationMs: Long, bytes: Long) =
        session(imageKey, sessionId, "tileWritten")?.let {
            it.tileWrites.incrementAndGet()
            it.tileWriteMs.addAndGet(durationMs)
            it.tileWriteBytes.addAndGet(bytes)
        }

    fun end(context: Context, imageKey: String, sessionId: Long) {
        val current = sessions[imageKey]
        if (current == null || current.id != sessionId || !sessions.remove(imageKey, current)) {
            Log.i(tag, "STALE_END session=$sessionId key=$imageKey current=${current?.id ?: 0}")
            return
        }
        val memory = memorySnapshot()
        val power = readPower(context)
        Log.i(
            tag,
            "END entry=${current.entryId} session=${current.id} key=$imageKey total=${elapsedMs(current.startedAtNanos)}ms " +
                "preview=${current.previewMs.get()}ms(${current.previewSource}) " +
                "tilesScheduled=${current.tilesScheduledMs.get()}ms tilesReady=${current.tilesReadyMs.get()}ms " +
                "cache=${current.cacheHits.get()}H/${current.cacheMisses.get()}M read=${current.cacheReadMs.get()}ms " +
                "region=${current.regionDecodes.get()}x/${current.regionDecodeMs.get()}ms " +
                "pixels=${current.regionOutputPixels.get()} first=[${current.firstRegion.get() ?: "none"}] " +
                "write=${current.tileWrites.get()}x/${current.tileWriteMs.get()}ms/${current.tileWriteBytes.get()}B " +
                "javaHeap=${memory.javaUsed}(delta=${memory.javaUsed - current.startMemory.javaUsed}) " +
                "nativeHeap=${memory.nativeUsed}(delta=${memory.nativeUsed - current.startMemory.nativeUsed}) " +
                "endCurrentUa=${power.currentUa ?: "unsupported"} endEstimatedMw=${power.estimatedMw ?: "unsupported"}"
        )
    }

    fun fastPreview(
        imageKey: String,
        cacheHit: Boolean,
        boundsMs: Long,
        decodeMs: Long,
        writeAndTrimMs: Long,
        totalMs: Long,
    ) {
        if (!isEnabled) return
        Log.i(
            tag,
            "FAST_PREVIEW entry=${currentEntryId()} key=$imageKey " +
                "cache=${if (cacheHit) "HIT" else "MISS"} bounds=${boundsMs}ms decode=${decodeMs}ms " +
                "writeAndTrim=${writeAndTrimMs}ms total=${totalMs}ms"
        )
    }

    private fun session(imageKey: String, expectedId: Long, event: String): Session? {
        val current = sessions[imageKey]
        if (current != null && current.id == expectedId) return current
        Log.i(
            tag,
            "STALE_TASK event=$event session=$expectedId key=$imageKey " +
                "current=${current?.id ?: 0} thread=${Thread.currentThread().name}"
        )
        return null
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L

    private fun sinceEntryMs(entryId: Long): Long {
        if (entryId == 0L) return -1L
        return entries[entryId]?.let { elapsedMs(it.requestedAtNanos) } ?: -1L
    }

    private fun threadLabel(): String =
        "${Thread.currentThread().name}/${Thread.currentThread().id}"

    private fun snapshotDetail(): String {
        val memory = memorySnapshot()
        val runtime = Runtime.getRuntime()
        val io = readProcIo()
        val gcCount = runCatching { Debug.getRuntimeStat("art.gc.gc-count") }.getOrNull() ?: "?"
        val blockingGc = runCatching { Debug.getRuntimeStat("art.gc.blocking-gc-count") }.getOrNull() ?: "?"
        val allocated = runCatching { Debug.getRuntimeStat("art.gc.bytes-allocated") }.getOrNull() ?: "?"
        val freed = runCatching { Debug.getRuntimeStat("art.gc.bytes-freed") }.getOrNull() ?: "?"
        return "processCpuMs=${Process.getElapsedCpuTime()} javaHeap=${memory.javaUsed} " +
            "nativeHeap=${memory.nativeUsed} heapMax=${runtime.maxMemory()} threads=${Thread.activeCount()} " +
            "gc=$gcCount blockingGc=$blockingGc allocated=$allocated freed=$freed $io"
    }

    private fun readProcIo(): String = runCatching {
        val values = File("/proc/self/io").readLines()
            .mapNotNull { line ->
                val split = line.split(':', limit = 2)
                if (split.size == 2) split[0] to split[1].trim() else null
            }
            .toMap()
        "ioRead=${values["read_bytes"] ?: "?"} ioWrite=${values["write_bytes"] ?: "?"} " +
            "rchar=${values["rchar"] ?: "?"} wchar=${values["wchar"] ?: "?"} " +
            "syscr=${values["syscr"] ?: "?"} syscw=${values["syscw"] ?: "?"}"
    }.getOrElse { "io=unavailable" }

    private fun powerSampleForEntry(context: Context, entryId: Long, label: String) {
        val sample = readPower(context)
        Log.i(
            tag,
            "ENTRY_POWER entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms at=$label " +
                "currentUa=${sample.currentUa ?: "unsupported"} voltageMv=${sample.voltageMv ?: "unsupported"} " +
                "estimatedMw=${sample.estimatedMw ?: "unsupported"}"
        )
    }

    private fun metricMs(metrics: FrameMetrics, metric: Int): Long {
        val value = metrics.getMetric(metric)
        return if (value < 0L) -1L else value / 1_000_000L
    }

    private fun memorySnapshot(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        return MemorySnapshot(runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize())
    }

    private fun readPower(context: Context): PowerSample {
        val manager = context.getSystemService(BatteryManager::class.java)
        val rawCurrent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentUa = rawCurrent?.takeUnless { it == Int.MIN_VALUE }
        val voltageMv = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
        val estimatedMw = if (currentUa != null && voltageMv != null) {
            String.format(Locale.US, "%.1f", abs(currentUa.toLong()) * voltageMv.toDouble() / 1_000_000.0)
        } else {
            null
        }
        return PowerSample(currentUa, voltageMv, estimatedMw)
    }

    private data class MemorySnapshot(val javaUsed: Long, val nativeUsed: Long)
    private data class PowerSample(val currentUa: Int?, val voltageMv: Int?, val estimatedMw: String?)
    private data class Entry(
        val id: Long,
        val requestedAtNanos: Long,
        val contentId: Long,
        val source: String,
    )

    private class FrameStats {
        val frames = AtomicInteger()
        val slowFrames = AtomicInteger()
        val totalNs = AtomicLong()
        val maxNs = AtomicLong()
    }

    private class Session(
        val id: Long,
        val entryId: Long,
        val startedAtNanos: Long,
        val startMemory: MemorySnapshot,
    ) {
        val previewMs = AtomicLong(-1L)
        val tilesScheduledMs = AtomicLong(-1L)
        val tilesReadyMs = AtomicLong(-1L)
        val cacheHits = AtomicInteger()
        val cacheMisses = AtomicInteger()
        val cacheReadMs = AtomicLong()
        val regionDecodes = AtomicInteger()
        val regionDecodeMs = AtomicLong()
        val regionOutputPixels = AtomicLong()
        val firstRegion = AtomicReference<String?>(null)
        val tileWrites = AtomicInteger()
        val tileWriteMs = AtomicLong()
        val tileWriteBytes = AtomicLong()
        @Volatile var previewSource: String = "none"
    }
}
