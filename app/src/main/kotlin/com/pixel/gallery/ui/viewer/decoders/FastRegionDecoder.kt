package com.pixel.gallery.ui.viewer.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.davemorrissey.labs.subscaleview.BatchedImageRegionDecoder
import com.davemorrissey.labs.subscaleview.RegionDecoderCapabilities
import com.pixel.gallery.BuildConfig
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import io.github.indexedjpeg.IndexedJpegRegionDecoder
import io.github.indexedjpeg.IndexedJpegOverviewRegionDecoder
import io.github.indexedjpeg.IndexedJpegStore
import io.github.indexedjpeg.IndexedJpegStatus
import io.github.indexedpng.IndexedPngRegionDecoder
import io.github.indexedpng.IndexedPngStore
import io.github.indexedpng.IndexedPngStatus
import io.github.indexedwebp.IndexedWebpRegionDecoder
import io.github.indexedwebp.IndexedWebpStore
import io.github.indexedwebp.IndexedWebpStatus
import io.github.indexedheif.IndexedHeifRegionDecoder
import io.github.indexedheif.IndexedHeifStore
import io.github.indexedheif.IndexedHeifStatus
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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val ssivTileCacheLock = ReentrantReadWriteLock(true)
private val ssivTileCacheGeneration = AtomicLong(0L)

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

fun clearSsivTileCache(context: Context): Boolean = ssivTileCacheLock.write {
    val appContext = context.applicationContext
    ssivTileCacheGeneration.incrementAndGet()
    val directory = File(appContext.cacheDir, "ssiv_tile_cache")
    val cleared = !directory.exists() || directory.deleteRecursively()
    SsivTileCacheBudget.reset(appContext)
    cleared
}

