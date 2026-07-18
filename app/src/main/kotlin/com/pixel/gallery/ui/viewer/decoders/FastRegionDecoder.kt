package com.pixel.gallery.ui.viewer.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import com.davemorrissey.labs.subscaleview.ImageRegionDecoder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
private object SsivTileCacheBudget {
    private const val PREFS = "ssiv_tile_cache_budget"
    private const val ESTIMATED_BYTES = "estimated_bytes"
    private const val MAX_BYTES = 512L * 1024L * 1024L
    private const val TRIM_TO_BYTES = 460L * 1024L * 1024L
    private const val PERSIST_STEP_BYTES = 16L * 1024L * 1024L

    private val estimatedBytes = AtomicLong(-1L)
    private val lastPersistedBytes = AtomicLong(0L)
    private val trimScheduled = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        0,
        1,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(),
        { runnable -> Thread(runnable, "ssiv-cache-trim").apply { priority = Thread.MIN_PRIORITY } }
    )

    fun recordWrite(context: Context, directory: File, bytes: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (estimatedBytes.compareAndSet(-1L, prefs.getLong(ESTIMATED_BYTES, 0L))) {
            lastPersistedBytes.set(estimatedBytes.get())
        }
        val total = estimatedBytes.addAndGet(bytes.coerceAtLeast(0L))
        val persisted = lastPersistedBytes.get()
        if (total - persisted >= PERSIST_STEP_BYTES && lastPersistedBytes.compareAndSet(persisted, total)) {
            prefs.edit().putLong(ESTIMATED_BYTES, total).apply()
        }
        if (total < MAX_BYTES || !trimScheduled.compareAndSet(false, true)) return

        executor.execute {
            try {
                val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
                var actualBytes = files.sumOf { it.length() }
                for (file in files) {
                    if (actualBytes <= TRIM_TO_BYTES) break
                    val length = file.length()
                    if (file.delete()) actualBytes -= length
                }
                val remaining = actualBytes.coerceAtLeast(0L)
                estimatedBytes.set(remaining)
                lastPersistedBytes.set(remaining)
                prefs.edit().putLong(ESTIMATED_BYTES, remaining).apply()
            } finally {
                trimScheduled.set(false)
            }
        }
    }

    fun reset(context: Context) {
        estimatedBytes.set(0L)
        lastPersistedBytes.set(0L)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(ESTIMATED_BYTES, 0L).apply()
    }
}

fun resetSsivTileCacheBudget(context: Context) {
    SsivTileCacheBudget.reset(context.applicationContext)
}

class FastRegionDecoder(
    private val minTileDpi: Int,
    private val imageVersion: String
) : ImageRegionDecoder {
    private var decoder: BitmapRegionDecoder? = null
    private val decoderLock = Any()
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var sourceWidth = 0
    private var sourceHeight = 0
    private lateinit var tileCacheDir: File
    private lateinit var appContext: Context
    private var metricsKey: String = ""

    override fun init(context: Context, uri: Uri): Point {
        appContext = context.applicationContext
        metricsKey = imageVersion
        val displayMetrics = context.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        tileCacheDir = File(context.cacheDir, "ssiv_tile_cache")

        val inputStream = if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path ?: uri.toString()
            File(path).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        }
        decoder = BitmapRegionDecoder.newInstance(inputStream!!, false)
        sourceWidth = decoder!!.width
        sourceHeight = decoder!!.height
        return Point(sourceWidth, sourceHeight)
    }

    override fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap {
        synchronized(decoderLock) {
            var newSampleSize = sampleSize
            if (minTileDpi <= 160) {
                if ((rect.width() > rect.height() && screenWidth > screenHeight) || (rect.height() > rect.width() && screenHeight > screenWidth)) {
                    if ((rect.width() / sampleSize > screenWidth || rect.height() / sampleSize > screenHeight)) {
                        newSampleSize *= 2
                    }
                }
            }

            val options = BitmapFactory.Options()
            options.inSampleSize = newSampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            val cacheFile = tileCacheFile(rect, newSampleSize)
            val metricsEnabled = ViewerLoadMetrics.isEnabled
            val cacheReadStartedAt = if (metricsEnabled) SystemClock.elapsedRealtimeNanos() else 0L
            decodeCachedTile(cacheFile)?.let {
                if (metricsEnabled) {
                    ViewerLoadMetrics.cacheRead(
                        imageKey = metricsKey,
                        hit = true,
                        durationMs = (SystemClock.elapsedRealtimeNanos() - cacheReadStartedAt) / 1_000_000L
                    )
                }
                return UltraHdrTileSupport.attach(
                    imageKey = imageVersion,
                    baseTile = it,
                    sourceRect = rect,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                )
            }
            if (metricsEnabled) {
                ViewerLoadMetrics.cacheRead(
                    imageKey = metricsKey,
                    hit = false,
                    durationMs = (SystemClock.elapsedRealtimeNanos() - cacheReadStartedAt) / 1_000_000L
                )
            }

            val decodeStartedAt = if (metricsEnabled) SystemClock.elapsedRealtimeNanos() else 0L
            val bitmap = decoder?.decodeRegion(rect, options)
                ?: throw RuntimeException("Region decoder returned null bitmap")
            if (metricsEnabled) {
                ViewerLoadMetrics.regionDecoded(
                    imageKey = metricsKey,
                    rect = "${rect.left},${rect.top}-${rect.right},${rect.bottom}",
                    requestedSample = sampleSize,
                    actualSample = newSampleSize,
                    outputPixels = bitmap.width.toLong() * bitmap.height.toLong(),
                    durationMs = (SystemClock.elapsedRealtimeNanos() - decodeStartedAt) / 1_000_000L
                )
            }
            val writeStartedAt = if (metricsEnabled) SystemClock.elapsedRealtimeNanos() else 0L
            saveCachedTile(cacheFile, bitmap)
            if (metricsEnabled) {
                ViewerLoadMetrics.tileWritten(
                    imageKey = metricsKey,
                    durationMs = (SystemClock.elapsedRealtimeNanos() - writeStartedAt) / 1_000_000L
                )
            }
            return UltraHdrTileSupport.attach(
                imageKey = imageVersion,
                baseTile = bitmap,
                sourceRect = rect,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            )
        }
    }

    override fun isReady() = decoder != null && !decoder!!.isRecycled

    override fun recycle() {
        decoder?.recycle()
    }
    private fun tileCacheFile(rect: Rect, sampleSize: Int): File {
        val key = "$imageVersion:${rect.left}:${rect.top}:${rect.right}:${rect.bottom}:$sampleSize"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(tileCacheDir, "$digest.jpg")
    }

    private fun decodeCachedTile(cacheFile: File): Bitmap? {
        if (!cacheFile.isFile) return null
        return try {
            BitmapFactory.decodeFile(cacheFile.absolutePath) ?: run {
                cacheFile.delete()
                null
            }
        } catch (_: Exception) {
            cacheFile.delete()
            null
        }
    }

    private fun saveCachedTile(cacheFile: File, bitmap: Bitmap) {
        // JPEG cannot preserve alpha. Keep transparent tiles in memory instead of
        // corrupting PNG/WebP content or paying for expensive PNG compression.
        if (bitmap.hasAlpha()) return
        try {
            if (!tileCacheDir.exists() && !tileCacheDir.mkdirs()) return
            FileOutputStream(cacheFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            SsivTileCacheBudget.recordWrite(appContext, tileCacheDir, cacheFile.length())
        } catch (_: Exception) {
            cacheFile.delete()
        }
    }
}
