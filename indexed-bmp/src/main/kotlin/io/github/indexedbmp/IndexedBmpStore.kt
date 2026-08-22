package io.github.indexedbmp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface IndexedBmpStatus {
    data object Absent : IndexedBmpStatus
    data class Ready(val bytes: Long) : IndexedBmpStatus
    data class Unsupported(val reason: String) : IndexedBmpStatus
    data class Invalid(val reason: String) : IndexedBmpStatus
}

data class IndexedBmpInfo(
    val indexBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val bitsPerPixel: Int,
    val topDown: Boolean,
)

/** Opt-in activation of deterministic scan-line seeking already present in uncompressed BMP. */
class IndexedBmpStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    fun status(sourcePath: String): IndexedBmpStatus {
        val source = supportedSource(sourcePath)
            ?: return IndexedBmpStatus.Unsupported("A readable local BMP file is required")
        val index = indexFile(source)
        if (!index.isFile) return IndexedBmpStatus.Absent
        return readManifest(index)?.let { manifest ->
            if (manifest.matches(source)) IndexedBmpStatus.Ready(index.length())
            else IndexedBmpStatus.Invalid("The image changed or the BMP activation is incompatible")
        } ?: IndexedBmpStatus.Invalid("The BMP activation manifest is damaged")
    }

    @Throws(IOException::class)
    fun build(sourcePath: String): IndexedBmpInfo = synchronized(mutationLock) {
        val source = supportedSource(sourcePath) ?: throw IOException("A readable local BMP file is required")
        if (!buildInProgress.compareAndSet(false, true)) {
            throw IOException("Another BMP activation is already running")
        }
        try {
            val nativeInfo = IndexedBmpNative.probe(source.absolutePath)
            if (nativeInfo.size != NATIVE_INFO_FIELDS) {
                throw IOException("The native BMP probe returned incomplete metadata")
            }
            val manifest = Manifest(
                sourceBytes = source.length(),
                sourceModifiedMillis = source.lastModified(),
                width = nativeInfo[0].toInt(),
                height = nativeInfo[1].toInt(),
                pixelOffset = nativeInfo[2],
                rowStride = nativeInfo[3],
                bitsPerPixel = nativeInfo[4].toInt(),
                topDown = nativeInfo[5] != 0L,
            )
            directory.mkdirs()
            val destination = indexFile(source)
            val temporary = File(directory, destination.name + ".tmp-${System.nanoTime()}")
            try {
                writeManifest(temporary, manifest)
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous BMP activation")
                }
                if (!temporary.renameTo(destination)) throw IOException("Unable to publish the BMP activation")
                generation.incrementAndGet()
                return IndexedBmpInfo(
                    indexBytes = destination.length(),
                    sourceWidth = manifest.width,
                    sourceHeight = manifest.height,
                    bitsPerPixel = manifest.bitsPerPixel,
                    topDown = manifest.topDown,
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
            if (status(destinationPath) is IndexedBmpStatus.Ready) {
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

    fun openDecoder(sourcePath: String): IndexedBmpRegionDecoder? {
        val source = supportedSource(sourcePath) ?: return null
        val manifest = readManifest(indexFile(source)) ?: return null
        if (!manifest.matches(source)) return null
        val handle = IndexedBmpNative.open(
            source.absolutePath,
            manifest.width,
            manifest.height,
            manifest.pixelOffset,
            manifest.rowStride,
            manifest.bitsPerPixel,
            manifest.topDown,
        )
        return handle.takeIf { it != 0L }?.let {
            IndexedBmpRegionDecoder(it, manifest.width, manifest.height)
        }
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
        if (!source.isFile || !source.canRead() || source.length() < MINIMUM_BMP_BYTES) return null
        return try {
            source.inputStream().buffered().use { input ->
                if (input.read() == 'B'.code && input.read() == 'M'.code) source else null
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun writeManifest(destination: File, manifest: Manifest) {
        DataOutputStream(BufferedOutputStream(destination.outputStream())).use { output ->
            output.writeInt(MANIFEST_MAGIC)
            output.writeInt(MANIFEST_VERSION)
            output.writeLong(manifest.sourceBytes)
            output.writeLong(manifest.sourceModifiedMillis)
            output.writeInt(manifest.width)
            output.writeInt(manifest.height)
            output.writeLong(manifest.pixelOffset)
            output.writeLong(manifest.rowStride)
            output.writeInt(manifest.bitsPerPixel)
            output.writeInt(if (manifest.topDown) 1 else 0)
        }
    }

    private fun readManifest(source: File): Manifest? {
        if (!source.isFile || source.length() != MANIFEST_BYTES) return null
        return try {
            DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
                if (input.readInt() != MANIFEST_MAGIC || input.readInt() != MANIFEST_VERSION) return null
                Manifest(
                    sourceBytes = input.readLong(),
                    sourceModifiedMillis = input.readLong(),
                    width = input.readInt(),
                    height = input.readInt(),
                    pixelOffset = input.readLong(),
                    rowStride = input.readLong(),
                    bitsPerPixel = input.readInt(),
                    topDown = input.readInt() != 0,
                ).takeIf {
                    it.width > 0 && it.height > 0 && it.pixelOffset >= MINIMUM_BMP_BYTES &&
                        it.rowStride > 0 && it.bitsPerPixel in setOf(24, 32)
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    private data class Manifest(
        val sourceBytes: Long,
        val sourceModifiedMillis: Long,
        val width: Int,
        val height: Int,
        val pixelOffset: Long,
        val rowStride: Long,
        val bitsPerPixel: Int,
        val topDown: Boolean,
    ) {
        fun matches(source: File): Boolean =
            sourceBytes == source.length() && sourceModifiedMillis == source.lastModified()
    }

    private companion object {
        const val DIRECTORY_NAME = "indexed-bmp"
        const val INDEX_SUFFIX = ".ibx"
        const val MANIFEST_MAGIC = 0x49424d50 // IBMP
        const val MANIFEST_VERSION = 1
        const val MANIFEST_BYTES = 56L
        const val MINIMUM_BMP_BYTES = 54L
        const val NATIVE_INFO_FIELDS = 6
        val mutationLock = Any()
        val buildInProgress = AtomicBoolean(false)
        val generation = AtomicLong(0L)
    }
}

class IndexedBmpRegionDecoder internal constructor(
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
            IndexedBmpNative.decode(handle, rect.left, rect.top, rect.right, rect.bottom, sampleSize, bitmap)
        ) bitmap else {
            bitmap.recycle()
            null
        }
    }

    override fun close() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) IndexedBmpNative.close(handle)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}

private object IndexedBmpNative {
    init { System.loadLibrary("indexed-bmp") }

    external fun probe(sourcePath: String): LongArray
    external fun open(
        sourcePath: String,
        width: Int,
        height: Int,
        pixelOffset: Long,
        rowStride: Long,
        bitsPerPixel: Int,
        topDown: Boolean,
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
