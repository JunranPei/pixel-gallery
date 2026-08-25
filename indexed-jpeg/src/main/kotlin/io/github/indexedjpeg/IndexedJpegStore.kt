package io.github.indexedjpeg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

sealed interface IndexedJpegStatus {
    data object Absent : IndexedJpegStatus
    data class Ready(val bytes: Long) : IndexedJpegStatus
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
)

data class IndexedJpegPyramidLayer(
    val sampleSize: Int,
    val width: Int,
    val height: Int,
    val bytes: Int,
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
        val source = supportedSource(sourcePath)
            ?: return IndexedJpegStatus.Unsupported("A readable local JPEG file is required")
        val index = indexFile(source)
        if (!index.isFile) return IndexedJpegStatus.Absent
        return if (IndexedJpegNative.validateIndex(
                index.absolutePath,
                source.length(),
                source.lastModified(),
            )
        ) {
            IndexedJpegStatus.Ready(index.length())
        } else {
            IndexedJpegStatus.Invalid("The image changed or the index is incompatible")
        }
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
            directory.mkdirs()
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
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous index")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to publish the completed index")
                }
                legacyOverviewFile(source).delete()
                generation.incrementAndGet()
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
            val indexDeleted = indexFile(source).let { !it.exists() || it.delete() }
            val overviewDeleted = legacyOverviewFile(source).let { !it.exists() || it.delete() }
            val deleted = indexDeleted && overviewDeleted
            if (deleted) generation.incrementAndGet()
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
            if (destinationIndex.exists() && !destinationIndex.delete()) return false
            if (!sourceIndex.renameTo(destinationIndex)) return false
            legacyOverviewFile(File(sourcePath)).delete()
            legacyOverviewFile(destinationSource).delete()
            if (status(destinationPath) is IndexedJpegStatus.Ready) {
                generation.incrementAndGet()
                true
            } else {
                if (!destinationIndex.renameTo(sourceIndex)) destinationIndex.delete()
                false
            }
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
        val encoded = if (pyramid != null) {
            val layer = pyramid.layers.asReversed().firstOrNull {
                overviewCoversFit(
                    overviewWidth = it.width,
                    overviewHeight = it.height,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    rotationDegrees = rotationDegrees,
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                )
            } ?: return null
            IndexedJpegNative.readPyramidLayer(
                index.absolutePath,
                source.length(),
                source.lastModified(),
                layer.sampleSize,
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
        if (values.size < 9 || values[0] < MULTI_LAYER_PYRAMID_FORMAT_VERSION) return null
        val count = values[3]
        if (count <= 0 || values.size != 5 + count * 4 || values[4] <= 0) return null
        val layers = ArrayList<IndexedJpegPyramidLayer>(count)
        repeat(count) { position ->
            val offset = 5 + position * 4
            val layer = IndexedJpegPyramidLayer(
                sampleSize = values[offset],
                width = values[offset + 1],
                height = values[offset + 2],
                bytes = values[offset + 3],
            )
            if (
                layer.sampleSize <= 0 || layer.width <= 0 ||
                layer.height <= 0 || layer.bytes <= 0
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

    /** Changes whenever the persisted index is replaced, moved, or deleted. */
    fun indexCacheSignature(sourcePath: String): String {
        val index = indexFile(File(sourcePath))
        // This value participates in Glide's persistent resource-cache key. A process-local
        // generation counter resets after every app restart and therefore made an unchanged
        // overview miss the disk cache once per process. The published index file is replaced
        // atomically, so its length and modification time are the stable cross-process identity.
        return "${index.length()}:${index.lastModified()}"
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
        const val SINGLE_LAYER_OVERVIEW_FORMAT_VERSION = 5
        const val MULTI_LAYER_PYRAMID_FORMAT_VERSION = 6
        val mutationLock = Any()
        val buildInProgress = AtomicBoolean(false)
        val generation = AtomicLong(0L)
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
        legacyDecoder = null,
    )

    val overviewSampleSize: Int
        get() = layers.firstOrNull()?.sampleSize ?: 0

    val availableSampleSizes: List<Int>
        get() = layers.map { it.sampleSize }

    fun layerSampleSize(sampleSize: Int): Int? =
        layers.firstOrNull { it.sampleSize == sampleSize }?.sampleSize

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
        val active = decoderFor(layer) ?: return null
        val relativeSample = sampleSize / layer.sampleSize
        val overviewRect = mapToOverview(rect, active.width, active.height)
        val decoded = active.decodeRegion(
            overviewRect,
            BitmapFactory.Options().apply {
                inSampleSize = relativeSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        ) ?: return null
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
