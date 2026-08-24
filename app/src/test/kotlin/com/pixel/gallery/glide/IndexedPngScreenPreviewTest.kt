package com.pixel.gallery.glide

import io.github.indexedpng.IndexedPngSourceCompatibility
import io.github.indexedpng.IndexedPngSourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32

class IndexedPngScreenPreviewTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun untaggedStillPngUsesIndexedPreview() {
        assertCompatibility(
            IndexedPngSourceCompatibility.SDR_SRGB_STILL,
            pngFile(chunk("IDAT")),
        )
    }

    @Test
    fun explicitSrgbTakesPrecedenceOverLegacyChunks() {
        assertCompatibility(
            IndexedPngSourceCompatibility.SDR_SRGB_STILL,
            pngFile(
                chunk("sRGB", byteArrayOf(0)),
                chunk("gAMA", integers(100_000)),
                chunk("cHRM", integers(1, 2, 3, 4, 5, 6, 7, 8)),
                chunk("IDAT"),
            ),
        )
    }

    @Test
    fun roundedStandardGammaAndChromaticitiesUseIndexedPreview() {
        // Values used by the 122 MB Han Western Regions test map. They differ from the canonical
        // PNG sRGB values only by encoder rounding in the final decimal place.
        assertCompatibility(
            IndexedPngSourceCompatibility.SDR_SRGB_STILL,
            pngFile(
                chunk("gAMA", integers(45_454)),
                chunk(
                    "cHRM",
                    integers(31_269, 32_899, 63_999, 33_001, 30_000, 60_000, 15_000, 5_999),
                ),
                chunk("IDAT"),
            ),
        )
    }

    @Test
    fun fullRangeSrgbCicpUsesIndexedPreview() {
        assertCompatibility(
            IndexedPngSourceCompatibility.SDR_SRGB_STILL,
            pngFile(chunk("cICP", byteArrayOf(1, 13, 0, 1)), chunk("IDAT")),
        )
    }

    @Test
    fun wideGamutOrLimitedRangeCicpUsesSourceDecoder() {
        assertCompatibility(
            IndexedPngSourceCompatibility.NON_SRGB_COLOR,
            pngFile(chunk("cICP", byteArrayOf(12, 13, 0, 1)), chunk("IDAT")),
        )
        assertCompatibility(
            IndexedPngSourceCompatibility.NON_SRGB_COLOR,
            pngFile(chunk("cICP", byteArrayOf(1, 13, 0, 0)), chunk("IDAT")),
        )
    }

    @Test
    fun profileOrIncompleteLegacyColourMetadataUsesSourceDecoder() {
        assertCompatibility(
            IndexedPngSourceCompatibility.ICC_PROFILE,
            pngFile(chunk("iCCP", byteArrayOf(1)), chunk("IDAT")),
        )
        assertCompatibility(
            IndexedPngSourceCompatibility.NON_SRGB_COLOR,
            pngFile(chunk("gAMA", integers(45_455)), chunk("IDAT")),
        )
        assertCompatibility(
            IndexedPngSourceCompatibility.NON_SRGB_COLOR,
            pngFile(
                chunk("gAMA", integers(100_000)),
                chunk(
                    "cHRM",
                    integers(31_270, 32_900, 64_000, 33_000, 30_000, 60_000, 15_000, 6_000),
                ),
                chunk("IDAT"),
            ),
        )
    }

    @Test
    fun animationAndMasteringMetadataUseSourceDecoder() {
        assertCompatibility(
            IndexedPngSourceCompatibility.ANIMATED,
            pngFile(chunk("acTL", integers(1, 0)), chunk("IDAT")),
        )
        assertCompatibility(
            IndexedPngSourceCompatibility.MASTERING_METADATA,
            pngFile(chunk("cICP", byteArrayOf(9, 16, 0, 1)), chunk("mDCV"), chunk("IDAT")),
        )
    }

    @Test
    fun malformedOrderingOrCriticalChunksFailClosed() {
        assertCompatibility(
            IndexedPngSourceCompatibility.MALFORMED,
            pngFile(chunk("IDAT"), chunk("gAMA", integers(45_455))),
        )
        assertCompatibility(
            IndexedPngSourceCompatibility.MALFORMED,
            pngFile(chunk("ABCD"), chunk("IDAT")),
        )
        val invalidSignature = pngFile(chunk("IDAT")).apply {
            outputStream().use { it.write(ByteArray(8)) }
        }
        assertCompatibility(IndexedPngSourceCompatibility.MALFORMED, invalidSignature)
    }

    @Test
    fun fitScreenSamplingUsesPowerOfTwoAndOrientedDimensions() {
        assertEquals(8, fitScreenSampleSize(9_419, 7_462, 0, 976, 1_852))
        assertEquals(1, fitScreenSampleSize(3_000, 1_000, 90, 1_000, 2_000))
        assertEquals(1, fitScreenSampleSize(0, 1_000, 0, 1_000, 2_000))
    }

    private fun assertCompatibility(expected: IndexedPngSourceCompatibility, file: File) {
        assertEquals(expected, IndexedPngSourcePolicy.inspect(file.absolutePath))
    }

    private fun pngFile(vararg contentChunks: Chunk): File = temporaryFolder.newFile().also { file ->
        DataOutputStream(FileOutputStream(file)).use { output ->
            output.write(PNG_SIGNATURE)
            output.writeChunk(chunk("IHDR", integers(1, 1) + byteArrayOf(8, 6, 0, 0, 0)))
            contentChunks.forEach { chunk -> output.writeChunk(chunk) }
            output.writeChunk(chunk("IEND"))
        }
    }

    private fun DataOutputStream.writeChunk(chunk: Chunk) {
        val type = chunk.type.toByteArray(Charsets.US_ASCII)
        writeInt(chunk.data.size)
        write(type)
        write(chunk.data)
        val crc = CRC32().apply {
            update(type)
            update(chunk.data)
        }
        writeInt(crc.value.toInt())
    }

    private fun chunk(type: String, data: ByteArray = byteArrayOf()) = Chunk(type, data)

    private fun integers(vararg values: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output -> values.forEach(output::writeInt) }
        bytes.toByteArray()
    }

    private data class Chunk(val type: String, val data: ByteArray)

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
