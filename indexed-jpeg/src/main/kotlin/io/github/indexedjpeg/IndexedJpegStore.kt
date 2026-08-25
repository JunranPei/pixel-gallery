package io.github.indexedjpeg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

const val INDEXED_JPEG_ADDRESSABLE_FORMAT_VERSION = 7

enum class IndexedJpegPyramidType {
    SEEK_ONLY,
    FIT_PREVIEW,
    WHOLE_JPEG_LAYERS,
    ADDRESSABLE_TILES,
}

sealed interface IndexedJpegStatus {
    data object Absent : IndexedJpegStatus
    data class Ready(
        val bytes: Long,
        val formatVersion: Int,
        val pyramidType: IndexedJpegPyramidType,
        val pyramidLayerCount: Int,
    ) : IndexedJpegStatus {
        val hasAddressablePyramid: Boolean
            get() = pyramidType == IndexedJpegPyramidType.ADDRESSABLE_TILES

        val canUpgradeToAddressablePyramid: Boolean
            get() = formatVersion < INDEXED_JPEG_ADDRESSABLE_FORMAT_VERSION
    }
    data class Unsupported(val reason: String) : IndexedJpegStatus
    data class Invalid(val reason: String) : IndexedJpegStatus
}

data class IndexedJpegInfo(
    val indexBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scanCount: Int,
    val overviewBytes: Long = 0L,
    val overviewWidth: Int = 0,
    val overviewHeight: Int = 0,
    val overviewSampleSize: Int = 0,
    val pyramidLayerCount: Int = 0,
    val formatVersion: Int = INDEXED_JPEG_ADDRESSABLE_FORMAT_VERSION,
    val pyramidType: IndexedJpegPyramidType = if (pyramidLayerCount > 0) {
        IndexedJpegPyramidType.ADDRESSABLE_TILES
    } else {
        IndexedJpegPyramidType.SEEK_ONLY
    },
)

data class IndexedJpegPyramidLayer(
    val sampleSize: Int,
    val width: Int,
    val height: Int,
    val bytes: Int,
    val tileSize: Int = 0,
)

private data class IndexedJpegPyramidMetadata(
    val formatVersion: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val payloadBytes: Int,
    val layers: List<IndexedJpegPyramidLayer>,
)

data class IndexedJpegOverviewSize(
    val width: Int,
    val height: Int,
)

data class IndexedJpegChange(
    val sourcePath: String,
    val generation: Long,
)

/**
 * Persistent, opt-in JPEG seek-index storage.
 *
 * Creating this class and opening images never builds an index. [build] is the
 * only entry point that performs the one-time full entropy scan.
 */
class IndexedJpegStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.noBackupFilesDir, DIRECTORY_NAME)

    fun status(sourcePath: String): IndexedJpegStatus {
        cleanupStaleTemporaryFiles()
        val source = supportedSource(sourcePath)
            ?: return IndexedJpegStatus.Unsupported("A readable local JPEG file is required")
        val index = indexFile(source)
        if (!index.isFile) return IndexedJpegStatus.Absent
        if (!IndexedJpegNative.validateIndex(
                index.absolutePath,
                source.length(),
                source.lastModified(),
            )
        ) {
            return IndexedJpegStatus.Invalid("The image changed or the index is incomplete")
        }
        val metadata = IndexedJpegNative.readIndexMetadata(
            index.absolutePath,
            source.length(),
            source.lastModified(),
        )
        if (metadata == null || metadata.size < INDEX_METADATA_SIZE) {
            return IndexedJpegStatus.Invalid("The JPEG index metadata is unreadable")
        }
        val formatVersion = metadata[0]
        val overviewBytes = metadata[6]
        val pyramid = if (
            formatVersion >= MULTI_LAYER_PYRAMID_FORMAT_VERSION && overviewBytes > 0
        ) {
            readPyramidMetadata(index, source)
                ?: return IndexedJpegStatus.Invalid("The JPEG pyramid directory is incomplete")
        } else {
            null
        }
        val pyramidType = when {
            formatVersion >= ADDRESSABLE_PYRAMID_FORMAT_VERSION && pyramid != null ->
                IndexedJpegPyramidType.ADDRESSABLE_TILES
            formatVersion >= MULTI_LAYER_PYRAMID_FORMAT_VERSION && pyramid != null ->
                IndexedJpegPyramidType.WHOLE_JPEG_LAYERS
            formatVersion == SINGLE_LAYER_OVERVIEW_FORMAT_VERSION && overviewBytes > 0 ->
                IndexedJpegPyramidType.WHOLE_JPEG_LAYERS
            formatVersion == FIT_OVERVIEW_FORMAT_VERSION && overviewBytes > 0 ->
                IndexedJpegPyramidType.FIT_PREVIEW
            else -> IndexedJpegPyramidType.SEEK_ONLY
        }
        return IndexedJpegStatus.Ready(
            bytes = index.length(),
            formatVersion = formatVersion,
            pyramidType = pyramidType,
            pyramidLayerCount = pyramid?.layers?.size
                ?: if (pyramidType == IndexedJpegPyramidType.WHOLE_JPEG_LAYERS) 1 else 0,
        )
    }

    @Throws(IOException::class)
    fun build(sourcePath: String): IndexedJpegInfo =
        buildWithViewport(sourcePath, maximumViewportSize())

    /** Test-only entry point that makes the target viewport deterministic. */
    internal fun buildForViewport(
        sourcePath: String,
        viewportWidth: Int,
        viewportHeight: Int,
    ): IndexedJpegInfo = buildWithViewport(
        sourcePath,
        IndexedJpegOverviewSize(viewportWidth, viewportHeight),
    )

    private fun buildWithViewport(
        sourcePath: String,
        viewport: IndexedJpegOverviewSize,
    ): IndexedJpegInfo = synchronized(mutationLock) {
        val source = supportedSource(sourcePath)
            ?: throw IOException("A readable local JPEG file is required")
        if (viewport.width <= 0 || viewport.height <= 0) {
            throw IOException("A positive JPEG overview viewport is required")
        }
        if (!buildInProgress.compareAndSet(false, true)) {
            throw IOException("Another JPEG index build is already running")
        }
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Unable to create the JPEG index directory")
            }
            cleanupStaleTemporaryFiles(force = true)
            val destination = indexFile(source)
            val temporary = File(directory, destination.name + ".tmp-${System.nanoTime()}")
            try {
                // The DC layer is reconstructed as sRGB. Preserve wide-gamut JPEGs by keeping
                // their index seek-only and letting Android's color-managed decoder draw fit view.
                val canEmbedOverview = sourceHasSrgbOutput(source)
                val nativeInfo = IndexedJpegNative.buildIndex(
                    source.absolutePath,
                    temporary.absolutePath,
                    source.length(),
                    source.lastModified(),
                    if (canEmbedOverview) viewport.width else 0,
                    if (canEmbedOverview) viewport.height else 0,
                )
                if (nativeInfo.size < 7 || nativeInfo[0] <= 0 || nativeInfo[1] <= 0) {
                    throw IOException("The native index builder returned invalid metadata")
                }
                if (!temporary.isFile || temporary.length() <= 0L) {
                    throw IOException("The native index writer produced no data")
                }
                publishCompletedFile(temporary, destination)
                legacyOverviewFile(source).delete()
                notifyIndexChanged(source.absolutePath)
                return IndexedJpegInfo(
                    indexBytes = destination.length(),
                    sourceWidth = nativeInfo[0],
                    sourceHeight = nativeInfo[1],
                    scanCount = nativeInfo[2],
                    overviewBytes = nativeInfo[3].toLong(),
                    overviewWidth = nativeInfo[4],
                    overviewHeight = nativeInfo[5],
                    overviewSampleSize = nativeInfo[6],
                    pyramidLayerCount = nativeInfo.getOrElse(7) { 0 },
                )
            } finally {
                temporary.delete()
            }
        } finally {
            buildInProgress.set(false)
        }
    }

    fun delete(sourcePath: String): Boolean = synchronized(mutationLock) {
        if (!buildInProgress.compareAndSet(false, true)) return false
        return try {
            val source = File(sourcePath)
            val hadIndex = indexFile(source).exists() || legacyOverviewFile(source).exists()
            val indexDeleted = indexFile(source).let { !it.exists() || it.delete() }
            val overviewDeleted = legacyOverviewFile(source).let { !it.exists() || it.delete() }
            val deleted = indexDeleted && overviewDeleted
            if (deleted && hadIndex) notifyIndexChanged(source.absolutePath)
            deleted
        } finally {
            buildInProgress.set(false)
        }
    }

    fun relocate(sourcePath: String, destinationPath: String): Boolean = synchronized(mutationLock) {
        if (sourcePath == destinationPath) return true
        if (!buildInProgress.compareAndSet(false, true)) return false
        return try {
            val sourceIndex = indexFile(File(sourcePath))
            if (!sourceIndex.isFile) return true
            val destinationSource = supportedSource(destinationPath) ?: return false
            val destinationIndex = indexFile(destinationSource)
            if (sourceIndex.absolutePath == destinationIndex.absolutePath) return true
            if (!IndexedJpegNative.validateIndex(
                    sourceIndex.absolutePath,
                    destinationSource.length(),
                    destinationSource.lastModified(),
                )
            ) {
                return false
            }
            try {
                publishCompletedFile(sourceIndex, destinationIndex)
            } catch (_: IOException) {
                return false
            }
            legacyOverviewFile(File(sourcePath)).delete()
            legacyOverviewFile(destinationSource).delete()
            notifyIndexChanged(File(sourcePath).absolutePath)
            notifyIndexChanged(destinationSource.absolutePath)
            true
        } finally {
            buildInProgress.set(false)
        }
    }

    fun openDecoder(sourcePath: String): IndexedJpegRegionDecoder? {
        val source = supportedSource(sourcePath) ?: return null
        val index = indexFile(source)
        if (!index.isFile) return null
        val handle = IndexedJpegNative.open(
            source.absolutePath,
            index.absolutePath,
            source.length(),
            source.lastModified(),
        )
        return handle.takeIf { it != 0L }?.let(::IndexedJpegRegionDecoder)
    }

    fun openOverviewDecoder(sourcePath: String): IndexedJpegOverviewRegionDecoder? {
        val source = supportedSource(sourcePath) ?: return null
        val index = indexFile(source)
        if (!index.isFile) return null
        readPyramidMetadata(index, source)?.let { pyramid ->
            if (pyramid.formatVersion >= ADDRESSABLE_PYRAMID_FORMAT_VERSION) {
                val handle = IndexedJpegNative.openPyramidTiles(
                    index.absolutePath,
                    source.length(),
                    source.lastModified(),
                )
                if (handle == 0L) return null
                return IndexedJpegOverviewRegionDecoder(
                    sourceWidth = pyramid.sourceWidth,
                    sourceHeight = pyramid.sourceHeight,
                    layers = pyramid.layers,
                    tileLoader = { sampleSize, tileX, tileY ->
                        IndexedJpegNative.readPyramidTile(
                            handle,
                            sampleSize,
                            tileX,
                            tileY,
                        )
                    },
                    tileContainerHandle = handle,
                )
            }
            return IndexedJpegOverviewRegionDecoder(
                sourceWidth = pyramid.sourceWidth,
                sourceHeight = pyramid.sourceHeight,
                layers = pyramid.layers,
                encodedLoader = { sampleSize ->
                    IndexedJpegNative.readPyramidLayer(
                        index.absolutePath,
                        source.length(),
                        source.lastModified(),
                        sampleSize,
                    )
                },
            )
        }
        val metadata = IndexedJpegNative.readOverviewMetadata(
            index.absolutePath,
            source.length(),
            source.lastModified(),
        ) ?: return null
        if (metadata.size < 7 || metadata.any { it <= 0 }) return null
        if (metadata[0] < SINGLE_LAYER_OVERVIEW_FORMAT_VERSION) return null
        if (
            metadata[3] != ceilDiv(metadata[1], metadata[5]) ||
            metadata[4] != ceilDiv(metadata[2], metadata[5])
        ) {
            // Earlier v4 files contain an exact fit-screen image, not a complete
            // power-of-two layer. They remain valid for fit preview, but cannot
            // safely replace source-aligned tile decoding.
            return null
        }
        val encoded = IndexedJpegNative.readOverview(
            index.absolutePath,
            source.length(),
            source.lastModified(),
        ) ?: return null
        if (encoded.size != metadata[6]) return null
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(encoded, 0, encoded.size, false)
            ?: return null
        if (decoder.width != metadata[3] || decoder.height != metadata[4]) {
            decoder.recycle()
            return null
        }
        return IndexedJpegOverviewRegionDecoder(
            decoder = decoder,
            sourceWidth = metadata[1],
            sourceHeight = metadata[2],
            overviewSampleSize = metadata[5],
        )
    }

    internal fun pyramidLayers(sourcePath: String): List<IndexedJpegPyramidLayer> {
        val source = supportedSource(sourcePath) ?: return emptyList()
        return readPyramidMetadata(indexFile(source), source)?.layers.orEmpty()
    }

    /**
     * Decodes the smallest stored low-frequency layer that covers the viewport.
     *
     * The overview is accepted only when it still covers the requested viewport. A larger
     * display therefore falls back to the original source instead of stretching a soft bitmap.
     */
    fun decodeScreenOverview(
        sourcePath: String,
        rotationDegrees: Int,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Bitmap? {
        val source = supportedSource(sourcePath) ?: return null
        if (status(source.absolutePath) !is IndexedJpegStatus.Ready) return null
        val index = indexFile(source)
        val pyramid = readPyramidMetadata(index, source)
        val legacyMetadata = if (pyramid == null) {
            IndexedJpegNative.readOverviewMetadata(
                index.absolutePath,
                source.length(),
                source.lastModified(),
            )
        } else {
            null
        }
        val sourceWidth = pyramid?.sourceWidth ?: legacyMetadata?.getOrNull(1) ?: return null
        val sourceHeight = pyramid?.sourceHeight ?: legacyMetadata?.getOrNull(2) ?: return null
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val selectedLayer = pyramid?.layers?.asReversed()?.firstOrNull {
            overviewCoversFit(
                overviewWidth = it.width,
                overviewHeight = it.height,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                rotationDegrees = rotationDegrees,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
            )
        }
        if (pyramid != null && selectedLayer == null) return null
        if ((pyramid?.formatVersion ?: 0) >= ADDRESSABLE_PYRAMID_FORMAT_VERSION) {
            val decoded = openOverviewDecoder(source.absolutePath)?.use { decoder ->
                decoder.decodeRegion(
                    Rect(0, 0, sourceWidth, sourceHeight),
                    selectedLayer!!.sampleSize,
                )
            } ?: return null
            if (!overviewCoversFit(
                    overviewWidth = decoded.width,
                    overviewHeight = decoded.height,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    rotationDegrees = rotationDegrees,
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                )
            ) {
                decoded.recycle()
                return null
            }
            return reduceOverviewToFit(
                decoded,
                sourceWidth,
                sourceHeight,
                rotationDegrees,
                requestedWidth,
                requestedHeight,
            )
        }
        val encoded = if (pyramid != null) {
            IndexedJpegNative.readPyramidLayer(
                index.absolutePath,
                source.length(),
                source.lastModified(),
                selectedLayer!!.sampleSize,
            ) ?: return null
        } else {
            IndexedJpegNative.readOverview(
                index.absolutePath,
                source.length(),
                source.lastModified(),
            ) ?: return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (!overviewCoversFit(
                overviewWidth = bounds.outWidth,
                overviewHeight = bounds.outHeight,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                rotationDegrees = rotationDegrees,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
            )
        ) {
            return null
        }
        var decodeSample = 1
        while (decodeSample <= Int.MAX_VALUE / 2) {
            val next = decodeSample * 2
            if (!overviewCoversFit(
                    overviewWidth = ceilDiv(bounds.outWidth, next),
                    overviewHeight = ceilDiv(bounds.outHeight, next),
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    rotationDegrees = rotationDegrees,
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                )
            ) {
                break
            }
            decodeSample = next
        }
        return BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply {
                inSampleSize = decodeSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        )
    }

    private fun reduceOverviewToFit(
        bitmap: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Bitmap {
        var reduction = 1
        while (reduction <= Int.MAX_VALUE / 2) {
            val next = reduction * 2
            if (!overviewCoversFit(
                    overviewWidth = ceilDiv(bitmap.width, next),
                    overviewHeight = ceilDiv(bitmap.height, next),
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    rotationDegrees = rotationDegrees,
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                )
            ) {
                break
            }
            reduction = next
        }
        if (reduction == 1) return bitmap
        val reduced = Bitmap.createScaledBitmap(
            bitmap,
            ceilDiv(bitmap.width, reduction),
            ceilDiv(bitmap.height, reduction),
            true,
        )
        if (reduced !== bitmap) bitmap.recycle()
        return reduced
    }

    private fun readPyramidMetadata(
        index: File,
        source: File,
    ): IndexedJpegPyramidMetadata? {
        if (!index.isFile) return null
        val values = IndexedJpegNative.readPyramidMetadata(
            index.absolutePath,
            source.length(),
            source.lastModified(),
        ) ?: return null
        if (values.size < 10 || values[0] < MULTI_LAYER_PYRAMID_FORMAT_VERSION) return null
        val count = values[3]
        if (count <= 0 || values.size != 5 + count * 5 || values[4] <= 0) return null
        val layers = ArrayList<IndexedJpegPyramidLayer>(count)
        repeat(count) { position ->
            val offset = 5 + position * 5
            val layer = IndexedJpegPyramidLayer(
                sampleSize = values[offset],
                width = values[offset + 1],
                height = values[offset + 2],
                bytes = values[offset + 3],
                tileSize = values[offset + 4],
            )
            if (
                layer.sampleSize <= 0 || layer.width <= 0 ||
                layer.height <= 0 || layer.bytes <= 0 ||
                (values[0] >= ADDRESSABLE_PYRAMID_FORMAT_VERSION && layer.tileSize <= 0)
            ) {
                return null
            }
            layers += layer
        }
        return IndexedJpegPyramidMetadata(
            formatVersion = values[0],
            sourceWidth = values[1],
            sourceHeight = values[2],
            payloadBytes = values[4],
            layers = layers,
        )
    }

    val currentGeneration: Long
        get() = generation.get()

    /** Process-local mutation revision scoped to one physical source path. */
    fun currentGenerationFor(sourcePath: String): Long =
        sourceGenerations[File(sourcePath).absolutePath] ?: 0L

    /**
     * Observes process-wide JPEG index mutations. Callbacks run on the mutation thread;
     * UI hosts should dispatch to their main scope before updating observable state.
     */
    fun addChangeListener(listener: (IndexedJpegChange) -> Unit): Closeable {
        changeListeners += listener
        return Closeable { changeListeners -= listener }
    }

    /** Changes whenever the persisted index is replaced, moved, or deleted. */
    fun indexCacheSignature(sourcePath: String): String {
        val index = indexFile(File(sourcePath))
        // This value participates in Glide's persistent resource-cache key. A process-local
        // generation counter resets after every app restart and therefore made an unchanged
        // overview miss the disk cache once per process. The published index file is replaced
        // atomically, so its length and modification time are the stable cross-process identity.
        return "$CACHE_SIGNATURE_VERSION:${index.length()}:${index.lastModified()}"
    }

    @Throws(IOException::class)
    private fun publishCompletedFile(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // The files are always in the same private directory, so Android filesystems
            // normally take the atomic path. Keep a recoverable fallback for unusual providers.
        }

        val backup = File(directory, destination.name + ".backup-${System.nanoTime()}")
        var previousMoved = false
        try {
            if (destination.exists()) {
                Files.move(
                    destination.toPath(),
                    backup.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                previousMoved = true
            }
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (publishFailure: IOException) {
            if (previousMoved && backup.exists()) {
                runCatching {
                    Files.move(
                        backup.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.exceptionOrNull()?.let(publishFailure::addSuppressed)
            }
            throw publishFailure
        }
        if (backup.exists()) backup.delete()
    }

    private fun cleanupStaleTemporaryFiles(
        force: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val previousCleanup = lastTemporaryCleanupMillis.get()
        if (!force && nowMillis - previousCleanup < TEMPORARY_CLEANUP_INTERVAL_MILLIS) return
        if (!lastTemporaryCleanupMillis.compareAndSet(previousCleanup, nowMillis)) return
        directory.listFiles()?.forEach { candidate ->
            val temporary = candidate.name.contains(".tmp-") ||
                candidate.name.contains(".backup-")
            val age = nowMillis - candidate.lastModified()
            if (temporary && candidate.isFile && age >= TEMPORARY_STALE_AGE_MILLIS) {
                candidate.delete()
            }
        }
    }

    private fun notifyIndexChanged(sourcePath: String) {
        val nextGeneration = generation.incrementAndGet()
        val normalizedPath = File(sourcePath).absolutePath
        sourceGenerations[normalizedPath] = nextGeneration
        val change = IndexedJpegChange(normalizedPath, nextGeneration)
        changeListeners.forEach { listener -> runCatching { listener(change) } }
    }

    private fun indexFile(source: File): File = File(directory, sourceKey(source) + INDEX_SUFFIX)

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private fun legacyOverviewFile(source: File): File =
        File(directory, sourceKey(source) + LEGACY_OVERVIEW_SUFFIX)

    private fun maximumViewportSize(): IndexedJpegOverviewSize {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val bounds = appContext.getSystemService(WindowManager::class.java)
                    .maximumWindowMetrics
                    .bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return IndexedJpegOverviewSize(bounds.width(), bounds.height())
                }
            }
        }
        val metrics = appContext.resources.displayMetrics
        return IndexedJpegOverviewSize(
            width = metrics.widthPixels.takeIf { it > 0 } ?: DEFAULT_VIEWPORT_WIDTH,
            height = metrics.heightPixels.takeIf { it > 0 } ?: DEFAULT_VIEWPORT_HEIGHT,
        )
    }

    private fun sourceKey(source: File): String {
        val stablePath = try {
            source.canonicalPath
        } catch (_: IOException) {
            source.absolutePath
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(stablePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun sourceHasSrgbOutput(source: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outColorSpace?.isSrgb != false
        }.getOrDefault(false)
    }

    private fun supportedSource(path: String): File? {
        val source = File(path)
        if (!source.isFile || !source.canRead() || source.length() < 4L) return null
        return try {
            source.inputStream().buffered().use { input ->
                if (input.read() == 0xff && input.read() == 0xd8) source else null
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "indexed-jpeg"
        const val INDEX_SUFFIX = ".ijx"
        const val LEGACY_OVERVIEW_SUFFIX = ".fit-v1.jpg"
        const val DEFAULT_VIEWPORT_WIDTH = 1080
        const val DEFAULT_VIEWPORT_HEIGHT = 1920
        const val INDEX_METADATA_SIZE = 7
        const val FIT_OVERVIEW_FORMAT_VERSION = 4
        const val SINGLE_LAYER_OVERVIEW_FORMAT_VERSION = 5
        const val MULTI_LAYER_PYRAMID_FORMAT_VERSION = 6
        const val ADDRESSABLE_PYRAMID_FORMAT_VERSION = INDEXED_JPEG_ADDRESSABLE_FORMAT_VERSION
        const val CACHE_SIGNATURE_VERSION = "jpeg-index-v7-addressable"
        const val TEMPORARY_CLEANUP_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
        const val TEMPORARY_STALE_AGE_MILLIS = 24L * 60L * 60L * 1000L
        val mutationLock = Any()
        val buildInProgress = AtomicBoolean(false)
        val generation = AtomicLong(0L)
        val lastTemporaryCleanupMillis = AtomicLong(0L)
        val sourceGenerations = ConcurrentHashMap<String, Long>()
        val changeListeners = CopyOnWriteArraySet<(IndexedJpegChange) -> Unit>()
    }
}

/** Chooses the smallest source-shaped bitmap that covers fit-center in either orientation. */
fun adaptiveOverviewTargetSize(
    sourceWidth: Int,
    sourceHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
): IndexedJpegOverviewSize {
    if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
        return IndexedJpegOverviewSize(0, 0)
    }
    fun fitScale(width: Int, height: Int): Double = min(
        width.toDouble() / sourceWidth,
        height.toDouble() / sourceHeight,
    )
    val requiredScale = min(
        1.0,
        max(
            fitScale(viewportWidth, viewportHeight),
            fitScale(viewportHeight, viewportWidth),
        ),
    )
    return IndexedJpegOverviewSize(
        width = ceil(sourceWidth * requiredScale).toInt().coerceAtLeast(1),
        height = ceil(sourceHeight * requiredScale).toInt().coerceAtLeast(1),
    )
}

fun overviewCoversFit(
    overviewWidth: Int,
    overviewHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
    requestedWidth: Int,
    requestedHeight: Int,
): Boolean {
    if (
        overviewWidth <= 0 || overviewHeight <= 0 ||
        sourceWidth <= 0 || sourceHeight <= 0 ||
        requestedWidth <= 0 || requestedHeight <= 0
    ) {
        return false
    }
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    val swap = normalizedRotation == 90 || normalizedRotation == 270
    val orientedWidth = if (swap) overviewHeight else overviewWidth
    val orientedHeight = if (swap) overviewWidth else overviewHeight
    val orientedSourceWidth = if (swap) sourceHeight else sourceWidth
    val orientedSourceHeight = if (swap) sourceWidth else sourceHeight
    val fitScale = min(
        requestedWidth.toDouble() / orientedSourceWidth,
        requestedHeight.toDouble() / orientedSourceHeight,
    )
    val fittedWidth = orientedSourceWidth * fitScale
    val fittedHeight = orientedSourceHeight * fitScale
    // Match the index builder: reconstruction may upscale a fit layer by at most 5%.
    return orientedWidth * 1.05 >= fittedWidth && orientedHeight * 1.05 >= fittedHeight
}

/**
 * Region decoder for the low-frequency JPEG pyramid embedded in a seek index.
 *
 * At the layer's native sampling level, source tile boundaries are mapped proportionally
 * onto the complete overview. Adjacent source tiles therefore resolve the same shared
 * boundary instead of accumulating independent divide-and-round errors.
 */
class IndexedJpegOverviewRegionDecoder private constructor(
    val sourceWidth: Int,
    val sourceHeight: Int,
    private val layers: List<IndexedJpegPyramidLayer>,
    private val encodedLoader: ((Int) -> ByteArray?)?,
    private val tileLoader: ((Int, Int, Int) -> ByteArray?)?,
    private var tileContainerHandle: Long,
    legacyDecoder: BitmapRegionDecoder?,
) : Closeable {
    private val decoders = HashMap<Int, BitmapRegionDecoder>().apply {
        if (legacyDecoder != null && layers.isNotEmpty()) {
            put(layers.first().sampleSize, legacyDecoder)
        }
    }

    internal constructor(
        decoder: BitmapRegionDecoder,
        sourceWidth: Int,
        sourceHeight: Int,
        overviewSampleSize: Int,
    ) : this(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        layers = listOf(
            IndexedJpegPyramidLayer(
                sampleSize = overviewSampleSize,
                width = decoder.width,
                height = decoder.height,
                bytes = 0,
            ),
        ),
        encodedLoader = null,
        tileLoader = null,
        tileContainerHandle = 0L,
        legacyDecoder = decoder,
    )

    internal constructor(
        sourceWidth: Int,
        sourceHeight: Int,
        layers: List<IndexedJpegPyramidLayer>,
        encodedLoader: (Int) -> ByteArray?,
    ) : this(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        layers = layers,
        encodedLoader = encodedLoader,
        tileLoader = null,
        tileContainerHandle = 0L,
        legacyDecoder = null,
    )

    internal constructor(
        sourceWidth: Int,
        sourceHeight: Int,
        layers: List<IndexedJpegPyramidLayer>,
        tileLoader: (Int, Int, Int) -> ByteArray?,
        tileContainerHandle: Long,
    ) : this(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        layers = layers,
        encodedLoader = null,
        tileLoader = tileLoader,
        tileContainerHandle = tileContainerHandle,
        legacyDecoder = null,
    )

    val isAddressableTiled: Boolean
        get() = tileLoader != null

    val overviewSampleSize: Int
        get() = layers.firstOrNull()?.sampleSize ?: 0

    val availableSampleSizes: List<Int>
        get() = layers.map { it.sampleSize }

    fun layerSampleSize(sampleSize: Int): Int? =
        layers.firstOrNull { it.sampleSize == sampleSize }?.sampleSize

    fun addressableTileSize(sampleSize: Int): Int? = if (tileLoader != null) {
        layers.firstOrNull { it.sampleSize == sampleSize }?.tileSize?.takeIf { it > 0 }
    } else {
        null
    }

    fun addressableTileCount(rect: Rect, sampleSize: Int): Int? {
        if (tileLoader == null || !supports(rect, sampleSize)) return null
        val layer = layers.firstOrNull { it.sampleSize == sampleSize } ?: return null
        val mapped = mapToAddressableLayer(rect, layer)
        if (mapped.isEmpty || layer.tileSize <= 0) return null
        val across = (mapped.right - 1) / layer.tileSize - mapped.left / layer.tileSize + 1
        val down = (mapped.bottom - 1) / layer.tileSize - mapped.top / layer.tileSize + 1
        return across * down
    }

    fun supports(rect: Rect, sampleSize: Int): Boolean {
        if (
            rect.isEmpty || rect.left < 0 || rect.top < 0 ||
            rect.right > sourceWidth || rect.bottom > sourceHeight || sampleSize <= 0
        ) {
            return false
        }
        val layer = layers.firstOrNull { it.sampleSize == sampleSize }
        if (layer != null) return !mapToOverview(rect, layer.width, layer.height).isEmpty

        // Version 5 contains only one complete layer. Keep its old full-image
        // downsampling behavior for compatibility, but never use it for partial
        // tiles at another sample tier.
        if (encodedLoader != null || layers.size != 1) return false
        val base = layers.first()
        if (sampleSize < base.sampleSize || sampleSize % base.sampleSize != 0) return false
        val relativeSample = sampleSize / base.sampleSize
        if (relativeSample and (relativeSample - 1) != 0 || !isFullSource(rect)) return false
        val mapped = mapToOverview(rect, base.width, base.height)
        return !mapped.isEmpty
    }

    @Synchronized
    fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap? {
        if (!supports(rect, sampleSize)) return null
        val exactLayer = layers.firstOrNull { it.sampleSize == sampleSize }
        val layer = exactLayer ?: layers.singleOrNull() ?: return null
        val relativeSample = sampleSize / layer.sampleSize
        val overviewRect = if (tileLoader != null) {
            mapToAddressableLayer(rect, layer)
        } else {
            mapToOverview(rect, layer.width, layer.height)
        }
        val decoded = if (tileLoader != null) {
            if (relativeSample != 1) return null
            decodeTiledRegion(layer, overviewRect)
        } else {
            val active = decoderFor(layer) ?: return null
            active.decodeRegion(
                overviewRect,
                BitmapFactory.Options().apply {
                    inSampleSize = relativeSample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            )
        } ?: return null
        val expectedWidth = ceilDiv(rect.width(), sampleSize)
        val expectedHeight = ceilDiv(rect.height(), sampleSize)
        if (decoded.width == expectedWidth && decoded.height == expectedHeight) return decoded
        val resized = Bitmap.createScaledBitmap(decoded, expectedWidth, expectedHeight, true)
        if (resized !== decoded) decoded.recycle()
        return resized
    }

    @Synchronized
    override fun close() {
        decoders.values.forEach { decoder -> decoder.recycle() }
        decoders.clear()
        val handle = tileContainerHandle
        tileContainerHandle = 0L
        if (handle != 0L) IndexedJpegNative.closePyramidTiles(handle)
    }

    private fun decodeTiledRegion(
        layer: IndexedJpegPyramidLayer,
        overviewRect: Rect,
    ): Bitmap? {
        val loader = tileLoader ?: return null
        if (layer.tileSize <= 0 || overviewRect.isEmpty) return null
        val firstTileX = overviewRect.left / layer.tileSize
        val lastTileX = (overviewRect.right - 1) / layer.tileSize
        val firstTileY = overviewRect.top / layer.tileSize
        val lastTileY = (overviewRect.bottom - 1) / layer.tileSize
        if (firstTileX == lastTileX && firstTileY == lastTileY) {
            val tileLeft = firstTileX * layer.tileSize
            val tileTop = firstTileY * layer.tileSize
            val tileWidth = min(layer.tileSize, layer.width - tileLeft)
            val tileHeight = min(layer.tileSize, layer.height - tileTop)
            if (
                overviewRect.left == tileLeft && overviewRect.top == tileTop &&
                overviewRect.right == tileLeft + tileWidth &&
                overviewRect.bottom == tileTop + tileHeight
            ) {
                return decodeStoredTile(
                    loader = loader,
                    layer = layer,
                    tileX = firstTileX,
                    tileY = firstTileY,
                    expectedWidth = tileWidth,
                    expectedHeight = tileHeight,
                )
            }
        }

        val destination = runCatching {
            Bitmap.createBitmap(
                overviewRect.width(),
                overviewRect.height(),
                Bitmap.Config.ARGB_8888,
            )
        }.getOrNull() ?: return null
        val canvas = Canvas(destination)
        for (tileY in firstTileY..lastTileY) {
            for (tileX in firstTileX..lastTileX) {
                val tileLeft = tileX * layer.tileSize
                val tileTop = tileY * layer.tileSize
                val expectedTileWidth = min(layer.tileSize, layer.width - tileLeft)
                val expectedTileHeight = min(layer.tileSize, layer.height - tileTop)
                val tileBitmap = decodeStoredTile(
                    loader = loader,
                    layer = layer,
                    tileX = tileX,
                    tileY = tileY,
                    expectedWidth = expectedTileWidth,
                    expectedHeight = expectedTileHeight,
                )
                if (tileBitmap == null) {
                    destination.recycle()
                    return null
                }
                val intersection = Rect(
                    max(overviewRect.left, tileLeft),
                    max(overviewRect.top, tileTop),
                    min(overviewRect.right, tileLeft + tileBitmap.width),
                    min(overviewRect.bottom, tileTop + tileBitmap.height),
                )
                val sourceRect = Rect(
                    intersection.left - tileLeft,
                    intersection.top - tileTop,
                    intersection.right - tileLeft,
                    intersection.bottom - tileTop,
                )
                val destinationRect = Rect(
                    intersection.left - overviewRect.left,
                    intersection.top - overviewRect.top,
                    intersection.right - overviewRect.left,
                    intersection.bottom - overviewRect.top,
                )
                canvas.drawBitmap(tileBitmap, sourceRect, destinationRect, null)
                tileBitmap.recycle()
            }
        }
        return destination
    }

    private fun decodeStoredTile(
        loader: (Int, Int, Int) -> ByteArray?,
        layer: IndexedJpegPyramidLayer,
        tileX: Int,
        tileY: Int,
        expectedWidth: Int,
        expectedHeight: Int,
    ): Bitmap? {
        val encoded = loader(layer.sampleSize, tileX, tileY) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        ) ?: return null
        if (bitmap.width == expectedWidth && bitmap.height == expectedHeight) return bitmap
        bitmap.recycle()
        return null
    }

    private fun decoderFor(layer: IndexedJpegPyramidLayer): BitmapRegionDecoder? {
        decoders[layer.sampleSize]?.let { return it }
        val encoded = encodedLoader?.invoke(layer.sampleSize) ?: return null
        if (encoded.size != layer.bytes) return null
        @Suppress("DEPRECATION")
        val opened = BitmapRegionDecoder.newInstance(encoded, 0, encoded.size, false)
            ?: return null
        if (opened.width != layer.width || opened.height != layer.height) {
            opened.recycle()
            return null
        }
        decoders[layer.sampleSize] = opened
        return opened
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private fun isFullSource(rect: Rect): Boolean =
        rect.left == 0 && rect.top == 0 &&
            rect.right == sourceWidth && rect.bottom == sourceHeight

    private fun mapToOverview(rect: Rect, overviewWidth: Int, overviewHeight: Int): Rect = Rect(
        mapCoordinate(rect.left, sourceWidth, overviewWidth),
        mapCoordinate(rect.top, sourceHeight, overviewHeight),
        mapCoordinate(rect.right, sourceWidth, overviewWidth),
        mapCoordinate(rect.bottom, sourceHeight, overviewHeight),
    )

    private fun mapToAddressableLayer(
        rect: Rect,
        layer: IndexedJpegPyramidLayer,
    ): Rect = Rect(
        (rect.left / layer.sampleSize).coerceIn(0, layer.width),
        (rect.top / layer.sampleSize).coerceIn(0, layer.height),
        ceilDiv(rect.right, layer.sampleSize).coerceIn(0, layer.width),
        ceilDiv(rect.bottom, layer.sampleSize).coerceIn(0, layer.height),
    )

    private fun mapCoordinate(coordinate: Int, sourceExtent: Int, overviewExtent: Int): Int =
        ((coordinate.toLong() * overviewExtent + sourceExtent / 2L) / sourceExtent)
            .toInt()
            .coerceIn(0, overviewExtent)
}

class IndexedJpegRegionDecoder internal constructor(
    private var nativeHandle: Long,
) : Closeable {
    fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap? {
        val handle = nativeHandle
        if (handle == 0L || rect.isEmpty || sampleSize <= 0) return null
        val width = ceilDiv(rect.width(), sampleSize)
        val height = ceilDiv(rect.height(), sampleSize)
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return if (IndexedJpegNative.decode(
                handle,
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                sampleSize,
                bitmap,
            )
        ) {
            bitmap
        } else {
            bitmap.recycle()
            null
        }
    }

    override fun close() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) IndexedJpegNative.close(handle)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}

private object IndexedJpegNative {
    init {
        System.loadLibrary("indexed-jpeg")
    }

    external fun buildIndex(
        sourcePath: String,
        destinationPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
        viewportWidth: Int,
        viewportHeight: Int,
    ): IntArray

    external fun validateIndex(
        indexPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
    ): Boolean

    external fun readIndexMetadata(
        indexPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
    ): IntArray?

    external fun readOverview(
        indexPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
    ): ByteArray?

    external fun readOverviewMetadata(
        indexPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
    ): IntArray?

    external fun readPyramidMetadata(
        indexPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
    ): IntArray?

    external fun readPyramidLayer(
        indexPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
        sampleSize: Int,
    ): ByteArray?

    external fun openPyramidTiles(
        indexPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
    ): Long

    external fun readPyramidTile(
        handle: Long,
        sampleSize: Int,
        tileX: Int,
        tileY: Int,
    ): ByteArray?

    external fun closePyramidTiles(handle: Long)

    external fun open(
        sourcePath: String,
        indexPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
    ): Long

    external fun decode(
        handle: Long,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        sampleSize: Int,
        bitmap: Bitmap,
    ): Boolean

    external fun close(handle: Long)
}
