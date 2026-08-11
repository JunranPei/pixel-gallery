package io.github.indexedheif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface IndexedHeifStatus {
    data object Absent : IndexedHeifStatus
    data class Ready(val bytes: Long) : IndexedHeifStatus
    data class Unsupported(val reason: String) : IndexedHeifStatus
    data class Invalid(val reason: String) : IndexedHeifStatus
}

data class IndexedHeifInfo(
    val indexBytes: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val levelCount: Int,
    val tileCount: Int,
)

/** Explicit lossless tile index for the primary still image exposed by Android's HEIF/AVIF codec. */
class IndexedHeifStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    fun status(sourcePath: String): IndexedHeifStatus {
        val source = supportedSource(sourcePath)
            ?: return IndexedHeifStatus.Unsupported("A readable local HEIF/HEIC/AVIF file is required")
        val index = indexFile(source)
        if (!index.isFile) return IndexedHeifStatus.Absent
        return if (IndexedHeifNative.validateIndex(index.absolutePath, source.length(), source.lastModified())) {
            IndexedHeifStatus.Ready(index.length())
        } else {
            IndexedHeifStatus.Invalid("The image changed or the index is incompatible")
        }
    }

    @Throws(IOException::class)
    fun build(sourcePath: String): IndexedHeifInfo {
        val source = supportedSource(sourcePath)
            ?: throw IOException("A readable local HEIF/HEIC/AVIF file is required")
        if (!buildInProgress.compareAndSet(false, true)) {
            throw IOException("Another HEIF/AVIF index build is already running")
        }
        try {
            directory.mkdirs()
            val destination = indexFile(source)
            val temporary = File(directory, destination.name + ".tmp-${System.nanoTime()}")
            var builder = 0L
            try {
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(source.absolutePath, false)
                    ?: throw IOException("The Android device cannot decode this HEIF/AVIF source")
                decoder.useCompat {
                    val width = decoder.width
                    val height = decoder.height
                    if (width <= 0 || height <= 0) throw IOException("Invalid HEIF/AVIF dimensions")
                    builder = IndexedHeifNative.beginBuild(
                        temporary.absolutePath,
                        source.length(),
                        source.lastModified(),
                        width,
                        height,
                    )
                    if (builder == 0L) throw IOException("Unable to create the HEIF/AVIF index writer")
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    for (top in 0 until height step TILE_SIZE) {
                        for (left in 0 until width step TILE_SIZE) {
                            val rect = Rect(
                                left,
                                top,
                                minOf(width, left + TILE_SIZE),
                                minOf(height, top + TILE_SIZE),
                            )
                            val tile = decoder.decodeRegion(rect, options)
                                ?: throw IOException("Platform HEIF/AVIF region decode returned no pixels")
                            try {
                                if (tile.width != rect.width() || tile.height != rect.height()) {
                                    throw IOException(
                                        "Unexpected HEIF/AVIF tile size ${tile.width}x${tile.height} " +
                                            "for ${rect.width()}x${rect.height()}",
                                    )
                                }
                                if (!IndexedHeifNative.appendBaseTile(builder, left, top, tile)) {
                                    throw IOException("Unable to append a HEIF/AVIF index tile")
                                }
                            } finally {
                                tile.recycle()
                            }
                        }
                    }
                    val nativeInfo = IndexedHeifNative.finishBuild(builder)
                    if (nativeInfo.size != 4) throw IOException("Invalid HEIF/AVIF index result")
                    if (destination.exists() && !destination.delete()) {
                        throw IOException("Unable to replace the previous HEIF/AVIF index")
                    }
                    if (!temporary.renameTo(destination)) {
                        throw IOException("Unable to publish the completed HEIF/AVIF index")
                    }
                    generation.incrementAndGet()
                    return IndexedHeifInfo(
                        indexBytes = destination.length(),
                        sourceWidth = nativeInfo[0],
                        sourceHeight = nativeInfo[1],
                        levelCount = nativeInfo[2],
                        tileCount = nativeInfo[3],
                    )
                }
            } catch (error: IOException) {
                throw error
            } catch (error: Throwable) {
                throw IOException("Unable to build HEIF/AVIF index: ${error.message}", error)
            } finally {
                if (builder != 0L) IndexedHeifNative.closeBuilder(builder)
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

    fun openDecoder(sourcePath: String): IndexedHeifRegionDecoder? {
        val source = supportedSource(sourcePath) ?: return null
        val index = indexFile(source)
        if (!index.isFile) return null
        val handle = IndexedHeifNative.open(index.absolutePath, source.length(), source.lastModified())
        return handle.takeIf { it != 0L }?.let(::IndexedHeifRegionDecoder)
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
        return source.takeIf {
            it.extension.lowercase() in EXTENSIONS || HeifFileType.hasCompatibleBrand(it)
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "indexed-heif"
        const val INDEX_SUFFIX = ".ihx"
        const val TILE_SIZE = 512
        val EXTENSIONS = setOf("heic", "heif", "hif", "avif")
        val buildInProgress = AtomicBoolean(false)
        val generation = AtomicLong(0L)
    }
}

class IndexedHeifRegionDecoder internal constructor(
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
            IndexedHeifNative.decode(
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
        if (handle != 0L) IndexedHeifNative.close(handle)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}

private inline fun <T> BitmapRegionDecoder.useCompat(block: (BitmapRegionDecoder) -> T): T {
    try {
        return block(this)
    } finally {
        recycle()
    }
}

private object IndexedHeifNative {
    init { System.loadLibrary("indexed-heif") }

    external fun beginBuild(
        destinationPath: String,
        sourceBytes: Long,
        sourceModifiedMillis: Long,
        width: Int,
        height: Int,
    ): Long
    external fun appendBaseTile(handle: Long, left: Int, top: Int, bitmap: Bitmap): Boolean
    external fun finishBuild(handle: Long): IntArray
    external fun closeBuilder(handle: Long)
    external fun validateIndex(indexPath: String, sourceBytes: Long, sourceModifiedMillis: Long): Boolean
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
