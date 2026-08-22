package io.github.indexedjpeg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
)

/**
 * Persistent, opt-in JPEG seek-index storage.
 *
 * Creating this class and opening images never builds an index. [build] is the
 * only entry point that performs the one-time full entropy scan.
 */
class IndexedJpegStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

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
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous index")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to publish the completed index")
                }
                generation.incrementAndGet()
                return IndexedJpegInfo(
                    indexBytes = destination.length(),
                    sourceWidth = nativeInfo[0],
                    sourceHeight = nativeInfo[1],
                    scanCount = nativeInfo[2],
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
            val deleted = indexFile(source).let { !it.exists() || it.delete() }
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
        val mutationLock = Any()
        val buildInProgress = AtomicBoolean(false)
        val generation = AtomicLong(0L)
    }
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
