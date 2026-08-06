package io.github.indexedtiff

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

sealed interface IndexedTiffStatus {
    data object Absent : IndexedTiffStatus
    data class Ready(val bytes: Long) : IndexedTiffStatus
    data class Unsupported(val reason: String) : IndexedTiffStatus
    data class Invalid(val reason: String) : IndexedTiffStatus
}

data class IndexedTiffInfo(
    val indexBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val levelCount: Int,
    val storageMode: StorageMode,
    val blockWidth: Int,
    val blockHeight: Int,
    val compression: Int,
) {
    enum class StorageMode { TILES, STRIPS }
}

/**
 * Opt-in activation of the random-access structure already stored in a TIFF.
 *
 * TIFF tile/strip offsets and optional reduced-resolution IFDs are the index. Building does not
 * transcode the image: it validates that those blocks can be decoded safely and writes a tiny,
 * source-bound manifest. TIFF variants that would require an incorrect 8-bit conversion fail
 * closed and remain on the host's normal decoder.
 */
class IndexedTiffStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    fun status(sourcePath: String): IndexedTiffStatus {
        val source = supportedSource(sourcePath)
            ?: return IndexedTiffStatus.Unsupported("A readable local TIFF or BigTIFF file is required")
        val index = indexFile(source)
        if (!index.isFile) return IndexedTiffStatus.Absent
        return readManifest(index)?.let { manifest ->
            if (manifest.matches(source)) {
                IndexedTiffStatus.Ready(index.length())
            } else {
                IndexedTiffStatus.Invalid("The image changed or the TIFF activation is incompatible")
            }
        } ?: IndexedTiffStatus.Invalid("The TIFF activation manifest is damaged")
    }

    @Throws(IOException::class)
    fun build(sourcePath: String): IndexedTiffInfo {
        val source = supportedSource(sourcePath)
            ?: throw IOException("A readable local TIFF or BigTIFF file is required")
        if (!buildInProgress.compareAndSet(false, true)) {
            throw IOException("Another TIFF activation is already running")
        }
        try {
            val nativeInfo = IndexedTiffNative.probe(source.absolutePath)
            if (nativeInfo.size != NATIVE_INFO_FIELDS) {
                throw IOException("The native TIFF probe returned incomplete metadata")
            }
            val manifest = Manifest(
                sourceBytes = source.length(),
                sourceModifiedMillis = source.lastModified(),
                width = nativeInfo[0],
                height = nativeInfo[1],
                levelCount = nativeInfo[2],
                mode = nativeInfo[3],
                blockWidth = nativeInfo[4],
                blockHeight = nativeInfo[5],
                compression = nativeInfo[6],
            )
            directory.mkdirs()
            val destination = indexFile(source)
            val temporary = File(directory, destination.name + ".tmp-${System.nanoTime()}")
            try {
                writeManifest(temporary, manifest)
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the previous TIFF activation")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to publish the TIFF activation")
                }
                generation.incrementAndGet()
                return IndexedTiffInfo(
                    indexBytes = destination.length(),
                    sourceWidth = manifest.width,
                    sourceHeight = manifest.height,
                    levelCount = manifest.levelCount,
                    storageMode = if (manifest.mode == MODE_TILES) {
                        IndexedTiffInfo.StorageMode.TILES
                    } else {
                        IndexedTiffInfo.StorageMode.STRIPS
                    },
                    blockWidth = manifest.blockWidth,
                    blockHeight = manifest.blockHeight,
                    compression = manifest.compression,
                )
            } finally {
                temporary.delete()
            }
        } finally {
            buildInProgress.set(false)
        }
    }

    fun delete(sourcePath: String): Boolean {
        val deleted = indexFile(File(sourcePath)).let { !it.exists() || it.delete() }
        if (deleted) generation.incrementAndGet()
        return deleted
    }

    fun openDecoder(sourcePath: String): IndexedTiffRegionDecoder? {
        val source = supportedSource(sourcePath) ?: return null
        val manifest = readManifest(indexFile(source)) ?: return null
        if (!manifest.matches(source)) return null
        val handle = IndexedTiffNative.open(source.absolutePath)
        return handle.takeIf { it != 0L }?.let(::IndexedTiffRegionDecoder)
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
                val signature = ByteArray(4)
                if (input.read(signature) == signature.size && TIFF_SIGNATURES.any(signature::contentEquals)) {
                    source
                } else {
                    null
                }
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
            output.writeInt(manifest.levelCount)
            output.writeInt(manifest.mode)
            output.writeInt(manifest.blockWidth)
            output.writeInt(manifest.blockHeight)
            output.writeInt(manifest.compression)
        }
    }

    private fun readManifest(source: File): Manifest? {
        if (!source.isFile || source.length() != MANIFEST_BYTES) return null
        return try {
            DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
                if (input.readInt() != MANIFEST_MAGIC || input.readInt() != MANIFEST_VERSION) {
                    return null
                }
                Manifest(
                    sourceBytes = input.readLong(),
                    sourceModifiedMillis = input.readLong(),
                    width = input.readInt(),
                    height = input.readInt(),
                    levelCount = input.readInt(),
                    mode = input.readInt(),
                    blockWidth = input.readInt(),
                    blockHeight = input.readInt(),
                    compression = input.readInt(),
                ).takeIf {
                    it.width > 0 && it.height > 0 && it.levelCount > 0 &&
                        (it.mode == MODE_TILES || it.mode == MODE_STRIPS) &&
                        it.blockWidth > 0 && it.blockHeight > 0
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
        val levelCount: Int,
        val mode: Int,
        val blockWidth: Int,
        val blockHeight: Int,
        val compression: Int,
    ) {
        fun matches(source: File): Boolean =
            sourceBytes == source.length() && sourceModifiedMillis == source.lastModified()
    }

    private companion object {
        const val DIRECTORY_NAME = "indexed-tiff"
        const val INDEX_SUFFIX = ".itx"
        const val MANIFEST_MAGIC = 0x49544946 // ITIF
        const val MANIFEST_VERSION = 1
        const val MANIFEST_BYTES = 52L
        const val NATIVE_INFO_FIELDS = 7
        const val MODE_TILES = 1
        const val MODE_STRIPS = 2
        val TIFF_SIGNATURES = arrayOf(
            byteArrayOf(0x49, 0x49, 0x2a, 0x00),
            byteArrayOf(0x4d, 0x4d, 0x00, 0x2a),
            byteArrayOf(0x49, 0x49, 0x2b, 0x00),
            byteArrayOf(0x4d, 0x4d, 0x00, 0x2b),
        )
        val buildInProgress = AtomicBoolean(false)
        val generation = AtomicLong(0L)
    }
}

class IndexedTiffRegionDecoder internal constructor(
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
            IndexedTiffNative.decode(
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
        if (handle != 0L) IndexedTiffNative.close(handle)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}

private object IndexedTiffNative {
    init {
        // imageOps is the public AAR's JNI entry point and loads its libtiff dependency.
        System.loadLibrary("imageOps")
        System.loadLibrary("indexed-tiff")
    }

    external fun probe(sourcePath: String): IntArray
    external fun open(sourcePath: String): Long
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
