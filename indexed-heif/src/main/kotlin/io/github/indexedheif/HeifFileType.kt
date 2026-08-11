package io.github.indexedheif

import java.io.File
import java.io.IOException

/**
 * Identifies HEIF-derived images from their ISO Base Media File Format `ftyp`
 * box, without relying on a filename extension or MIME type supplied by a
 * media provider.
 */
object HeifFileType {
    private const val HEADER_BYTES = 4 * 1024
    private val compatibleBrands = setOf(
        "mif1", "msf1", // generic HEIF
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", // HEVC HEIF
        "avif", "avis", // AVIF
    )

    fun hasCompatibleBrand(source: File): Boolean {
        if (!source.isFile || !source.canRead() || source.length() < 16L) return false
        return try {
            source.inputStream().buffered().use { input ->
                val header = ByteArray(minOf(HEADER_BYTES.toLong(), source.length()).toInt())
                val bytesRead = input.read(header)
                bytesRead >= 16 && isCompatibleFileTypeBox(header, bytesRead)
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun isCompatibleFileTypeBox(bytes: ByteArray, length: Int): Boolean {
        if (asciiAt(bytes, 4, length) != "ftyp") return false
        val boxSize = uint32At(bytes, 0)
        if (boxSize < 16L || boxSize > length.toLong()) return false
        val endOffset = boxSize.toInt()
        for (offset in 8 until endOffset step 4) {
            if (offset + 4 <= endOffset && asciiAt(bytes, offset, length) in compatibleBrands) {
                return true
            }
        }
        return false
    }

    private fun uint32At(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun asciiAt(bytes: ByteArray, offset: Int, length: Int): String? =
        if (offset + 4 <= length) {
            String(bytes, offset, 4, Charsets.US_ASCII)
        } else {
            null
        }
}
