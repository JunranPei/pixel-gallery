package io.github.indexedraw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface IndexedRawStatus {
    data object Absent : IndexedRawStatus
    data class Ready(val bytes: Long) : IndexedRawStatus
    data class Unsupported(val reason: String) : IndexedRawStatus
    data class Invalid(val reason: String) : IndexedRawStatus
}

data class IndexedRawInfo(
    val indexBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val levelCount: Int,
    val tileCount: Int,
)

/** Explicit, persistent developed RAW rendering. No build is triggered by status/open calls. */
class IndexedRawStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    fun status(sourcePath: String): IndexedRawStatus {
        val source = supportedSource(sourcePath)
            ?: return IndexedRawStatus.Unsupported("A readable local camera RAW file is required")
        val index = indexFile(source)
        if (!index.isFile) return IndexedRawStatus.Absent
        return if (IndexedRawNative.validateIndex(index.absolutePath, source.length(), source.lastModified())) {
            IndexedRawStatus.Ready(index.length())
        } else {
            IndexedRawStatus.Invalid("The RAW source changed or the developed index is incompatible")
        }
    }

    @Throws(IOException::class)
    fun build(sourcePath: String): IndexedRawInfo = synchronized(mutationLock) {
        val source = supportedSource(sourcePath)
            ?: throw IOException("A readable local camera RAW file is required")
        if (!buildInProgress.compareAndSet(false, true)) {
            throw IOException("Another RAW development is already running")
        }
        try {
            directory.mkdirs()
            val destination = indexFile(source)
            val temporary = File(directory, destination.name + ".tmp-${System.nanoTime()}")
            try {
                val nativeInfo = IndexedRawNative.buildIndex(
                    source.absolutePath,
                    temporary.absolutePath,
                    source.length(),
                    source.lastModified(),
                )
                if (!temporary.isFile || temporary.length() <= 0L) {
                    throw IOException("The native RAW index writer produced no data")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous RAW index")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to publish the completed RAW index")
                }
                generation.incrementAndGet()
                return IndexedRawInfo(
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
            if (status(destinationPath) is IndexedRawStatus.Ready) {
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

    fun openDecoder(sourcePath: String): IndexedRawRegionDecoder? {
        val source = supportedSource(sourcePath) ?: return null
        val index = indexFile(source)
        if (!index.isFile) return null
        val handle = IndexedRawNative.open(index.absolutePath, source.length(), source.lastModified())
        if (handle == 0L) return null
        val dimensions = IndexedRawNative.dimensions(handle)
        if (dimensions.size != 2 || dimensions[0] <= 0 || dimensions[1] <= 0) {
            IndexedRawNative.close(handle)
            return null
        }
        return IndexedRawRegionDecoder(handle, dimensions[0], dimensions[1])
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
        return source.takeIf { it.extension.lowercase() in RAW_EXTENSIONS }
    }

    private companion object {
        const val DIRECTORY_NAME = "indexed-raw"
        const val INDEX_SUFFIX = ".irx"
        const val RAW_TEMP_SUFFIX = ".ppm"
        val RAW_EXTENSIONS = setOf(
            "3fr", "arw", "bay", "cap", "cr2", "cr3", "crw", "dcr", "dcs", "dng",
            "drf", "eip", "erf", "fff", "gpr", "iiq", "k25", "kdc", "mdc", "mef",
            "mos", "mrw", "nef", "nrw", "obm", "orf", "pef", "ptx", "pxn", "r3d",
            "raf", "raw", "rw2", "rwl", "rwz", "sr2", "srf", "srw", "x3f",
        )
        val mutationLock = Any()
        val buildInProgress = AtomicBoolean(false)
        val generation = AtomicLong(0L)
    }
}

class IndexedRawRegionDecoder internal constructor(
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
            IndexedRawNative.decode(
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
        if (handle != 0L) IndexedRawNative.close(handle)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}

private object IndexedRawNative {
    init { System.loadLibrary("indexed-raw") }

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
