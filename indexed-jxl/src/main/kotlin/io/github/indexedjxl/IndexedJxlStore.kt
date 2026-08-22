package io.github.indexedjxl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface IndexedJxlStatus {
    data object Absent : IndexedJxlStatus
    data class Ready(val bytes: Long) : IndexedJxlStatus
    data class Unsupported(val reason: String) : IndexedJxlStatus
    data class Invalid(val reason: String) : IndexedJxlStatus
}

data class IndexedJxlInfo(
    val indexBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val levelCount: Int,
    val tileCount: Int,
)

/** Explicit, lossless, multi-resolution tile index for a still 8-bit JPEG XL image. */
class IndexedJxlStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    fun status(sourcePath: String): IndexedJxlStatus {
        val source = supportedSource(sourcePath)
            ?: return IndexedJxlStatus.Unsupported("A readable local JPEG XL file is required")
        val index = indexFile(source)
        if (!index.isFile) return IndexedJxlStatus.Absent
        return if (IndexedJxlNative.validateIndex(index.absolutePath, source.length(), source.lastModified())) {
            IndexedJxlStatus.Ready(index.length())
        } else {
            IndexedJxlStatus.Invalid("The image changed or the index is incompatible")
        }
    }

    @Throws(IOException::class)
    fun build(sourcePath: String): IndexedJxlInfo = synchronized(mutationLock) {
        val source = supportedSource(sourcePath)
            ?: throw IOException("A readable local JPEG XL file is required")
        if (!buildInProgress.compareAndSet(false, true)) {
            throw IOException("Another JPEG XL index build is already running")
        }
        try {
            directory.mkdirs()
            val destination = indexFile(source)
            val temporary = File(directory, destination.name + ".tmp-${System.nanoTime()}")
            try {
                val nativeInfo = IndexedJxlNative.buildIndex(
                    source.absolutePath,
                    temporary.absolutePath,
                    source.length(),
                    source.lastModified(),
                )
                if (nativeInfo.size != 4) throw IOException("Invalid JPEG XL index result")
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous JPEG XL index")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to publish the completed JPEG XL index")
                }
                generation.incrementAndGet()
                return IndexedJxlInfo(
                    indexBytes = destination.length(),
                    sourceWidth = nativeInfo[0],
                    sourceHeight = nativeInfo[1],
                    levelCount = nativeInfo[2],
                    tileCount = nativeInfo[3],
                )
            } catch (error: IOException) {
                throw error
            } catch (error: Throwable) {
                throw IOException("Unable to build JPEG XL index: ${error.message}", error)
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
            if (status(destinationPath) is IndexedJxlStatus.Ready) {
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

    fun openDecoder(sourcePath: String): IndexedJxlRegionDecoder? {
        val source = supportedSource(sourcePath) ?: return null
        val index = indexFile(source)
        if (!index.isFile) return null
        val handle = IndexedJxlNative.open(index.absolutePath, source.length(), source.lastModified())
        if (handle == 0L) return null
        val dimensions = IndexedJxlNative.dimensions(handle)
        if (dimensions.size != 2 || dimensions[0] <= 0 || dimensions[1] <= 0) {
            IndexedJxlNative.close(handle)
            return null
        }
        return IndexedJxlRegionDecoder(handle, dimensions[0], dimensions[1])
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
        if (!source.isFile || !source.canRead() || source.length() <= 0L) return null
        return source.takeIf { it.extension.equals("jxl", ignoreCase = true) }
    }

    private companion object {
        const val DIRECTORY_NAME = "indexed-jxl"
        const val INDEX_SUFFIX = ".ijx"
        val mutationLock = Any()
        val buildInProgress = AtomicBoolean(false)
        val generation = AtomicLong(0L)
    }
}

class IndexedJxlRegionDecoder internal constructor(
    private var nativeHandle: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
) : Closeable {
    fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap? {
        val handle = nativeHandle
        if (handle == 0L || rect.isEmpty || sampleSize <= 0) return null
        val width = ceilDiv(rect.width(), sampleSize)
        val height = ceilDiv(rect.height(), sampleSize)
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return if (
            IndexedJxlNative.decode(
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
        if (handle != 0L) IndexedJxlNative.close(handle)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}

private object IndexedJxlNative {
    init { System.loadLibrary("indexed-jxl") }

    external fun buildIndex(
        sourcePath: String,
        destinationPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
    ): IntArray
    external fun validateIndex(indexPath: String, sourceBytes: Long, sourceModifiedMillis: Long): Boolean
    external fun open(indexPath: String, sourceBytes: Long, sourceModifiedMillis: Long): Long
    external fun dimensions(handle: Long): IntArray
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
