package com.pixel.gallery.glide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class IndexedPngScreenPreviewTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun untaggedStillPngUsesIndexedPreview() {
        assertTrue(isSdrSrgbStillPng(pngFile("IHDR", "IDAT").absolutePath))
    }

    @Test
    fun explicitSrgbMayIncludeStandardGammaChunks() {
        assertTrue(isSdrSrgbStillPng(pngFile("IHDR", "sRGB", "gAMA", "cHRM", "IDAT").absolutePath))
    }

    @Test
    fun profileOrUntypedChromaticitiesUseSourceDecoder() {
        assertFalse(isSdrSrgbStillPng(pngFile("IHDR", "iCCP", "IDAT").absolutePath))
        assertFalse(isSdrSrgbStillPng(pngFile("IHDR", "gAMA", "IDAT").absolutePath))
    }

    @Test
    fun animatedPngUsesSourceDecoder() {
        assertFalse(isSdrSrgbStillPng(pngFile("IHDR", "acTL", "IDAT").absolutePath))
    }

    private fun pngFile(vararg chunkTypes: String): File = temporaryFolder.newFile().also { file ->
        DataOutputStream(FileOutputStream(file)).use { output ->
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            chunkTypes.forEach { type ->
                output.writeInt(0)
                output.write(type.toByteArray(Charsets.US_ASCII))
                output.writeInt(0)
            }
        }
    }
}
