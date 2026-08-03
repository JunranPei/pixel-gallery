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
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
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
                val files = directory.listFiles()
                    ?.filter {
                        it.isFile &&
                            (it.extension == "argb8888" || it.extension == "jpg" || it.extension == "webp")
                    }
                    ?.sortedBy { it.lastModified() }
                    .orEmpty()
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
    private val imageVersion: String,
    private val knownSourceWidth: Int = 0,
    private val knownSourceHeight: Int = 0,
) : ImageRegionDecoder {
    private companion object {
        const val RAW_TILE_MAGIC = 0x50475854
        const val RAW_TILE_VERSION = 1
        const val RAW_TILE_HEADER_BYTES = 20
        const val ARGB_8888_BYTES_PER_PIXEL = 4
    }

    private var decoder: BitmapRegionDecoder? = null
    private var decoderInputStream: InputStream? = null
    private val decoderLock = Any()
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var sourceWidth = 0
    private var sourceHeight = 0
    private lateinit var tileCacheDir: File
    private lateinit var appContext: Context
    private lateinit var sourceUri: Uri
    private var initialized = false
    private var metricsKey: String = ""
    private var metricsSessionId: Long = 0L

    override fun init(context: Context, uri: Uri): Point {
        val initToken = ViewerLoadMetrics.workStarted(
            "REGION_DECODER_INIT",
            imageVersion,
            "uriScheme=${uri.scheme} minTileDpi=$minTileDpi",
        )
        appContext = context.applicationContext
        sourceUri = uri
        metricsKey = imageVersion
        metricsSessionId = ViewerLoadMetrics.currentSessionId(metricsKey)
        val displayMetrics = context.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        tileCacheDir = File(context.cacheDir, "ssiv_tile_cache")

        sourceWidth = knownSourceWidth
        sourceHeight = knownSourceHeight
        val source = if (sourceWidth > 0 && sourceHeight > 0) {
            "MEDIA_METADATA_LAZY"
        } else {
            val opened = openDecoder("metadata-missing")
            sourceWidth = opened.width
            sourceHeight = opened.height
            "BITMAP_REGION_DECODER"
        }
        initialized = true
        ViewerLoadMetrics.workReady(
            initToken,
            source = source,
            detail = "source=${sourceWidth}x$sourceHeight screen=${screenWidth}x$screenHeight " +
                "session=$metricsSessionId",
        )
        return Point(sourceWidth, sourceHeight)
    }

    override fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap {
        synchronized(decoderLock) {
            val decodeToken = ViewerLoadMetrics.workStarted(
                "REGION_TILE_REQUEST",
                imageVersion,
                "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize",
            )
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
            val cacheFiles = tileCacheFiles(rect, newSampleSize)
            val metricsEnabled = ViewerLoadMetrics.isEnabled
            val cacheReadStartedAt = if (metricsEnabled) SystemClock.elapsedRealtimeNanos() else 0L
            decodeCachedTile(cacheFiles)?.let { (cacheFile, cachedBitmap) ->
                if (metricsEnabled) {
                    ViewerLoadMetrics.cacheRead(
                        imageKey = metricsKey,
                        sessionId = metricsSessionId,
                        hit = true,
                        durationMs = (SystemClock.elapsedRealtimeNanos() - cacheReadStartedAt) / 1_000_000L
                    )
                }
                val attached = UltraHdrTileSupport.attach(
                    imageKey = imageVersion,
                    baseTile = cachedBitmap,
                    sourceRect = rect,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                )
                ViewerLoadMetrics.workReady(
                    decodeToken,
                    source = "TILE_DISK_CACHE",
                    detail = "actualSample=$newSampleSize bitmap=${attached.width}x${attached.height} " +
                        "config=${attached.config} bytes=${cacheFile.length()}",
                )
                return attached
            }
            if (metricsEnabled) {
                ViewerLoadMetrics.cacheRead(
                    imageKey = metricsKey,
                    sessionId = metricsSessionId,
                    hit = false,
                    durationMs = (SystemClock.elapsedRealtimeNanos() - cacheReadStartedAt) / 1_000_000L
                )
            }

            val decodeStartedAt = if (metricsEnabled) SystemClock.elapsedRealtimeNanos() else 0L
            val bitmap = openDecoder("tile-cache-miss").decodeRegion(rect, options)
                ?: throw RuntimeException("Region decoder returned null bitmap")
            if (metricsEnabled) {
                ViewerLoadMetrics.regionDecoded(
                    imageKey = metricsKey,
                    sessionId = metricsSessionId,
                    rect = "${rect.left},${rect.top}-${rect.right},${rect.bottom}",
                    requestedSample = sampleSize,
                    actualSample = newSampleSize,
                    outputPixels = bitmap.width.toLong() * bitmap.height.toLong(),
                    durationMs = (SystemClock.elapsedRealtimeNanos() - decodeStartedAt) / 1_000_000L
                )
            }
            val cacheWritten = saveCachedTile(cacheFiles, bitmap)
            val attached = UltraHdrTileSupport.attach(
                imageKey = imageVersion,
                baseTile = bitmap,
                sourceRect = rect,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            )
            ViewerLoadMetrics.workReady(
                decodeToken,
                source = "SOURCE_REGION_DECODE",
                detail = "actualSample=$newSampleSize bitmap=${attached.width}x${attached.height} " +
                    "config=${attached.config} cacheWrite=" +
                    if (cacheWritten) "ARGB_8888" else "SKIPPED",
            )
            return attached
        }
    }

    override fun isReady() = initialized && decoder?.isRecycled != true

    override fun recycle() {
        val token = ViewerLoadMetrics.workStarted(
            "REGION_DECODER_RECYCLE",
            imageVersion,
            "ready=${isReady()}",
        )
        synchronized(decoderLock) {
            decoder?.recycle()
            decoder = null
            decoderInputStream?.close()
            decoderInputStream = null
            initialized = false
        }
        ViewerLoadMetrics.workReady(token)
    }

    private fun openDecoder(reason: String): BitmapRegionDecoder {
        decoder?.takeIf { !it.isRecycled }?.let { return it }
        val token = ViewerLoadMetrics.workStarted(
            "REGION_DECODER_OPEN",
            imageVersion,
            "reason=$reason uriScheme=${sourceUri.scheme}",
        )
        try {
            val filePath = if (sourceUri.scheme == "file" || sourceUri.scheme == null) {
                val path = sourceUri.path ?: sourceUri.toString()
                File(path).takeIf { it.isFile && it.canRead() }?.absolutePath
            } else {
                null
            }
            @Suppress("DEPRECATION")
            val opened = if (filePath != null) {
                // The stable viewer opened a real path, allowing BitmapRegionDecoder to
                // perform native random access instead of reading through InputStream.
                decoderInputStream?.close()
                decoderInputStream = null
                BitmapRegionDecoder.newInstance(filePath, false)
            } else {
                val inputStream = appContext.contentResolver.openInputStream(sourceUri)
                    ?: throw IllegalStateException("Unable to open source URI")
                decoderInputStream = inputStream
                BitmapRegionDecoder.newInstance(inputStream, false)
            } ?: throw IllegalStateException("Unable to create region decoder")
            decoder = opened
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                sourceWidth = opened.width
                sourceHeight = opened.height
            } else if (sourceWidth != opened.width || sourceHeight != opened.height) {
                ViewerLoadMetrics.event(
                    "REGION_SOURCE_DIMENSION_MISMATCH",
                    "metadata=${sourceWidth}x$sourceHeight decoder=${opened.width}x${opened.height}",
                    imageKey = imageVersion,
                )
            }
            ViewerLoadMetrics.workReady(
                token,
                source = if (filePath != null) "BITMAP_REGION_DECODER_FILE" else "BITMAP_REGION_DECODER_STREAM",
                detail = "source=${opened.width}x${opened.height}",
            )
            return opened
        } catch (error: Exception) {
            decoderInputStream?.close()
            decoderInputStream = null
            ViewerLoadMetrics.workFailed(token, error.javaClass.simpleName)
            throw error
        }
    }

    private data class TileCacheFiles(val argb8888: File)

    private fun tileCacheFiles(rect: Rect, sampleSize: Int): TileCacheFiles {
        val key = "$imageVersion:${rect.left}:${rect.top}:${rect.right}:${rect.bottom}:$sampleSize:argb8888-v1"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return TileCacheFiles(
            argb8888 = File(tileCacheDir, "$digest.argb8888"),
        )
    }

    private fun decodeCachedTile(cacheFiles: TileCacheFiles): Pair<File, Bitmap>? {
        val cacheFile = cacheFiles.argb8888
        if (!cacheFile.isFile) return null
        val decoded = try {
            RandomAccessFile(cacheFile, "r").use { input ->
                val channel = input.channel
                if (channel.size() < RAW_TILE_HEADER_BYTES) return@use null
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
                val magic = mapped.int
                val version = mapped.int
                val width = mapped.int
                val height = mapped.int
                val pixelBytes = mapped.int
                val expectedBytes = width.toLong() * height.toLong() * ARGB_8888_BYTES_PER_PIXEL
                if (
                    magic != RAW_TILE_MAGIC ||
                    version != RAW_TILE_VERSION ||
                    width <= 0 ||
                    height <= 0 ||
                    pixelBytes.toLong() != expectedBytes ||
                    channel.size() != RAW_TILE_HEADER_BYTES + expectedBytes
                ) {
                    return@use null
                }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    mapped.position(RAW_TILE_HEADER_BYTES)
                    bitmap.copyPixelsFromBuffer(mapped.slice())
                }
            }
        } catch (_: Exception) {
            null
        }
        if (decoded == null) {
            cacheFile.delete()
            return null
        }
        cacheFile.setLastModified(System.currentTimeMillis())
        return cacheFile to decoded
    }

    private fun saveCachedTile(cacheFiles: TileCacheFiles, bitmap: Bitmap): Boolean {
        val cacheFile = cacheFiles.argb8888
        if (cacheFile.isFile) return false
        val token = ViewerLoadMetrics.workStarted(
            "TILE_CACHE_WRITE",
            metricsKey,
            "format=ARGB_8888 bitmap=${bitmap.width}x${bitmap.height}",
        )
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val tempFile = File(tileCacheDir, "${cacheFile.name}.${Thread.currentThread().id}.tmp")
        return try {
            if (!tileCacheDir.exists() && !tileCacheDir.mkdirs()) {
                throw IllegalStateException("Unable to create tile cache directory")
            }
            require(bitmap.config == Bitmap.Config.ARGB_8888) {
                "Expected ARGB_8888 tile, got ${bitmap.config}"
            }
            val pixelBytes = bitmap.width.toLong() * bitmap.height.toLong() * ARGB_8888_BYTES_PER_PIXEL
            val totalBytes = RAW_TILE_HEADER_BYTES + pixelBytes
            RandomAccessFile(tempFile, "rw").use { output ->
                output.setLength(totalBytes)
                val mapped = output.channel.map(FileChannel.MapMode.READ_WRITE, 0L, totalBytes)
                mapped.putInt(RAW_TILE_MAGIC)
                mapped.putInt(RAW_TILE_VERSION)
                mapped.putInt(bitmap.width)
                mapped.putInt(bitmap.height)
                mapped.putInt(pixelBytes.toInt())
                bitmap.copyPixelsToBuffer(mapped.slice())
                mapped.force()
            }
            if (cacheFile.isFile) {
                tempFile.delete()
                return false
            }
            if (!tempFile.renameTo(cacheFile)) {
                tempFile.copyTo(cacheFile, overwrite = false)
                tempFile.delete()
            }
            val bytes = cacheFile.length()
            SsivTileCacheBudget.recordWrite(appContext, tileCacheDir, bytes)
            if (ViewerLoadMetrics.currentSessionId(metricsKey) == metricsSessionId) {
                ViewerLoadMetrics.tileWritten(
                    imageKey = metricsKey,
                    sessionId = metricsSessionId,
                    durationMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L,
                    bytes = bytes,
                )
            }
            ViewerLoadMetrics.workReady(token, source = "ARGB_8888_DISK_CACHE", detail = "bytes=$bytes")
            true
        } catch (error: Exception) {
            tempFile.delete()
            cacheFile.delete()
            ViewerLoadMetrics.workFailed(token, error.javaClass.simpleName)
            false
        }
    }
}
