package com.pixel.gallery.ui.viewer

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Low-overhead, per-image timings for comparing cold and warm viewer entries.
 * Values are emitted as one summary line when the active page is disposed.
 */
internal object ViewerLoadMetrics {
    private const val tag = "ViewerLoadMetrics"
    const val isEnabled: Boolean = false
    private val sessions = if (isEnabled) ConcurrentHashMap<String, Session>() else null

    fun begin(imageKey: String) {
        val activeSessions = sessions ?: return
        activeSessions[imageKey] = Session(SystemClock.elapsedRealtimeNanos())
        Log.i(tag, "BEGIN key=$imageKey")
    }

    fun previewReady(imageKey: String, source: String) = sessions?.get(imageKey)?.let {
        it.previewMs.compareAndSet(-1L, elapsedMs(it))
        it.previewSource = source
    }

    fun tilesScheduled(imageKey: String) = sessions?.get(imageKey)?.let {
        it.tilesScheduledMs.compareAndSet(-1L, elapsedMs(it))
    }

    fun tilesReady(imageKey: String) = sessions?.get(imageKey)?.let {
        it.tilesReadyMs.compareAndSet(-1L, elapsedMs(it))
    }

    fun cacheRead(imageKey: String, hit: Boolean, durationMs: Long) = sessions?.get(imageKey)?.let {
        if (hit) it.cacheHits.incrementAndGet() else it.cacheMisses.incrementAndGet()
        it.cacheReadMs.addAndGet(durationMs)
    }

    fun regionDecoded(
        imageKey: String,
        rect: String,
        requestedSample: Int,
        actualSample: Int,
        outputPixels: Long,
        durationMs: Long
    ) = sessions?.get(imageKey)?.let {
        it.regionDecodes.incrementAndGet()
        it.regionDecodeMs.addAndGet(durationMs)
        it.regionOutputPixels.addAndGet(outputPixels)
        it.firstRegion.compareAndSet(
            null,
            "$rect sample=$requestedSample->$actualSample output=${outputPixels}px"
        )
    }

    fun tileWritten(imageKey: String, durationMs: Long) = sessions?.get(imageKey)?.let {
        it.tileWrites.incrementAndGet()
        it.tileWriteMs.addAndGet(durationMs)
    }

    fun end(imageKey: String) {
        val session = sessions?.remove(imageKey) ?: return
        Log.i(
            tag,
            "END key=$imageKey total=${elapsedMs(session)}ms " +
                "preview=${session.previewMs.get()}ms(${session.previewSource}) " +
                "tilesScheduled=${session.tilesScheduledMs.get()}ms tilesReady=${session.tilesReadyMs.get()}ms " +
                "cache=${session.cacheHits.get()}H/${session.cacheMisses.get()}M read=${session.cacheReadMs.get()}ms " +
                "region=${session.regionDecodes.get()}x/${session.regionDecodeMs.get()}ms " +
                "pixels=${session.regionOutputPixels.get()} first=[${session.firstRegion.get() ?: "none"}] " +
                "write=${session.tileWrites.get()}x/${session.tileWriteMs.get()}ms"
        )
    }

    fun fastPreview(
        imageKey: String,
        cacheHit: Boolean,
        boundsMs: Long,
        decodeMs: Long,
        writeAndTrimMs: Long,
        totalMs: Long
    ) {
        if (!isEnabled) return
        Log.i(
            tag,
            "FAST_PREVIEW key=$imageKey cache=${if (cacheHit) "HIT" else "MISS"} " +
                "bounds=${boundsMs}ms decode=${decodeMs}ms writeAndTrim=${writeAndTrimMs}ms total=${totalMs}ms"
        )
    }

    private fun elapsedMs(session: Session): Long =
        (SystemClock.elapsedRealtimeNanos() - session.startedAtNanos) / 1_000_000L

    private class Session(val startedAtNanos: Long) {
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
        @Volatile var previewSource: String = "none"
    }
}
