package io.github.indexedpng

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface IndexedPngStatus {
    data object Absent : IndexedPngStatus
    data class Ready(val bytes: Long) : IndexedPngStatus
    data class Unsupported(val reason: String) : IndexedPngStatus
    data class Invalid(val reason: String) : IndexedPngStatus
}

data class IndexedPngInfo(
    val indexBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val levelCount: Int,
    val tileCount: Int,
)

data class IndexedPngColdReadResult(
    val adviceAccepted: Boolean,
    val residencyVerified: Boolean,
    val totalPages: Long,
    val residentBefore: Long,
    val residentAfter: Long,
)

/**
 * Persistent, opt-in lossless PNG tile-pyramid storage.
 *
 * Merely creating this class or opening a source never creates an index. [build]
 * is the sole entry point that performs the one-time full PNG decode.
 */
class IndexedPngStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    fun status(sourcePath: String): IndexedPngStatus {
        val source = readableSource(sourcePath)
            ?: return IndexedPngStatus.Unsupported("A readable local PNG file is required")
        val compatibility = IndexedPngSourcePolicy.inspect(source.absolutePath)
        if (!compatibility.canUseSrgbTilePyramid) {
            return IndexedPngStatus.Unsupported("PNG index cannot preserve ${compatibility.description}")
        }
        val index = indexFile(source)
        if (!index.isFile) return IndexedPngStatus.Absent
        return if (
            IndexedPngNative.validateIndex(
                index.absolutePath,
                source.length(),
                source.lastModified(),
            )
        ) {
            IndexedPngStatus.Ready(index.length())
        } else {
            IndexedPngStatus.Invalid("The image changed or the index is incompatible")
        }
    }

    @Throws(IOException::class)
    fun build(sourcePath: String): IndexedPngInfo = synchronized(mutationLock) {
        val source = readableSource(sourcePath)
            ?: throw IOException("A readable local PNG file is required")
        val compatibility = IndexedPngSourcePolicy.inspect(source.absolutePath)
        if (!compatibility.canUseSrgbTilePyramid) {
            throw IOException("PNG index cannot preserve ${compatibility.description}")
        }
        if (!buildInProgress.compareAndSet(false, true)) {
            throw IOException("Another PNG index build is already running")
        }
        try {
            directory.mkdirs()
            val destination = indexFile(source)
            val temporary = File(directory, destination.name + ".tmp-${System.nanoTime()}")
            try {
                val nativeInfo = IndexedPngNative.buildIndex(
                    source.absolutePath,
                    temporary.absolutePath,
                    source.length(),
                    source.lastModified(),
                )
                if (!temporary.isFile || temporary.length() <= 0L) {
                    throw IOException("The native PNG index writer produced no data")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous PNG index")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to publish the completed PNG index")
                }
                markChanged(source)
                return IndexedPngInfo(
                    indexBytes = destination.length(),
                    sourceWidth = nativeInfo[0],
                    sourceHeight = nativeInfo[1],
                    levelCount = nativeInfo[2],
                    tileCount = nativeInfo[3],
                )
            } finally {
                temporary.delete()
                File(temporary.absolutePath + RAW_TEMP_SUFFIX).delete()
            }
        } finally {
            buildInProgress.set(false)
        }
    }

    fun delete(sourcePath: String): Boolean = synchronized(mutationLock) {
        if (!buildInProgress.compareAndSet(false, true)) return false
        return try {
            val source = File(sourcePath)
            val index = indexFile(source)
            val existed = index.exists()
            val deleted = !existed || index.delete()
            if (deleted && existed) markChanged(source)
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
            val destinationSource = compatibleSource(destinationPath) ?: return false
            val destinationIndex = indexFile(destinationSource)
            if (sourceIndex.absolutePath == destinationIndex.absolutePath) return true
            if (destinationIndex.exists() && !destinationIndex.delete()) return false
            if (!sourceIndex.renameTo(destinationIndex)) return false
            if (status(destinationPath) is IndexedPngStatus.Ready) {
                markChanged(File(sourcePath))
                markChanged(destinationSource)
                true
            } else {
                if (!destinationIndex.renameTo(sourceIndex)) destinationIndex.delete()
                false
            }
        } finally {
            buildInProgress.set(false)
        }
    }

    fun openDecoder(sourcePath: String): IndexedPngRegionDecoder? {
        val source = compatibleSource(sourcePath) ?: return null
        val index = indexFile(source)
        if (!index.isFile) return null
        val handle = IndexedPngNative.open(
            index.absolutePath,
            source.length(),
            source.lastModified(),
        )
        return handle.takeIf { it != 0L }?.let(::IndexedPngRegionDecoder)
    }

    fun currentGenerationFor(sourcePath: String): Long =
        generations[sourceKey(File(sourcePath))]?.get() ?: 0L

    private fun indexFile(source: File): File = File(directory, sourceKey(source) + INDEX_SUFFIX)

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

    /** Test-only cache policy hook. The index itself is preserved; only clean file pages are reclaimed. */
    fun requestColdRead(sourcePath: String, verifyResidency: Boolean): IndexedPngColdReadResult? {
        val source = compatibleSource(sourcePath) ?: return null
        val index = indexFile(source)
        if (!index.isFile) return null
        val sourceResult = IndexedPngNative.dropFileCache(source.absolutePath, verifyResidency)
            ?.takeIf { it.size >= 5 } ?: return null
        val indexResult = IndexedPngNative.dropFileCache(index.absolutePath, verifyResidency)
            ?.takeIf { it.size >= 5 } ?: return null
        val residencyVerified = sourceResult[1] == 1L && indexResult[1] == 1L
        return IndexedPngColdReadResult(
            adviceAccepted = sourceResult[0] == 1L && indexResult[0] == 1L,
            residencyVerified = residencyVerified,
            totalPages = sourceResult[2] + indexResult[2],
            residentBefore = if (residencyVerified) sourceResult[3] + indexResult[3] else -1L,
            residentAfter = if (residencyVerified) sourceResult[4] + indexResult[4] else -1L,
        )
    }

    private fun markChanged(source: File) {
        generations.computeIfAbsent(sourceKey(source)) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun compatibleSource(path: String): File? = readableSource(path)?.takeIf { source ->
        IndexedPngSourcePolicy.inspect(source.absolutePath).canUseSrgbTilePyramid
    }

    private fun readableSource(path: String): File? {
        val source = File(path)
        if (!source.isFile || !source.canRead() || source.length() < PNG_SIGNATURE.size) return null
        return try {
            source.inputStream().buffered().use { input ->
                val signature = ByteArray(PNG_SIGNATURE.size)
                if (input.read(signature) == signature.size && signature.contentEquals(PNG_SIGNATURE)) {
                    source
                } else {
                    null
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        const val TILE_SIZE = 512
        const val PREFERRED_DECODED_TILE_SIZE = TILE_SIZE * 2
        private const val DIRECTORY_NAME = "indexed-png"
        private const val INDEX_SUFFIX = ".ipx"
        private const val RAW_TEMP_SUFFIX = ".rows"
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        private val mutationLock = Any()
        private val buildInProgress = AtomicBoolean(false)
        private val generations = ConcurrentHashMap<String, AtomicLong>()
    }
}

class IndexedPngRegionDecoder internal constructor(
    private var nativeHandle: Long,
) : Closeable {
    fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap? {
        val handle = nativeHandle
        if (handle == 0L || rect.isEmpty || sampleSize <= 0) return null
        val width = ceilDiv(rect.width(), sampleSize)
        val height = ceilDiv(rect.height(), sampleSize)
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return if (
            IndexedPngNative.decode(
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
        if (handle != 0L) IndexedPngNative.close(handle)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}

private object IndexedPngNative {
    init {
        System.loadLibrary("indexed-png")
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

    external fun dropFileCache(path: String, verifyResidency: Boolean): LongArray?
}