class FastRegionDecoder(
    private val minTileDpi: Int,
    private val imageVersion: String,
    private val indexedSourcePath: String? = null,
    private val knownSourceWidth: Int = 0,
    private val knownSourceHeight: Int = 0,
    private val coldTestMode: Boolean = false,
) : BatchedImageRegionDecoder {
    companion object {
        const val RAW_TILE_MAGIC = 0x50475854
        const val RAW_TILE_VERSION = 1
        const val RAW_TILE_HEADER_BYTES = 20
        const val ARGB_8888_BYTES_PER_PIXEL = 4

        /** Source/header validation only; checking this never builds an index. */
        internal fun hasReadyPersistentIndex(context: Context, sourcePath: String): Boolean {
            val source = File(sourcePath)
            if (!source.isFile || !source.canRead()) return false
            val appContext = context.applicationContext
            return IndexedJpegStore(appContext).status(sourcePath) is IndexedJpegStatus.Ready ||
                IndexedPngStore(appContext).status(sourcePath) is IndexedPngStatus.Ready ||
                IndexedWebpStore(appContext).status(sourcePath) is IndexedWebpStatus.Ready ||
                IndexedHeifStore(appContext).status(sourcePath) is IndexedHeifStatus.Ready
        }
    }

    private enum class IndexedBackend(val persistentTilePyramid: Boolean) {
        JPEG(false),
        PNG(true),
        WEBP(true),
        HEIF(true),
    }

    private var decoder: BitmapRegionDecoder? = null
    private var decoderInputStream: InputStream? = null
    private val decoderLock = Any()
    private val cacheWriteLock = Any()
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var sourceWidth = 0
    private var sourceHeight = 0
    private lateinit var tileCacheDir: File
    private var tileCacheGeneration = 0L
    private lateinit var appContext: Context
    private lateinit var sourceUri: Uri
    private var localSourcePath: String? = null
    private var indexedStore: IndexedJpegStore? = null
    private var indexedDecoder: IndexedJpegRegionDecoder? = null
    private var indexedOverviewDecoder: IndexedJpegOverviewRegionDecoder? = null
    private var indexedGeneration = Long.MIN_VALUE
    private var indexedOverviewGeneration = Long.MIN_VALUE
    private var indexedDecodeFailed = false
    private var indexedOverviewDecodeFailed = false
    private var indexedPngStore: IndexedPngStore? = null
    private var indexedPngDecoder: IndexedPngRegionDecoder? = null
    private var indexedPngGeneration = Long.MIN_VALUE
    private var indexedPngDecodeFailed = false
    private var indexedWebpStore: IndexedWebpStore? = null
    private var indexedWebpDecoder: IndexedWebpRegionDecoder? = null
    private var indexedWebpGeneration = Long.MIN_VALUE
    private var indexedWebpDecodeFailed = false
    private var indexedHeifStore: IndexedHeifStore? = null
    private var indexedHeifDecoder: IndexedHeifRegionDecoder? = null
    private var indexedHeifGeneration = Long.MIN_VALUE
    private var indexedHeifDecodeFailed = false
    @Volatile private var activeIndexedBackend: IndexedBackend? = null
    @Volatile private var resolvedCapabilityRevision = Long.MIN_VALUE
    @Volatile private var initialized = false
    private var coldDropSequence = 0L
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
        localSourcePath = if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path ?: uri.toString()
            File(path).takeIf { it.isFile && it.canRead() }?.absolutePath
        } else {
            null
        }
        indexedStore = IndexedJpegStore(appContext)
        indexedPngStore = IndexedPngStore(appContext)
        indexedWebpStore = IndexedWebpStore(appContext)
        indexedHeifStore = IndexedHeifStore(appContext)
        activeIndexedBackend = resolveIndexedBackend()
        resolvedCapabilityRevision = indexStoresRevision()
        metricsKey = imageVersion
        metricsSessionId = ViewerLoadMetrics.currentSessionId(metricsKey)
        val displayMetrics = context.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        tileCacheDir = File(context.cacheDir, "ssiv_tile_cache")
        tileCacheGeneration = ssivTileCacheGeneration.get()

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
            val useDecodedTileCache = capabilities(newSampleSize).persistDecodedTiles
            val cacheFiles = if (useDecodedTileCache) {
                tileCacheFiles(rect, newSampleSize)
            } else {
                null
            }
            val metricsEnabled = ViewerLoadMetrics.isEnabled
            val cacheReadStartedAt = if (metricsEnabled) SystemClock.elapsedRealtimeNanos() else 0L
            cacheFiles?.let(::decodeCachedTile)?.let { (cacheFile, cachedBitmap) ->
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
            if (metricsEnabled && useDecodedTileCache) {
                ViewerLoadMetrics.cacheRead(
                    imageKey = metricsKey,
                    sessionId = metricsSessionId,
                    hit = false,
                    durationMs = (SystemClock.elapsedRealtimeNanos() - cacheReadStartedAt) / 1_000_000L
                )
            }

            val decodeStartedAt = if (metricsEnabled) SystemClock.elapsedRealtimeNanos() else 0L
            val (bitmap, source) = decodeSourceRegion(rect, newSampleSize, options, "tile-cache-miss")
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
            val attached = UltraHdrTileSupport.attach(
                imageKey = imageVersion,
                baseTile = bitmap,
                sourceRect = rect,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            )
            ViewerLoadMetrics.workReady(
                decodeToken,
                source = source,
                detail = "actualSample=$newSampleSize bitmap=${attached.width}x${attached.height} " +
                    "config=${attached.config} cacheWrite=DEFERRED",
            )
            return attached
        }
    }

    override fun capabilityRevision(): Long = indexStoresRevision()

    override fun capabilities(sampleSize: Int): RegionDecoderCapabilities {
        return synchronized(decoderLock) {
            refreshBackendForCapabilityRevision()
            val addressableJpegTileSize = addressableJpegPyramidTileSize(sampleSize)
            val persistentTileSize = when {
                addressableJpegTileSize != null -> addressableJpegTileSize
                activeIndexedBackend == IndexedBackend.PNG -> IndexedPngStore.PREFERRED_DECODED_TILE_SIZE
                else -> null
            }
            val persistentPyramid =
                activeIndexedBackend?.persistentTilePyramid == true ||
                    addressableJpegTileSize != null
            RegionDecoderCapabilities(
                batchSourceMisses = !persistentPyramid,
                persistDecodedTiles = !coldTestMode && !persistentPyramid,
                preferredDecodedTileSize = persistentTileSize,
            )
        }
    }

    override fun isRegionCached(sRect: Rect, sampleSize: Int): Boolean {
        if (!initialized) return false
        val effectiveSample = effectiveSampleSize(sRect, sampleSize)
        if (!capabilities(effectiveSample).persistDecodedTiles) return false
        return tileCacheFiles(sRect, effectiveSample).argb8888.isFile
    }

    override fun decodeRegions(sRects: List<Rect>, sampleSize: Int): List<Bitmap> {
        require(sRects.isNotEmpty()) { "At least one source region is required" }
        if (sRects.size == 1) return listOf(decodeRegion(sRects.first(), sampleSize))
        val routedSamples = sRects.map { effectiveSampleSize(it, sampleSize) }
        if (
            routedSamples.distinct().size == 1 &&
            !capabilities(routedSamples.first()).batchSourceMisses
        ) {
            return sRects.map { decodeRegion(it, sampleSize) }
        }

        synchronized(decoderLock) {
            val actualSamples = routedSamples
            if (actualSamples.distinct().size != 1) {
                return sRects.map { decodeRegion(it, sampleSize) }
            }
            val actualSample = actualSamples.first()
            // A cache file may have appeared after SSIV built this batch. Preserve the
            // ordinary hit path instead of making that region wait on a source decode.
            if (sRects.any { tileCacheFiles(it, actualSample).argb8888.isFile }) {
                return sRects.map { decodeRegion(it, sampleSize) }
            }

            val union = Rect(sRects.first())
            sRects.drop(1).forEach(union::union)
            val batchToken = ViewerLoadMetrics.workStarted(
                "REGION_TILE_BATCH_REQUEST",
                imageVersion,
                "count=${sRects.size} rect=${union.left},${union.top}-${union.right},${union.bottom} " +
                    "sample=$sampleSize actualSample=$actualSample",
            )
            val options = BitmapFactory.Options().apply {
                inSampleSize = actualSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decodeStartedAt = SystemClock.elapsedRealtimeNanos()
            val (unionBitmap, source) = decodeSourceRegion(
                union,
                actualSample,
                options,
                "tile-batch-cache-miss",
            )
            val decodeDurationMs =
                (SystemClock.elapsedRealtimeNanos() - decodeStartedAt) / 1_000_000L
            if (ViewerLoadMetrics.isEnabled) {
                ViewerLoadMetrics.regionDecoded(
                    imageKey = metricsKey,
                    sessionId = metricsSessionId,
                    rect = "${union.left},${union.top}-${union.right},${union.bottom}",
                    requestedSample = sampleSize,
                    actualSample = actualSample,
                    outputPixels = unionBitmap.width.toLong() * unionBitmap.height.toLong(),
                    durationMs = decodeDurationMs,
                )
            }

            val splitBitmaps = ArrayList<Bitmap>(sRects.size)
            try {
                for (rect in sRects) {
                    val left = ((rect.left - union.left) / actualSample)
                        .coerceIn(0, unionBitmap.width - 1)
                    val top = ((rect.top - union.top) / actualSample)
                        .coerceIn(0, unionBitmap.height - 1)
                    val right = ceilDiv(rect.right - union.left, actualSample)
                        .coerceIn(left + 1, unionBitmap.width)
                    val bottom = ceilDiv(rect.bottom - union.top, actualSample)
                        .coerceIn(top + 1, unionBitmap.height)
                    splitBitmaps += Bitmap.createBitmap(
                        unionBitmap,
                        left,
                        top,
                        right - left,
                        bottom - top,
                    )
                }
            } catch (error: Throwable) {
                splitBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                ViewerLoadMetrics.workFailed(batchToken, error.javaClass.simpleName)
                throw error
            } finally {
                if (!unionBitmap.isRecycled) unionBitmap.recycle()
            }

            val attached = splitBitmaps.mapIndexed { index, bitmap ->
                val rect = sRects[index]
                UltraHdrTileSupport.attach(
                    imageKey = imageVersion,
                    baseTile = bitmap,
                    sourceRect = rect,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                )
            }
            ViewerLoadMetrics.workReady(
                batchToken,
                source = when (source) {
                    "INDEXED_JPEG_REGION_DECODE" -> "INDEXED_JPEG_REGION_BATCH_DECODE"
                    "INDEXED_PNG_REGION_DECODE" -> "INDEXED_PNG_REGION_BATCH_DECODE"
                    "INDEXED_WEBP_REGION_DECODE" -> "INDEXED_WEBP_REGION_BATCH_DECODE"
                    else -> "SOURCE_REGION_BATCH_DECODE"
                },
                detail = "count=${attached.size} actualSample=$actualSample decodeMs=$decodeDurationMs",
            )
            return attached
        }
    }

    override fun cacheRegion(sRect: Rect, sampleSize: Int, bitmap: Bitmap): Boolean {
        synchronized(cacheWriteLock) {
            if (!initialized || bitmap.isRecycled) return false
            val actualSample = effectiveSampleSize(sRect, sampleSize)
            if (!capabilities(actualSample).persistDecodedTiles) return true
            val cacheFiles = tileCacheFiles(sRect, actualSample)
            if (cacheFiles.argb8888.isFile) return true
            return saveCachedTile(cacheFiles, bitmap)
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
            indexedDecoder?.close()
            indexedDecoder = null
            indexedOverviewDecoder?.close()
            indexedOverviewDecoder = null
            indexedStore = null
            indexedGeneration = Long.MIN_VALUE
            indexedOverviewGeneration = Long.MIN_VALUE
            indexedDecodeFailed = false
            indexedOverviewDecodeFailed = false
            indexedPngDecoder?.close()
            indexedPngDecoder = null
            indexedPngStore = null
            indexedPngGeneration = Long.MIN_VALUE
            indexedPngDecodeFailed = false
            indexedWebpDecoder?.close()
            indexedWebpDecoder = null
            indexedWebpStore = null
            indexedWebpGeneration = Long.MIN_VALUE
            indexedWebpDecodeFailed = false
            indexedHeifDecoder?.close()
            indexedHeifDecoder = null
            indexedHeifStore = null
            indexedHeifGeneration = Long.MIN_VALUE
            indexedHeifDecodeFailed = false
            activeIndexedBackend = null
            resolvedCapabilityRevision = Long.MIN_VALUE
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

    private fun effectiveSampleSize(rect: Rect, sampleSize: Int): Int {
        var effective = sampleSize
        if (minTileDpi <= 160) {
            val sourceAndScreenHaveSameOrientation =
                (rect.width() > rect.height() && screenWidth > screenHeight) ||
                    (rect.height() > rect.width() && screenHeight > screenWidth)
            if (
                sourceAndScreenHaveSameOrientation &&
                (rect.width() / sampleSize > screenWidth || rect.height() / sampleSize > screenHeight)
            ) {
                effective *= 2
            }
        }
        return effective
    }

    private fun decodeSourceRegion(
        rect: Rect,
        sampleSize: Int,
        options: BitmapFactory.Options,
        fallbackReason: String,
    ): Pair<Bitmap, String> {
        prepareColdSourceDecode()
        ViewerLoadMetrics.event(
            "REGION_SOURCE_ROUTE",
            "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize " +
                "fallback=$fallbackReason indexedPath=${indexedSourcePath != null} " +
                "jpeg=open:${indexedDecoder != null},failed:$indexedDecodeFailed,generation:$indexedGeneration " +
                "png=open:${indexedPngDecoder != null},failed:$indexedPngDecodeFailed,generation:$indexedPngGeneration " +
                "webp=open:${indexedWebpDecoder != null},failed:$indexedWebpDecodeFailed,generation:$indexedWebpGeneration " +
                "heif=open:${indexedHeifDecoder != null},failed:$indexedHeifDecodeFailed,generation:$indexedHeifGeneration",
            imageKey = imageVersion,
        )
        if (sampleSize >= 2) {
            refreshIndexedOverviewDecoder()?.let { overview ->
                if (overview.supports(rect, sampleSize)) {
                    val startedAt = if (ViewerLoadMetrics.isEnabled) {
                        SystemClock.elapsedRealtimeNanos()
                    } else {
                        0L
                    }
                    val bitmap = try {
                        overview.decodeRegion(rect, sampleSize)
                    } catch (error: Throwable) {
                        ViewerLoadMetrics.event(
                            "INDEXED_JPEG_OVERVIEW_DECODE_ERROR",
                            "sample=$sampleSize error=${error.javaClass.simpleName}:${error.message}",
                            imageKey = imageVersion,
                        )
                        null
                    }
                    if (bitmap != null) {
                        if (ViewerLoadMetrics.isEnabled) {
                            ViewerLoadMetrics.event(
                                "INDEXED_JPEG_OVERVIEW_REGION_DECODE",
                                "sample=$sampleSize layerSample=${overview.layerSampleSize(sampleSize)} " +
                                    "storage=${if (overview.isAddressableTiled) "ADDRESSABLE" else "WHOLE_LAYER"} " +
                                    "blocks=${overview.addressableTileCount(rect, sampleSize) ?: 0} " +
                                    "bitmap=${bitmap.width}x${bitmap.height} duration=" +
                                    "${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L}ms",
                                imageKey = imageVersion,
                            )
                        }
                        return bitmap to "INDEXED_JPEG_OVERVIEW_REGION_DECODE"
                    }
                    overview.close()
                    indexedOverviewDecoder = null
                    indexedOverviewDecodeFailed = true
                }
            }
        }
        refreshIndexedDecoder()?.let { indexed ->
            val startedAt = if (ViewerLoadMetrics.isEnabled) {
                SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }
            val bitmap = try {
                indexed.decodeRegion(rect, sampleSize)
            } catch (error: Throwable) {
                ViewerLoadMetrics.event(
                    "INDEXED_JPEG_DECODE_ERROR",
                    "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize " +
                        "error=${error.javaClass.simpleName}:${error.message}",
                    imageKey = imageVersion,
                )
                null
            }
            if (bitmap != null) {
                if (ViewerLoadMetrics.isEnabled) {
                    ViewerLoadMetrics.event(
                        "INDEXED_JPEG_REGION_DECODE",
                        "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} " +
                            "sample=$sampleSize bitmap=${bitmap.width}x${bitmap.height} " +
                            "duration=${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L}ms",
                        imageKey = imageVersion,
                    )
                }
                return bitmap to "INDEXED_JPEG_REGION_DECODE"
            }
            indexed.close()
            indexedDecoder = null
            indexedDecodeFailed = true
            if (activeIndexedBackend == IndexedBackend.JPEG) activeIndexedBackend = null
            ViewerLoadMetrics.event(
                "INDEXED_JPEG_FALLBACK",
                "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize",
                imageKey = imageVersion,
            )
        }

        refreshIndexedPngDecoder()?.let { indexed ->
            val startedAt = if (ViewerLoadMetrics.isEnabled) {
                SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }
            val bitmap = try {
                indexed.decodeRegion(rect, sampleSize)
            } catch (error: Throwable) {
                ViewerLoadMetrics.event(
                    "INDEXED_PNG_DECODE_ERROR",
                    "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize " +
                        "error=${error.javaClass.simpleName}:${error.message}",
                    imageKey = imageVersion,
                )
                null
            }
            if (bitmap != null) {
                if (ViewerLoadMetrics.isEnabled) {
                    ViewerLoadMetrics.event(
                        "INDEXED_PNG_REGION_DECODE",
                        "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} " +
                            "sample=$sampleSize bitmap=${bitmap.width}x${bitmap.height} " +
                            "duration=${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L}ms",
                        imageKey = imageVersion,
                    )
                }
                return bitmap to "INDEXED_PNG_REGION_DECODE"
            }
            indexed.close()
            indexedPngDecoder = null
            indexedPngDecodeFailed = true
            if (activeIndexedBackend == IndexedBackend.PNG) activeIndexedBackend = null
            ViewerLoadMetrics.event(
                "INDEXED_PNG_FALLBACK",
                "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize",
                imageKey = imageVersion,
            )
        }

        refreshIndexedWebpDecoder()?.let { indexed ->
            val startedAt = if (ViewerLoadMetrics.isEnabled) {
                SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }
            val bitmap = try {
                indexed.decodeRegion(rect, sampleSize)
            } catch (error: Throwable) {
                ViewerLoadMetrics.event(
                    "INDEXED_WEBP_DECODE_ERROR",
                    "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize " +
                        "error=${error.javaClass.simpleName}:${error.message}",
                    imageKey = imageVersion,
                )
                null
            }
            if (bitmap != null) {
                if (ViewerLoadMetrics.isEnabled) {
                    ViewerLoadMetrics.event(
                        "INDEXED_WEBP_REGION_DECODE",
                        "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} " +
                            "sample=$sampleSize bitmap=${bitmap.width}x${bitmap.height} " +
                            "duration=${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L}ms",
                        imageKey = imageVersion,
                    )
                }
                return bitmap to "INDEXED_WEBP_REGION_DECODE"
            }
            indexed.close()
            indexedWebpDecoder = null
            indexedWebpDecodeFailed = true
            if (activeIndexedBackend == IndexedBackend.WEBP) activeIndexedBackend = null
            ViewerLoadMetrics.event(
                "INDEXED_WEBP_FALLBACK",
                "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize",
                imageKey = imageVersion,
            )
        }

        refreshIndexedHeifDecoder()?.let { indexed ->
            val startedAt = if (ViewerLoadMetrics.isEnabled) {
                SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }
            val bitmap = try {
                indexed.decodeRegion(rect, sampleSize)
            } catch (error: Throwable) {
                ViewerLoadMetrics.event(
                    "INDEXED_HEIF_DECODE_ERROR",
                    "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize " +
                        "error=${error.javaClass.simpleName}:${error.message}",
                    imageKey = imageVersion,
                )
                null
            }
            if (bitmap != null) {
                if (ViewerLoadMetrics.isEnabled) {
                    ViewerLoadMetrics.event(
                        "INDEXED_HEIF_REGION_DECODE",
                        "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} " +
                            "sample=$sampleSize bitmap=${bitmap.width}x${bitmap.height} " +
                            "duration=${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L}ms",
                        imageKey = imageVersion,
                    )
                }
                return bitmap to "INDEXED_HEIF_REGION_DECODE"
            }
            indexed.close()
            indexedHeifDecoder = null
            indexedHeifDecodeFailed = true
            if (activeIndexedBackend == IndexedBackend.HEIF) activeIndexedBackend = null
            ViewerLoadMetrics.event(
                "INDEXED_HEIF_FALLBACK",
                "rect=${rect.left},${rect.top}-${rect.right},${rect.bottom} sample=$sampleSize",
                imageKey = imageVersion,
            )
        }

        val bitmap = openDecoder(fallbackReason).decodeRegion(rect, options)
            ?: throw RuntimeException("Region decoder returned null bitmap")
        return bitmap to "SOURCE_REGION_DECODE"
    }

    private fun resolveIndexedBackend(): IndexedBackend? {
        if (refreshIndexedDecoder() != null) return IndexedBackend.JPEG
        if (refreshIndexedPngDecoder() != null) return IndexedBackend.PNG
        if (refreshIndexedWebpDecoder() != null) return IndexedBackend.WEBP
        if (refreshIndexedHeifDecoder() != null) return IndexedBackend.HEIF
        return null
    }

    private fun indexStoresRevision(): Long {
        val sourcePath = indexedSourcePath
        var revision = if (sourcePath != null) {
            indexedStore?.currentGenerationFor(sourcePath) ?: 0L
        } else {
            0L
        }
        revision = revision * 31L + if (sourcePath != null) {
            indexedPngStore?.currentGenerationFor(sourcePath) ?: 0L
        } else {
            0L
        }
        revision = revision * 31L + (indexedWebpStore?.currentGeneration ?: 0L)
        revision = revision * 31L + (indexedHeifStore?.currentGeneration ?: 0L)
        return revision
    }

    private fun refreshBackendForCapabilityRevision() {
        val revision = indexStoresRevision()
        if (revision == resolvedCapabilityRevision) return
        activeIndexedBackend = resolveIndexedBackend()
        resolvedCapabilityRevision = revision
        ViewerLoadMetrics.event(
            "INDEX_CAPABILITY_REVISION",
            "revision=$revision backend=${activeIndexedBackend?.name ?: "NONE"}",
            imageKey = imageVersion,
        )
    }

    private fun refreshIndexedDecoder(): IndexedJpegRegionDecoder? {
        val store = indexedStore ?: return null
        val sourcePath = indexedSourcePath ?: return null
        val generation = store.currentGenerationFor(sourcePath)
        if (indexedGeneration != generation) {
            ViewerLoadMetrics.event(
                "INDEX_GENERATION_CHANGE",
                "format=JPEG from=$indexedGeneration to=$generation",
                imageKey = imageVersion,
            )
            indexedDecoder?.close()
            indexedDecoder = null
            indexedGeneration = generation
            indexedDecodeFailed = false
            if (activeIndexedBackend == IndexedBackend.JPEG) activeIndexedBackend = null
        }
        if (indexedDecodeFailed) {
            ViewerLoadMetrics.event("INDEX_BYPASS", "format=JPEG reason=previous-failure", imageKey = imageVersion)
            return null
        }
        indexedDecoder?.let {
            activeIndexedBackend = IndexedBackend.JPEG
            ViewerLoadMetrics.event("INDEX_REUSE", "format=JPEG generation=$generation", imageKey = imageVersion)
            return it
        }
        indexedDecoder = try {
            store.openDecoder(sourcePath)
        } catch (_: Throwable) {
            null
        }
        if (indexedDecoder == null) indexedDecodeFailed = true
        if (indexedDecoder != null) activeIndexedBackend = IndexedBackend.JPEG
        logIndexOpen("JPEG", sourcePath, indexedDecoder != null)
        return indexedDecoder
    }

    private fun refreshIndexedOverviewDecoder(): IndexedJpegOverviewRegionDecoder? {
        val store = indexedStore ?: return null
        val sourcePath = indexedSourcePath ?: return null
        val generation = store.currentGenerationFor(sourcePath)
        if (indexedOverviewGeneration != generation) {
            indexedOverviewDecoder?.close()
            indexedOverviewDecoder = null
            indexedOverviewGeneration = generation
            indexedOverviewDecodeFailed = false
        }
        if (indexedOverviewDecodeFailed) return null
        indexedOverviewDecoder?.let { return it }
        indexedOverviewDecoder = try {
            store.openOverviewDecoder(sourcePath)
        } catch (_: Throwable) {
            null
        }
        if (indexedOverviewDecoder == null) indexedOverviewDecodeFailed = true
        ViewerLoadMetrics.event(
            "INDEX_OPEN",
            "format=JPEG_PYRAMID result=${if (indexedOverviewDecoder != null) "HIT" else "MISS"} " +
                "storage=${if (indexedOverviewDecoder?.isAddressableTiled == true) "ADDRESSABLE" else "WHOLE_LAYER"} " +
                "layers=${indexedOverviewDecoder?.availableSampleSizes?.joinToString() ?: "none"}",
            imageKey = imageVersion,
        )
        if (BuildConfig.INDEXED_IMAGE_DIAGNOSTICS_ENABLED) {
            Log.i(
                "IndexedImageDecode",
                "INDEX_OPEN format=JPEG_OVERVIEW result=" +
                    (if (indexedOverviewDecoder != null) "HIT" else "MISS"),
            )
        }
        return indexedOverviewDecoder
    }

    private fun addressableJpegPyramidTileSize(sampleSize: Int): Int? {
        if (sampleSize < 2 || activeIndexedBackend != IndexedBackend.JPEG) return null
        return synchronized(decoderLock) {
            refreshIndexedOverviewDecoder()?.let { overview ->
                if (overview.isAddressableTiled) {
                    overview.addressableTileSize(sampleSize)
                } else {
                    null
                }
            }
        }
    }

    private fun refreshIndexedPngDecoder(): IndexedPngRegionDecoder? {
        val store = indexedPngStore ?: return null
        val sourcePath = indexedSourcePath ?: return null
        val generation = store.currentGenerationFor(sourcePath)
        if (indexedPngGeneration != generation) {
            ViewerLoadMetrics.event(
                "INDEX_GENERATION_CHANGE",
                "format=PNG from=$indexedPngGeneration to=$generation",
                imageKey = imageVersion,
            )
            indexedPngDecoder?.close()
            indexedPngDecoder = null
            indexedPngGeneration = generation
            indexedPngDecodeFailed = false
            if (activeIndexedBackend == IndexedBackend.PNG) activeIndexedBackend = null
        }
        if (indexedPngDecodeFailed) {
            ViewerLoadMetrics.event("INDEX_BYPASS", "format=PNG reason=previous-failure", imageKey = imageVersion)
            return null
        }
        indexedPngDecoder?.let {
            activeIndexedBackend = IndexedBackend.PNG
            ViewerLoadMetrics.event("INDEX_REUSE", "format=PNG generation=$generation", imageKey = imageVersion)
            return it
        }
        indexedPngDecoder = try {
            store.openDecoder(sourcePath)
        } catch (_: Throwable) {
            null
        }
        if (indexedPngDecoder == null) indexedPngDecodeFailed = true
        if (indexedPngDecoder != null) activeIndexedBackend = IndexedBackend.PNG
        logIndexOpen("PNG", sourcePath, indexedPngDecoder != null)
        return indexedPngDecoder
    }

    private fun refreshIndexedWebpDecoder(): IndexedWebpRegionDecoder? {
        val store = indexedWebpStore ?: return null
        val sourcePath = indexedSourcePath ?: return null
        val generation = store.currentGeneration
        if (indexedWebpGeneration != generation) {
            ViewerLoadMetrics.event(
                "INDEX_GENERATION_CHANGE",
                "format=WEBP from=$indexedWebpGeneration to=$generation",
                imageKey = imageVersion,
            )
            indexedWebpDecoder?.close()
            indexedWebpDecoder = null
            indexedWebpGeneration = generation
            indexedWebpDecodeFailed = false
            if (activeIndexedBackend == IndexedBackend.WEBP) activeIndexedBackend = null
        }
        if (indexedWebpDecodeFailed) {
            ViewerLoadMetrics.event("INDEX_BYPASS", "format=WEBP reason=previous-failure", imageKey = imageVersion)
            return null
        }
        indexedWebpDecoder?.let {
            activeIndexedBackend = IndexedBackend.WEBP
            ViewerLoadMetrics.event("INDEX_REUSE", "format=WEBP generation=$generation", imageKey = imageVersion)
            return it
        }
        indexedWebpDecoder = try {
            store.openDecoder(sourcePath)
        } catch (_: Throwable) {
            null
        }
        if (indexedWebpDecoder == null) indexedWebpDecodeFailed = true
        if (indexedWebpDecoder != null) activeIndexedBackend = IndexedBackend.WEBP
        logIndexOpen("WEBP", sourcePath, indexedWebpDecoder != null)
        return indexedWebpDecoder
    }

    private fun refreshIndexedHeifDecoder(): IndexedHeifRegionDecoder? {
        val store = indexedHeifStore ?: return null
        val sourcePath = indexedSourcePath ?: return null
        val generation = store.currentGeneration
        if (indexedHeifGeneration != generation) {
            ViewerLoadMetrics.event(
                "INDEX_GENERATION_CHANGE",
                "format=HEIF_AVIF from=$indexedHeifGeneration to=$generation",
                imageKey = imageVersion,
            )
            indexedHeifDecoder?.close()
            indexedHeifDecoder = null
            indexedHeifGeneration = generation
            indexedHeifDecodeFailed = false
            if (activeIndexedBackend == IndexedBackend.HEIF) activeIndexedBackend = null
        }
        if (indexedHeifDecodeFailed) {
            ViewerLoadMetrics.event("INDEX_BYPASS", "format=HEIF_AVIF reason=previous-failure", imageKey = imageVersion)
            return null
        }
        indexedHeifDecoder?.let {
            activeIndexedBackend = IndexedBackend.HEIF
            ViewerLoadMetrics.event("INDEX_REUSE", "format=HEIF_AVIF generation=$generation", imageKey = imageVersion)
            return it
        }
        indexedHeifDecoder = try {
            store.openDecoder(sourcePath)
        } catch (_: Throwable) {
            null
        }
        if (indexedHeifDecoder == null) indexedHeifDecodeFailed = true
        if (indexedHeifDecoder != null) activeIndexedBackend = IndexedBackend.HEIF
        logIndexOpen("HEIF_AVIF", sourcePath, indexedHeifDecoder != null)
        return indexedHeifDecoder
    }

    private fun logIndexOpen(format: String, sourcePath: String, hit: Boolean) {
        ViewerLoadMetrics.event(
            "INDEX_OPEN",
            "format=$format result=${if (hit) "HIT" else "MISS"} " +
                "source=${File(sourcePath).name} " +
                "decodeSource=${localSourcePath?.let(::File)?.name ?: "none"}",
            imageKey = imageVersion,
        )
        if (!BuildConfig.INDEXED_IMAGE_DIAGNOSTICS_ENABLED) return
        Log.i(
            "IndexedImageDecode",
            "INDEX_OPEN format=$format result=${if (hit) "HIT" else "MISS"} " +
                "source=${File(sourcePath).name} decodeSource=${localSourcePath?.let(::File)?.name ?: "none"}",
        )
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

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

    private fun saveCachedTile(cacheFiles: TileCacheFiles, bitmap: Bitmap): Boolean = ssivTileCacheLock.read {
        if (tileCacheGeneration != ssivTileCacheGeneration.get()) return@read true
        val cacheFile = cacheFiles.argb8888
        if (cacheFile.isFile) return@read false
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
            }
            if (cacheFile.isFile) {
                tempFile.delete()
                return@read false
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

    private data class ColdDropSummary(
        val adviceAccepted: Boolean,
        val residencyVerified: Boolean,
        val totalPages: Long,
        val residentBefore: Long,
        val residentAfter: Long,
    )

    /**
     * Test-only zero-reuse path. Close every live decoder first so no mmap or descriptor
     * owned by this viewer can pin the source/index, then reclaim clean file pages before
     * every source decode. Residency is sampled on the first and every sixteenth request;
     * doing a whole-file mincore scan for every 1024px tile would itself contaminate the
     * power measurement we are trying to isolate.
     */
    private fun prepareColdSourceDecode() {
        if (!coldTestMode) return
        val totalStartedAt = SystemClock.elapsedRealtimeNanos()
        coldDropSequence += 1L
        val closeStartedAt = SystemClock.elapsedRealtimeNanos()
        closeDecodersForColdRead()
        val closeDurationNanos = SystemClock.elapsedRealtimeNanos() - closeStartedAt
        val sourcePath = indexedSourcePath
        val backend = activeIndexedBackend
        val verifyResidency = coldDropSequence == 1L || coldDropSequence % 16L == 0L
        val adviceStartedAt = SystemClock.elapsedRealtimeNanos()
        val result = try {
            when (backend) {
                IndexedBackend.JPEG -> sourcePath?.let { path ->
                    indexedStore?.requestColdRead(path, verifyResidency)?.let { report ->
                        ColdDropSummary(
                            adviceAccepted = report.adviceAccepted,
                            residencyVerified = report.residencyVerified,
                            totalPages = report.totalPages,
                            residentBefore = report.residentBefore,
                            residentAfter = report.residentAfter,
                        )
                    }
                }
                IndexedBackend.PNG -> sourcePath?.let { path ->
                    indexedPngStore?.requestColdRead(path, verifyResidency)?.let { report ->
                        ColdDropSummary(
                            adviceAccepted = report.adviceAccepted,
                            residencyVerified = report.residencyVerified,
                            totalPages = report.totalPages,
                            residentBefore = report.residentBefore,
                            residentAfter = report.residentAfter,
                        )
                    }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
        val adviceDurationNanos = SystemClock.elapsedRealtimeNanos() - adviceStartedAt
        val totalDurationNanos = SystemClock.elapsedRealtimeNanos() - totalStartedAt
        ViewerLoadMetrics.event(
            "COLD_TEST_EVICT",
            "sequence=$coldDropSequence backend=${backend?.name ?: "NONE"} " +
                "source=${sourcePath?.let(::File)?.name ?: "none"} " +
                "dropAdviceAccepted=${result?.adviceAccepted == true} " +
                "residencyVerified=${result?.residencyVerified == true} " +
                "residentPages=${result?.residentBefore ?: -1}->${result?.residentAfter ?: -1}" +
                "/${result?.totalPages ?: -1} decodedTileDiskCache=false " +
                "decoderReuse=false closeUs=${closeDurationNanos / 1_000L} " +
                "adviceUs=${adviceDurationNanos / 1_000L} totalUs=${totalDurationNanos / 1_000L}",
            imageKey = imageVersion,
        )
    }

    private fun closeDecodersForColdRead() {
        decoder?.recycle()
        decoder = null
        decoderInputStream?.close()
        decoderInputStream = null
        indexedDecoder?.close()
        indexedDecoder = null
        indexedOverviewDecoder?.close()
        indexedOverviewDecoder = null
        indexedPngDecoder?.close()
        indexedPngDecoder = null
        indexedWebpDecoder?.close()
        indexedWebpDecoder = null
        indexedHeifDecoder?.close()
        indexedHeifDecoder = null
    }
}
