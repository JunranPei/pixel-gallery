package io.github.indexedjpeg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowManager
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
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
    fun build(sourcePath: String): IndexedJpegInfo = synchronized(mutationLock) {
        val source = supportedSource(sourcePath)
            ?: throw IOException("A readable local JPEG file is required")
        if (!buildInProgress.compareAndSet(false, true)) {
            throw IOException("Another JPEG index build is already running")
        }
        try {
            directory.mkdirs()
            val destination = indexFile(source)
            val temporary = File(directory, destination.name + ".tmp-${System.nanoTime()}")
            val overviewDestination = overviewFile(source)
            val overviewTemporary = File(
                directory,
                overviewDestination.name + ".tmp-${System.nanoTime()}",
            )
            try {
                val nativeInfo = IndexedJpegNative.buildIndex(
                    source.absolutePath,
                    temporary.absolutePath,
                    source.length(),
                    source.lastModified(),
                )
                if (!temporary.isFile || temporary.length() <= 0L) {
                    throw IOException("The native index writer produced no data")
                }
                val overview = runCatching {
                    buildAdaptiveOverview(
                        source = source,
                        destination = overviewTemporary,
                        sourceWidth = nativeInfo[0],
                        sourceHeight = nativeInfo[1],
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Unable to build the optional JPEG fit overview", error)
                }.getOrNull()
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous index")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to publish the completed index")
                }
                val publishedOverview = if (overview != null) {
                    if (overviewDestination.exists() && !overviewDestination.delete()) {
                        Log.w(TAG, "Unable to replace the previous JPEG fit overview")
                        false
                    } else if (!overviewTemporary.renameTo(overviewDestination)) {
                        Log.w(TAG, "Unable to publish the completed JPEG fit overview")
                        false
                    } else {
                        true
                    }
                } else {
                    // A source that is too small, wide-gamut, or Ultra HDR must not inherit an
                    // overview produced by an older build for the same path.
                    !overviewDestination.exists() || overviewDestination.delete()
                }
                generation.incrementAndGet()
                return IndexedJpegInfo(
                    indexBytes = destination.length(),
                    sourceWidth = nativeInfo[0],
                    sourceHeight = nativeInfo[1],
                    scanCount = nativeInfo[2],
                    overviewBytes = if (publishedOverview) overviewDestination.length() else 0L,
                    overviewWidth = if (publishedOverview) overview!!.width else 0,
                    overviewHeight = if (publishedOverview) overview!!.height else 0,
                )
            } finally {
                temporary.delete()
                overviewTemporary.delete()
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
            val overviewDeleted = overviewFile(source).let { !it.exists() || it.delete() }
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
            val sourceOverview = overviewFile(File(sourcePath))
            val destinationSource = supportedSource(destinationPath) ?: return false
            val destinationIndex = indexFile(destinationSource)
            val destinationOverview = overviewFile(destinationSource)
            if (sourceIndex.absolutePath == destinationIndex.absolutePath) return true
            if (destinationIndex.exists() && !destinationIndex.delete()) return false
            if (destinationOverview.exists() && !destinationOverview.delete()) return false
            if (!sourceIndex.renameTo(destinationIndex)) return false
            val overviewMoved = !sourceOverview.exists() || sourceOverview.renameTo(destinationOverview)
            if (!overviewMoved) {
                destinationIndex.renameTo(sourceIndex)
                return false
            }
            if (status(destinationPath) is IndexedJpegStatus.Ready) {
                generation.incrementAndGet()
                true
            } else {
                if (!destinationIndex.renameTo(sourceIndex)) destinationIndex.delete()
                if (destinationOverview.exists()) {
                    if (!destinationOverview.renameTo(sourceOverview)) destinationOverview.delete()
                }
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

    /**
     * Decodes the one adaptive fit overview created alongside a ready seek index.
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
        val overview = overviewFile(source).takeIf(File::isFile) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(overview.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (!overviewCoversFit(
                overviewWidth = bounds.outWidth,
                overviewHeight = bounds.outHeight,
                rotationDegrees = rotationDegrees,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
            )
        ) {
            return null
        }
        return BitmapFactory.decodeFile(
            overview.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    val currentGeneration: Long
        get() = generation.get()

    private fun indexFile(source: File): File = File(directory, sourceKey(source) + INDEX_SUFFIX)

    private fun overviewFile(source: File): File =
        File(directory, sourceKey(source) + OVERVIEW_SUFFIX)

    private fun buildAdaptiveOverview(
        source: File,
        destination: File,
        sourceWidth: Int,
        sourceHeight: Int,
    ): IndexedJpegOverviewSize? {
        val viewport = maximumViewportSize()
        val target = adaptiveOverviewTargetSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
        )
        // Small JPEGs do not need a duplicate overview; their ordinary source decode is already
        // bounded by the display-sized workload this feature is intended to enforce.
        if (target.width >= sourceWidth && target.height >= sourceHeight) return null

        var sampleSize = 1
        while (
            sourceWidth / (sampleSize * 2f) >= target.width &&
            sourceHeight / (sampleSize * 2f) >= target.height
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && decoded.colorSpace?.isSrgb == false) {
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && decoded.hasGainmap()) {
                return null
            }
            val scaled = if (decoded.width == target.width && decoded.height == target.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
            }
            try {
                FileOutputStream(destination).use { output ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, OVERVIEW_JPEG_QUALITY, output)) {
                        throw IOException("Unable to encode the JPEG fit overview")
                    }
                }
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
            if (!destination.isFile || destination.length() <= 0L) return null
            return target
        } finally {
            decoded.recycle()
        }
    }

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
        const val TAG = "IndexedJpegStore"
        const val DIRECTORY_NAME = "indexed-jpeg"
        const val INDEX_SUFFIX = ".ijx"
        const val OVERVIEW_SUFFIX = ".fit-v1.jpg"
        const val OVERVIEW_JPEG_QUALITY = 95
        const val DEFAULT_VIEWPORT_WIDTH = 1080
        const val DEFAULT_VIEWPORT_HEIGHT = 1920
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
    rotationDegrees: Int,
    requestedWidth: Int,
    requestedHeight: Int,
): Boolean {
    if (
        overviewWidth <= 0 || overviewHeight <= 0 ||
        requestedWidth <= 0 || requestedHeight <= 0
    ) {
        return false
    }
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    val swap = normalizedRotation == 90 || normalizedRotation == 270
    val orientedWidth = if (swap) overviewHeight else overviewWidth
    val orientedHeight = if (swap) overviewWidth else overviewHeight
    val scale = min(
        requestedWidth.toDouble() / orientedWidth,
        requestedHeight.toDouble() / orientedHeight,
    )
    // Upscaling by more than rounding noise means this overview cannot cover the viewport.
    return scale <= 1.001
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
    ): IntArray

    external fun validateIndex(
        indexPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
    ): Boolean

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
