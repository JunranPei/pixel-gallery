package io.github.indexedwebp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface IndexedWebpStatus {
    data object Absent : IndexedWebpStatus
    data class Ready(val bytes: Long) : IndexedWebpStatus
    data class Unsupported(val reason: String) : IndexedWebpStatus
    data class Invalid(val reason: String) : IndexedWebpStatus
}

data class IndexedWebpInfo(
    val indexBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val levelCount: Int,
    val tileCount: Int,
)

/**
 * Persistent, opt-in lossless tile pyramid for static WebP sources.
 *
 * [build] is the only operation that performs a complete WebP decode. Merely checking status or
 * opening a source never creates or refreshes an index.
 */
class IndexedWebpStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    fun status(sourcePath: String): IndexedWebpStatus {
        val source = supportedSource(sourcePath)
            ?: return IndexedWebpStatus.Unsupported("A readable local WebP file is required")
        val index = indexFile(source)
        if (!index.isFile) return IndexedWebpStatus.Absent
        return if (
            IndexedWebpNative.validateIndex(index.absolutePath, source.length(), source.lastModified())
        ) {
            IndexedWebpStatus.Ready(index.length())
        } else {
            IndexedWebpStatus.Invalid("The image changed or the index is incompatible")
        }
    }

    @Throws(IOException::class)
    fun build(sourcePath: String): IndexedWebpInfo = synchronized(mutationLock) {
        val source = supportedSource(sourcePath)
            ?: throw IOException("A readable local WebP file is required")
        if (!buildInProgress.compareAndSet(false, true)) {
            throw IOException("Another WebP index build is already running")
        }
        try {
            directory.mkdirs()
            val destination = indexFile(source)
            val temporary = File(directory, destination.name + ".tmp-${System.nanoTime()}")
            try {
                val nativeInfo = IndexedWebpNative.buildIndex(
                    source.absolutePath,
                    temporary.absolutePath,
                    source.length(),
                    source.lastModified(),
                )
                if (!temporary.isFile || temporary.length() <= 0L) {
                    throw IOException("The native WebP index writer produced no data")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous WebP index")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to publish the completed WebP index")
                }
                generation.incrementAndGet()
                return IndexedWebpInfo(
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
            val deleted = indexFile(File(sourcePath)).let { !it.exists() || it.delete() }
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
            if (status(destinationPath) is IndexedWebpStatus.Ready) {
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

    fun openDecoder(sourcePath: String): IndexedWebpRegionDecoder? {
        val source = supportedSource(sourcePath) ?: return null
        val index = indexFile(source)
        if (!index.isFile) return null
        val handle = IndexedWebpNative.open(index.absolutePath, source.length(), source.lastModified())
        return handle.takeIf { it != 0L }?.let(::IndexedWebpRegionDecoder)
    }

    val currentGeneration: Long
        get() = generation.get()

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

    private fun supportedSource(path: String): File? {
        val source = File(path)
        if (!source.isFile || !source.canRead() || source.length() < WEBP_HEADER_BYTES) return null
        return try {
            source.inputStream().buffered().use { input ->
                val header = ByteArray(WEBP_HEADER_BYTES)
                if (
                    input.read(header) == header.size &&
                    header.copyOfRange(0, 4).contentEquals(RIFF) &&
                    header.copyOfRange(8, 12).contentEquals(WEBP)
                ) {
                    source
                } else {
                    null
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "indexed-webp"
        const val INDEX_SUFFIX = ".iwx"
        const val RAW_TEMP_SUFFIX = ".rgba"
        const val WEBP_HEADER_BYTES = 12
        val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
        val mutationLock = Any()
        val buildInProgress = AtomicBoolean(false)
        val generation = AtomicLong(0L)
    }
}

class IndexedWebpRegionDecoder internal constructor(
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
            IndexedWebpNative.decode(
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
        if (handle != 0L) IndexedWebpNative.close(handle)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}

private object IndexedWebpNative {
    init {
        System.loadLibrary("glide-webp")
        System.loadLibrary("indexed-webp")
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

    external fun open(indexPath: String, sourceBytes: Long, sourceModifiedMillis: Long): Long

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
