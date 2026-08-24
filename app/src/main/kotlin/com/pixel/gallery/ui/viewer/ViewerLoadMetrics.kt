package com.pixel.gallery.ui.viewer

import android.app.Activity
import android.content.BroadcastReceiver
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
import com.pixel.gallery.BuildConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Low-overhead, per-entry diagnostics used by the power comparison build. */
internal object ViewerLoadMetrics {
    private const val tag = "ViewerLoadMetrics"
    private const val powerTimelinePeriodMs = 100L
    private const val powerTimelineRetentionMs = 120_000L
    private const val entryTimelineBeforeMs = 5_000L
    private const val entryTimelineAfterMs = 5_000L
    private const val activeRuntimeSamplingWindowMs = 60_000L
    private const val idlePowerTimelinePeriodMs = 1_000L
    const val isEnabled: Boolean = BuildConfig.VIEWER_METRICS_ENABLED

    private val sessions = ConcurrentHashMap<String, Session>()
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val activeEntry = AtomicReference<Entry?>()
    private val frameStats = ConcurrentHashMap<Long, FrameStats>()
    private val activeWorks = ConcurrentHashMap<Long, WorkToken>()
    private val nextEntryId = AtomicLong()
    private val nextSessionId = AtomicLong()
    private val nextPreviewId = AtomicLong()
    private val nextWorkId = AtomicLong()
    private val powerTimelineStarted = AtomicBoolean()
    private val powerTimelineLock = Any()
    private val powerTimeline = ArrayDeque<ContinuousPowerSample>()
    private val batteryBroadcastTimelineLock = Any()
    private val batteryBroadcastTimeline = ArrayDeque<BatteryBroadcastSample>()
    private val nextBatteryBroadcastId = AtomicLong()
    private val lastRuntimeSampleNanos = AtomicLong()
    private val lastContinuousPowerPollNanos = AtomicLong()
    private val latestBatteryState = AtomicReference(BatteryState())
    private val latestContinuousPower = AtomicReference<ContinuousPowerSample?>()
    @Volatile private var timelineBatteryManager: BatteryManager? = null
    private val persistenceLock = Any()
    private val pendingPersistence = ArrayDeque<PersistedEntry>()
    private var pendingPersistenceTask: ScheduledFuture<*>? = null
    private val powerTimelineExecutor = ScheduledThreadPoolExecutor(
        1,
        { runnable -> Thread(runnable, "viewer-power-clock").apply { priority = Thread.MIN_PRIORITY } },
    ).apply {
        removeOnCancelPolicy = true
    }
    private val persistenceExecutor = ScheduledThreadPoolExecutor(
        1,
        { runnable -> Thread(runnable, "viewer-metrics-persist").apply { priority = Thread.MIN_PRIORITY } },
    ).apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(30L, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private val batteryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) recordBatteryBroadcast(intent)
        }
    }

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

    /**
     * Starts one process-wide clock that samples independently of Viewer actions.
     * Entry events only mark this timeline; they never reset its phase.
     */
    fun startContinuousPowerTimeline(context: Context) {
        if (!isEnabled || !powerTimelineStarted.compareAndSet(false, true)) return
        val applicationContext = context.applicationContext
        timelineBatteryManager = applicationContext.getSystemService(BatteryManager::class.java)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(
                batteryStateReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(batteryStateReceiver, filter)
        }
        sticky?.let(::updateBatteryState)
        powerTimelineExecutor.scheduleAtFixedRate(
            {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
                } catch (_: Exception) {
                }
                sampleContinuousPower()
            },
            0L,
            powerTimelinePeriodMs,
            TimeUnit.MILLISECONDS,
        )
        Log.i(
            tag,
            "POWER_CLOCK_START periodMs=$powerTimelinePeriodMs retentionMs=$powerTimelineRetentionMs " +
                "phaseOriginNanos=${SystemClock.elapsedRealtimeNanos()}",
        )
    }

    fun entryRequested(
        context: Context,
        contentId: Long,
        source: String,
        sourceItems: Int,
        detail: String = "",
    ): Long {
        if (!isEnabled) return 0L
        startContinuousPowerTimeline(context)
        pausePersistence()
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
        emit(
            entry.id,
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
        emit(
            entryId,
            "EVENT entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms name=$name " +
                "key=${imageKey ?: "none"} thread=${threadLabel()} $detail"
        )
    }

    /** Records an expensive process/power snapshot only at diagnostic phase boundaries. */
    fun snapshotEvent(
        name: String,
        detail: String = "",
        imageKey: String? = null,
        entryId: Long = currentEntryId(),
    ) {
        if (!isEnabled) return
        val power = latestContinuousPower.get()
        emit(
            entryId,
            "SNAPSHOT entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms name=$name " +
                "key=${imageKey ?: "none"} thread=${threadLabel()} " +
                "currentUa=${power?.currentUa ?: "unsupported"} " +
                "voltageMv=${power?.voltageMv ?: "unsupported"} " +
                "signedDischargeMw=${power?.signedDischargeMw()?.let {
                    String.format(Locale.US, "%.1f", it)
                } ?: "unsupported"} ${snapshotDetail()} $detail"
        )
    }

    fun checkpoint(context: Context, entryId: Long, label: String) {
        if (!isEnabled) return
        emit(
            entryId,
            "CHECKPOINT entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms at=$label " +
                snapshotDetail()
        )
        powerSampleForEntry(context, entryId, label)
    }

    fun workStarted(type: String, imageKey: String, detail: String = ""): WorkToken {
        if (!isEnabled) return WorkToken(0L, type, imageKey, 0L, 0L)
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
        emit(
            token.entryId,
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
        if (!isEnabled) return
        if (!activeWorks.remove(token.id, token)) {
            emit(
                token.entryId,
                "WORK_DUPLICATE_FINISH entry=${token.entryId} work=${token.id} type=${token.type} " +
                    "duration=${elapsedMs(token.startedAtNanos)}ms outcome=$outcome source=$source detail=$detail"
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection("Viewer:${token.type}", token.id.toInt())
        }
        emit(
            token.entryId,
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
            if (totalMs >= 12L) {
                stats.slowFrames.incrementAndGet()
                val gpuMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    metricMs(metrics, FrameMetrics.GPU_DURATION)
                } else {
                    -1L
                }
                emit(
                    entryId,
                    "FRAME_SLOW entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms total=${totalMs}ms " +
                        "input=${metricMs(metrics, FrameMetrics.INPUT_HANDLING_DURATION)}ms " +
                        "animation=${metricMs(metrics, FrameMetrics.ANIMATION_DURATION)}ms " +
                        "layout=${metricMs(metrics, FrameMetrics.LAYOUT_MEASURE_DURATION)}ms " +
                        "draw=${metricMs(metrics, FrameMetrics.DRAW_DURATION)}ms " +
                        "sync=${metricMs(metrics, FrameMetrics.SYNC_DURATION)}ms " +
                        "command=${metricMs(metrics, FrameMetrics.COMMAND_ISSUE_DURATION)}ms " +
                        "swap=${metricMs(metrics, FrameMetrics.SWAP_BUFFERS_DURATION)}ms " +
                        "gpu=${gpuMs}ms dropped=$dropped ${snapshotDetail()}"
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
        if (!isEnabled) return
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
        if (!isEnabled) return
        checkpoint(context, entryId, "entry-end:$reason")
        val entry = entries[entryId]
        if (entry != null) {
            val remaining = activeWorks.values.filter { it.entryId == entryId }
            if (remaining.isNotEmpty()) {
                emit(
                    entryId,
                    "WORK_STILL_ACTIVE entry=$entryId count=${remaining.size} " +
                        "items=${remaining.joinToString(limit = 20) { "${it.id}:${it.type}:${it.imageKey}" }}"
                )
            }
            activeEntry.compareAndSet(entry, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.endAsyncSection("ViewerEntry:${entry.contentId}", entry.id.toInt())
            }
            emit(
                entryId,
                "ENTRY_END entry=$entryId total=${elapsedMs(entry.requestedAtNanos)}ms " +
                    "contentId=${entry.contentId} source=${entry.source} reason=$reason ${snapshotDetail()}"
            )
            entries.remove(entryId, entry)
            queueEntryForPersistence(context.applicationContext, entry)
        }
    }

    fun begin(context: Context, imageKey: String, detail: String = ""): Long {
        if (!isEnabled) return 0L
        val id = nextSessionId.incrementAndGet()
        val memory = memorySnapshot()
        val entryId = currentEntryId()
        sessions[imageKey] = Session(id, entryId, SystemClock.elapsedRealtimeNanos(), memory)
        emit(
            entryId,
            "BEGIN entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms session=$id key=$imageKey " +
                "$detail javaHeap=${memory.javaUsed} nativeHeap=${memory.nativeUsed}"
        )
        powerSample(context, imageKey, id, "0ms")
        return id
    }

    fun currentSessionId(imageKey: String): Long = sessions[imageKey]?.id ?: 0L

    fun powerSample(context: Context, imageKey: String, sessionId: Long, label: String) {
        if (!isEnabled) return
        if (sessions[imageKey]?.id != sessionId) return
        val sample = latestPowerSample(context)
        val entryId = sessions[imageKey]?.entryId ?: currentEntryId()
        emit(
            entryId,
            "POWER session=$sessionId key=$imageKey at=$label currentUa=${sample.currentUa ?: "unsupported"} " +
                "voltageMv=${sample.voltageMv ?: "unsupported"} estimatedMw=${sample.estimatedMw ?: "unsupported"} " +
                "signedDischargeMw=${sample.signedDischargeMw ?: "unsupported"} " +
                "sampleAgeMs=${sampleAgeMs(sample)} sampleClockMs=${sample.sampledAtNanos / 1_000_000L}"
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
        if (!isEnabled) return PreviewToken(0L, imageKey, 0L, 0L)
        val entryId = currentEntryId()
        val token = PreviewToken(
            id = nextPreviewId.incrementAndGet(),
            imageKey = imageKey,
            startedAtNanos = SystemClock.elapsedRealtimeNanos(),
            entryId = entryId,
        )
        emit(
            entryId,
            "PREVIEW_START entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms request=${token.id} " +
                "key=$imageKey active=$activeAtStart view=${viewWidth}x$viewHeight model=$modelType " +
                "thread=${threadLabel()} $detail"
        )
        return token
    }

    fun previewReady(token: PreviewToken, source: String, detail: String) {
        if (!isEnabled) return
        sessions[token.imageKey]?.let {
            it.previewMs.compareAndSet(-1L, elapsedMs(it.startedAtNanos))
            it.previewSource = source
        }
        emit(
            token.entryId,
            "PREVIEW_READY entry=${token.entryId} sinceClick=${sinceEntryMs(token.entryId)}ms " +
                "request=${token.id} key=${token.imageKey} source=$source " +
                "duration=${elapsedMs(token.startedAtNanos)}ms thread=${threadLabel()} $detail"
        )
    }

    fun previewFailed(token: PreviewToken, error: String) {
        if (!isEnabled) return
        emit(
            token.entryId,
            "PREVIEW_FAILED entry=${token.entryId} sinceClick=${sinceEntryMs(token.entryId)}ms " +
                "request=${token.id} key=${token.imageKey} duration=${elapsedMs(token.startedAtNanos)}ms " +
                "thread=${threadLabel()} error=$error"
        )
    }

    fun previewCleared(token: PreviewToken, reason: String) {
        if (!isEnabled) return
        emit(
            token.entryId,
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

    fun baseImageDecoded(
        imageKey: String,
        sessionId: Long,
        durationMs: Long,
        outputPixels: Long,
        allocationBytes: Long,
    ) = session(imageKey, sessionId, "baseImageDecoded")?.let {
        it.baseDecodes.incrementAndGet()
        it.baseDecodeMs.addAndGet(durationMs)
        it.baseOutputPixels.addAndGet(outputPixels)
        it.baseAllocationBytes.addAndGet(allocationBytes)
    }

    fun tileWritten(imageKey: String, sessionId: Long, durationMs: Long, bytes: Long) {
        if (!isEnabled) return
        session(imageKey, sessionId, "tileWritten")?.let {
            it.tileWrites.incrementAndGet()
            it.tileWriteMs.addAndGet(durationMs)
            it.tileWriteBytes.addAndGet(bytes)
        }
    }

    fun end(context: Context, imageKey: String, sessionId: Long) {
        if (!isEnabled) return
        val current = sessions[imageKey]
        if (current == null || current.id != sessionId || !sessions.remove(imageKey, current)) {
            emit(
                current?.entryId ?: currentEntryId(),
                "STALE_END session=$sessionId key=$imageKey current=${current?.id ?: 0}",
            )
            return
        }
        val memory = memorySnapshot()
        val power = latestPowerSample(context)
        emit(
            current.entryId,
            "END entry=${current.entryId} session=${current.id} key=$imageKey total=${elapsedMs(current.startedAtNanos)}ms " +
                "preview=${current.previewMs.get()}ms(${current.previewSource}) " +
                "tilesScheduled=${current.tilesScheduledMs.get()}ms tilesReady=${current.tilesReadyMs.get()}ms " +
                "cache=${current.cacheHits.get()}H/${current.cacheMisses.get()}M read=${current.cacheReadMs.get()}ms " +
                "region=${current.regionDecodes.get()}x/${current.regionDecodeMs.get()}ms " +
                "pixels=${current.regionOutputPixels.get()} first=[${current.firstRegion.get() ?: "none"}] " +
                "base=${current.baseDecodes.get()}x/${current.baseDecodeMs.get()}ms/" +
                "${current.baseOutputPixels.get()}px/${current.baseAllocationBytes.get()}B " +
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
        val entryId = currentEntryId()
        emit(
            entryId,
            "FAST_PREVIEW entry=$entryId key=$imageKey " +
                "cache=${if (cacheHit) "HIT" else "MISS"} bounds=${boundsMs}ms decode=${decodeMs}ms " +
                "writeAndTrim=${writeAndTrimMs}ms total=${totalMs}ms"
        )
    }

    private fun session(imageKey: String, expectedId: Long, event: String): Session? {
        val current = sessions[imageKey]
        if (current != null && current.id == expectedId) return current
        emit(
            current?.entryId ?: currentEntryId(),
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
        val sample = latestPowerSample(context)
        emit(
            entryId,
            "ENTRY_POWER entry=$entryId sinceClick=${sinceEntryMs(entryId)}ms at=$label " +
                "currentUa=${sample.currentUa ?: "unsupported"} voltageMv=${sample.voltageMv ?: "unsupported"} " +
                "estimatedMw=${sample.estimatedMw ?: "unsupported"} " +
                "signedDischargeMw=${sample.signedDischargeMw ?: "unsupported"} " +
                "sampleAgeMs=${sampleAgeMs(sample)} sampleClockMs=${sample.sampledAtNanos / 1_000_000L}"
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

    private fun latestPowerSample(context: Context): PowerSample {
        latestContinuousPower.get()?.let { sample ->
            return sample.toPowerSample()
        }
        return readPower(context)
    }

    private fun readPower(context: Context): PowerSample {
        val sampledAtNanos = SystemClock.elapsedRealtimeNanos()
        val manager = context.getSystemService(BatteryManager::class.java)
        val rawCurrent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentUa = rawCurrent?.takeUnless { it == Int.MIN_VALUE }
        val state = latestBatteryState.get()
        val voltageMv = state.voltageMv
        val estimatedMw = if (currentUa != null && voltageMv != null) {
            String.format(Locale.US, "%.1f", abs(currentUa.toLong()) * voltageMv.toDouble() / 1_000_000.0)
        } else {
            null
        }
        val signedDischargeMw = if (currentUa != null && voltageMv != null) {
            String.format(Locale.US, "%.1f", -currentUa.toLong() * voltageMv.toDouble() / 1_000_000.0)
        } else {
            null
        }
        return PowerSample(
            currentUa = currentUa,
            voltageMv = voltageMv,
            estimatedMw = estimatedMw,
            signedDischargeMw = signedDischargeMw,
            sampledAtNanos = sampledAtNanos,
            plugged = state.plugged,
            status = state.status,
        )
    }

    private fun sampleContinuousPower() {
        val sampledAtNanos = SystemClock.elapsedRealtimeNanos()
        val active = activeEntry.get()
        val activeAgeMs = active?.let { elapsedMs(it.requestedAtNanos) } ?: Long.MAX_VALUE
        val requiredPeriodMs = if (activeAgeMs <= activeRuntimeSamplingWindowMs) {
            powerTimelinePeriodMs
        } else {
            idlePowerTimelinePeriodMs
        }
        val previousPoll = lastContinuousPowerPollNanos.get()
        if (
            sampledAtNanos - previousPoll < TimeUnit.MILLISECONDS.toNanos(requiredPeriodMs) ||
            !lastContinuousPowerPollNanos.compareAndSet(previousPoll, sampledAtNanos)
        ) {
            return
        }
        val rawCurrent = timelineBatteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentUa = rawCurrent?.takeUnless { it == Int.MIN_VALUE }
        val state = latestBatteryState.get()
        val sample = ContinuousPowerSample(
            sampledAtNanos = sampledAtNanos,
            currentUa = currentUa,
            voltageMv = state.voltageMv,
            plugged = state.plugged,
            status = state.status,
        )
        latestContinuousPower.set(sample)
        synchronized(powerTimelineLock) {
            powerTimeline.addLast(sample)
            val cutoffNanos = sampledAtNanos - TimeUnit.MILLISECONDS.toNanos(powerTimelineRetentionMs)
            while (powerTimeline.firstOrNull()?.sampledAtNanos?.let { it < cutoffNanos } == true) {
                powerTimeline.removeFirst()
            }
        }
        if (active != null && activeAgeMs <= activeRuntimeSamplingWindowMs) {
            val previous = lastRuntimeSampleNanos.get()
            if (
                sampledAtNanos - previous >= 250_000_000L &&
                lastRuntimeSampleNanos.compareAndSet(previous, sampledAtNanos)
            ) {
                emit(
                    active.id,
                    "RUNTIME_SAMPLE entry=${active.id} sinceClick=${sinceEntryMs(active.id)}ms " +
                        "currentUa=${sample.currentUa ?: "unsupported"} " +
                        "voltageMv=${sample.voltageMv ?: "unsupported"} " +
                        "signedDischargeMw=${sample.signedDischargeMw()?.let {
                            String.format(Locale.US, "%.1f", it)
                        } ?: "unsupported"} ${snapshotDetail()}",
                )
            }
        }
    }

    private fun updateBatteryState(intent: Intent) {
        latestBatteryState.set(
            BatteryState(
                voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                    .takeUnless { it == Int.MIN_VALUE },
                plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, Int.MIN_VALUE)
                    .takeUnless { it == Int.MIN_VALUE },
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, Int.MIN_VALUE)
                    .takeUnless { it == Int.MIN_VALUE },
            ),
        )
    }

    /**
     * Battery Guru refreshes its live reading from the same sticky battery broadcast
     * stream. Keep this clock separate from the 100 ms polling clock so a test can
     * distinguish real Viewer work from whether the system happened to publish a
     * battery update while that short work was running.
     */
    private fun recordBatteryBroadcast(intent: Intent) {
        val sampledAtNanos = SystemClock.elapsedRealtimeNanos()
        updateBatteryState(intent)
        val rawCurrent = timelineBatteryManager
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val state = latestBatteryState.get()
        val sample = BatteryBroadcastSample(
            id = nextBatteryBroadcastId.incrementAndGet(),
            sampledAtNanos = sampledAtNanos,
            currentUa = rawCurrent?.takeUnless { it == Int.MIN_VALUE },
            voltageMv = state.voltageMv,
            plugged = state.plugged,
            status = state.status,
        )
        synchronized(batteryBroadcastTimelineLock) {
            batteryBroadcastTimeline.addLast(sample)
            val cutoffNanos = sampledAtNanos -
                TimeUnit.MILLISECONDS.toNanos(powerTimelineRetentionMs)
            while (
                batteryBroadcastTimeline.firstOrNull()
                    ?.sampledAtNanos
                    ?.let { it < cutoffNanos } == true
            ) {
                batteryBroadcastTimeline.removeFirst()
            }
        }
        Log.i(
            tag,
            "BATTERY_BROADCAST id=${sample.id} clockMs=${sample.sampledAtNanos / 1_000_000L} " +
                "currentUa=${sample.currentUa ?: "unsupported"} " +
                "voltageMv=${sample.voltageMv ?: "unsupported"} " +
                "signedDischargeMw=${sample.signedDischargeMw()?.let {
                    String.format(Locale.US, "%.1f", it)
                } ?: "unsupported"} plugged=${sample.plugged ?: "unsupported"} " +
                "status=${sample.status ?: "unsupported"}",
        )
    }

    private fun sampleAgeMs(sample: PowerSample): Long =
        (SystemClock.elapsedRealtimeNanos() - sample.sampledAtNanos).coerceAtLeast(0L) / 1_000_000L

    private fun ContinuousPowerSample.toPowerSample(): PowerSample {
        val magnitude = if (currentUa != null && voltageMv != null) {
            String.format(Locale.US, "%.1f", abs(currentUa.toLong()) * voltageMv.toDouble() / 1_000_000.0)
        } else {
            null
        }
        val signed = if (currentUa != null && voltageMv != null) {
            String.format(Locale.US, "%.1f", -currentUa.toLong() * voltageMv.toDouble() / 1_000_000.0)
        } else {
            null
        }
        return PowerSample(
            currentUa = currentUa,
            voltageMv = voltageMv,
            estimatedMw = magnitude,
            signedDischargeMw = signed,
            sampledAtNanos = sampledAtNanos,
            plugged = plugged,
            status = status,
        )
    }

    private fun emit(entryId: Long, line: String) {
        Log.i(tag, line)
        if (entryId != 0L) {
            entries[entryId]?.record(line)
        }
    }

    private fun pausePersistence() {
        synchronized(persistenceLock) {
            pendingPersistenceTask?.cancel(false)
            pendingPersistenceTask = null
        }
    }

    private fun queueEntryForPersistence(context: Context, entry: Entry) {
        val lines = entry.snapshotLines()
        if (lines.isEmpty()) return
        val timeline = snapshotPowerTimeline(entry.requestedAtNanos)
        val batteryBroadcasts = snapshotBatteryBroadcastTimeline(entry.requestedAtNanos)
        val persistedEntry = PersistedEntry(
            id = entry.id,
            contentId = entry.contentId,
            requestedAtNanos = entry.requestedAtNanos,
            lines = lines,
            powerTimeline = timeline,
            batteryBroadcasts = batteryBroadcasts,
        )
        Log.i(
            tag,
            buildString { appendPowerTimelineSummary(persistedEntry) }.trimEnd(),
        )
        synchronized(persistenceLock) {
            pendingPersistence.addLast(persistedEntry)
            pendingPersistenceTask?.cancel(false)
            // Persist only after the user has stopped entering Viewer for a while. Writing
            // every entry a few seconds after exit can overlap the next entry and corrupt
            // the very power trace this diagnostic build is meant to capture.
            pendingPersistenceTask = persistenceExecutor.schedule({
                persistPendingEntries(context)
            }, 15L, TimeUnit.SECONDS)
        }
    }

    private fun persistPendingEntries(context: Context) {
        if (activeEntry.get() != null) return
        val batch = synchronized(persistenceLock) {
            pendingPersistenceTask = null
            if (pendingPersistence.isEmpty()) {
                emptyList()
            } else {
                pendingPersistence.toList().also { pendingPersistence.clear() }
            }
        }
        if (batch.isEmpty()) return
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
        } catch (_: Exception) {
        }
        runCatching {
            val directory = context.getExternalFilesDir("viewer_metrics")
                ?: File(context.filesDir, "viewer_metrics")
            if (!directory.exists() && !directory.mkdirs()) return@runCatching
            val current = File(directory, "viewer-entries.log")
            val previous = File(directory, "viewer-entries.previous.log")
            if (current.length() >= 32L * 1024L * 1024L) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText(
                buildString {
                    batch.forEach { entry ->
                        append("=== entry=")
                        append(entry.id)
                        append(" contentId=")
                        append(entry.contentId)
                        append(" ===\n")
                        entry.lines.forEach {
                            append(it)
                            append('\n')
                        }
                        appendPowerTimeline(entry)
                        appendBatteryBroadcastTimeline(entry)
                    }
                },
            )
        }.onFailure {
            // Keep the batch available for a later idle flush if storage was transiently
            // unavailable.
            synchronized(persistenceLock) {
                batch.asReversed().forEach(pendingPersistence::addFirst)
            }
        }
    }

    private fun snapshotPowerTimeline(requestedAtNanos: Long): List<ContinuousPowerSample> {
        val startNanos = requestedAtNanos - TimeUnit.MILLISECONDS.toNanos(entryTimelineBeforeMs)
        val endNanos = requestedAtNanos + TimeUnit.MILLISECONDS.toNanos(entryTimelineAfterMs)
        return synchronized(powerTimelineLock) {
            powerTimeline.filter { it.sampledAtNanos in startNanos..endNanos }
        }
    }

    private fun snapshotBatteryBroadcastTimeline(
        requestedAtNanos: Long,
    ): List<BatteryBroadcastSample> {
        val startNanos = requestedAtNanos - TimeUnit.MILLISECONDS.toNanos(entryTimelineBeforeMs)
        val endNanos = requestedAtNanos + TimeUnit.MILLISECONDS.toNanos(entryTimelineAfterMs)
        return synchronized(batteryBroadcastTimelineLock) {
            batteryBroadcastTimeline.filter { it.sampledAtNanos in startNanos..endNanos }
        }
    }

    private fun StringBuilder.appendPowerTimeline(entry: PersistedEntry) {
        append("POWER_TIMELINE entry=")
        append(entry.id)
        append(" clock=continuous periodMs=")
        append(powerTimelinePeriodMs)
        append(" sampleCount=")
        append(entry.powerTimeline.size)
        append(" beforeMs=")
        append(entryTimelineBeforeMs)
        append(" afterMs=")
        append(entryTimelineAfterMs)
        append('\n')
        entry.powerTimeline.forEach { sample ->
            val relativeMs = TimeUnit.NANOSECONDS.toMillis(
                sample.sampledAtNanos - entry.requestedAtNanos,
            )
            append("POWER_CLOCK entry=")
            append(entry.id)
            append(" relativeMs=")
            append(relativeMs)
            append(" clockMs=")
            append(sample.sampledAtNanos / 1_000_000L)
            append(" currentUa=")
            append(sample.currentUa ?: "unsupported")
            append(" voltageMv=")
            append(sample.voltageMv ?: "unsupported")
            append(" signedDischargeMw=")
            append(sample.signedDischargeMw()?.let { String.format(Locale.US, "%.1f", it) } ?: "unsupported")
            append(" magnitudeMw=")
            append(sample.signedDischargeMw()?.let { String.format(Locale.US, "%.1f", abs(it)) } ?: "unsupported")
            append(" plugged=")
            append(sample.plugged ?: "unsupported")
            append(" status=")
            append(sample.status ?: "unsupported")
            append('\n')
        }
        appendPowerTimelineSummary(entry)
    }

    private fun StringBuilder.appendBatteryBroadcastTimeline(entry: PersistedEntry) {
        append("BATTERY_BROADCAST_TIMELINE entry=")
        append(entry.id)
        append(" sampleCount=")
        append(entry.batteryBroadcasts.size)
        append(" beforeMs=")
        append(entryTimelineBeforeMs)
        append(" afterMs=")
        append(entryTimelineAfterMs)
        append('\n')
        entry.batteryBroadcasts.forEach { sample ->
            val relativeMs = TimeUnit.NANOSECONDS.toMillis(
                sample.sampledAtNanos - entry.requestedAtNanos,
            )
            append("BATTERY_BROADCAST entry=")
            append(entry.id)
            append(" id=")
            append(sample.id)
            append(" relativeMs=")
            append(relativeMs)
            append(" clockMs=")
            append(sample.sampledAtNanos / 1_000_000L)
            append(" currentUa=")
            append(sample.currentUa ?: "unsupported")
            append(" voltageMv=")
            append(sample.voltageMv ?: "unsupported")
            append(" signedDischargeMw=")
            append(
                sample.signedDischargeMw()
                    ?.let { String.format(Locale.US, "%.1f", it) }
                    ?: "unsupported",
            )
            append(" plugged=")
            append(sample.plugged ?: "unsupported")
            append(" status=")
            append(sample.status ?: "unsupported")
            append('\n')
        }
        val firstSecond = entry.batteryBroadcasts.mapNotNull { sample ->
            val relativeMs = TimeUnit.NANOSECONDS.toMillis(
                sample.sampledAtNanos - entry.requestedAtNanos,
            )
            if (relativeMs in 0L..1_000L) {
                sample.signedDischargeMw()?.let { relativeMs to it }
            } else {
                null
            }
        }
        append("BATTERY_BROADCAST_SUMMARY entry=")
        append(entry.id)
        append(" firstAfterClickMs=")
        append(firstSecond.firstOrNull()?.first ?: "none")
        append(" firstAfterClickMw=")
        append(formatMetric(firstSecond.firstOrNull()?.second))
        append(" first1sCount=")
        append(firstSecond.size)
        append(" first1sPeakMw=")
        append(formatMetric(firstSecond.maxOfOrNull { it.second }))
        append('\n')
    }

    private fun StringBuilder.appendPowerTimelineSummary(entry: PersistedEntry) {
        val values = entry.powerTimeline.mapNotNull { sample ->
            sample.signedDischargeMw()?.let { power ->
                TimeUnit.NANOSECONDS.toMillis(sample.sampledAtNanos - entry.requestedAtNanos) to power
            }
        }
        val baseline = values.filter { it.first in -3_000L..-250L }.map { it.second }
        val firstSecond = values.filter { it.first in 0L..1_000L }.map { it.second }
        val firstThreeSeconds = values.filter { it.first in 0L..3_000L }.map { it.second }
        val baselineMean = baseline.averageOrNull()
        val excessEnergy1s = integrateExcessEnergy(
            values = values,
            fromMs = 0L,
            toMs = 1_000L,
            baselineMw = baselineMean,
        )
        val excessEnergy3s = integrateExcessEnergy(
            values = values,
            fromMs = 0L,
            toMs = 3_000L,
            baselineMw = baselineMean,
        )
        append("POWER_CLOCK_SUMMARY entry=")
        append(entry.id)
        append(" baselineMeanMw=")
        append(formatMetric(baselineMean))
        append(" first1sMeanMw=")
        append(formatMetric(firstSecond.averageOrNull()))
        append(" first1sPeakMw=")
        append(formatMetric(firstSecond.maxOrNull()))
        append(" first3sMeanMw=")
        append(formatMetric(firstThreeSeconds.averageOrNull()))
        append(" first3sPeakMw=")
        append(formatMetric(firstThreeSeconds.maxOrNull()))
        append(" excessEnergy1sMj=")
        append(formatMetric(excessEnergy1s))
        append(" excessEnergy3sMj=")
        append(formatMetric(excessEnergy3s))
        append(" distinctCurrentValues=")
        append(entry.powerTimeline.mapNotNull { it.currentUa }.distinct().size)
        append('\n')
    }

    private fun integrateExcessEnergy(
        values: List<Pair<Long, Double>>,
        fromMs: Long,
        toMs: Long,
        baselineMw: Double?,
    ): Double? {
        if (baselineMw == null) return null
        val inWindow = values.filter { it.first in fromMs..toMs }
        if (inWindow.isEmpty()) return null
        var energyMj = 0.0
        inWindow.forEachIndexed { index, sample ->
            val nextMs = inWindow.getOrNull(index + 1)?.first ?: toMs
            val durationMs = (nextMs.coerceAtMost(toMs) - sample.first.coerceAtLeast(fromMs))
                .coerceAtLeast(0L)
            energyMj += (sample.second - baselineMw).coerceAtLeast(0.0) * durationMs / 1_000.0
        }
        return energyMj
    }

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else average()

    private fun formatMetric(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f", it) } ?: "unsupported"

    private data class MemorySnapshot(val javaUsed: Long, val nativeUsed: Long)
    private data class BatteryState(
        val voltageMv: Int? = null,
        val plugged: Int? = null,
        val status: Int? = null,
    )
    private data class ContinuousPowerSample(
        val sampledAtNanos: Long,
        val currentUa: Int?,
        val voltageMv: Int?,
        val plugged: Int?,
        val status: Int?,
    ) {
        fun signedDischargeMw(): Double? {
            val current = currentUa ?: return null
            val voltage = voltageMv ?: return null
            return -current.toDouble() * voltage / 1_000_000.0
        }
    }
    private data class BatteryBroadcastSample(
        val id: Long,
        val sampledAtNanos: Long,
        val currentUa: Int?,
        val voltageMv: Int?,
        val plugged: Int?,
        val status: Int?,
    ) {
        fun signedDischargeMw(): Double? {
            val current = currentUa ?: return null
            val voltage = voltageMv ?: return null
            return -current.toDouble() * voltage / 1_000_000.0
        }
    }
    private data class PowerSample(
        val currentUa: Int?,
        val voltageMv: Int?,
        val estimatedMw: String?,
        val signedDischargeMw: String?,
        val sampledAtNanos: Long,
        val plugged: Int?,
        val status: Int?,
    )
    private data class PersistedEntry(
        val id: Long,
        val contentId: Long,
        val requestedAtNanos: Long,
        val lines: List<String>,
        val powerTimeline: List<ContinuousPowerSample>,
        val batteryBroadcasts: List<BatteryBroadcastSample>,
    )
    private class Entry(
        val id: Long,
        val requestedAtNanos: Long,
        val contentId: Long,
        val source: String,
    ) {
        private val lines = ArrayDeque<String>()

        fun record(line: String) = synchronized(lines) {
            if (lines.size >= 32_768) lines.removeFirst()
            lines.addLast(line)
        }

        fun snapshotLines(): List<String> = synchronized(lines) { lines.toList() }
    }

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
        val baseDecodes = AtomicInteger()
        val baseDecodeMs = AtomicLong()
        val baseOutputPixels = AtomicLong()
        val baseAllocationBytes = AtomicLong()
        val tileWrites = AtomicInteger()
        val tileWriteMs = AtomicLong()
        val tileWriteBytes = AtomicLong()
        @Volatile var previewSource: String = "none"
    }
}
