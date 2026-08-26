package io.github.indexedpng

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater

@RunWith(AndroidJUnit4::class)
class IndexedPngStoreInstrumentedTest {
    @Test
    fun corruptedTilePayloadFailsClosed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-png-corrupt-fixture.png")
        createRgbFixture(source, width = 37, height = 29)
        val store = IndexedPngStore(context)
        store.delete(source.absolutePath)
        store.build(source.absolutePath)

        val key = MessageDigest.getInstance("SHA-256")
            .digest(source.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val index = File(context.noBackupFilesDir, "indexed-png/$key.ipx")
        RandomAccessFile(index, "rw").use { file ->
            file.seek(64)
            val payloadOffset = java.lang.Long.reverseBytes(file.readLong())
            file.seek(payloadOffset)
            file.writeByte(file.readUnsignedByte() xor 0x40)
        }

        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            assertEquals(null, decoder!!.decodeRegion(Rect(0, 0, 20, 20), 1))
        }
        assertTrue(store.delete(source.absolutePath))
    }

    @Test
    fun opaqueRgbPngBuildsAndDecodesWithoutInventingTransparency() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-png-rgb-fixture.png")
        createRgbFixture(source, width = 37, height = 29)
        val store = IndexedPngStore(context)
        store.delete(source.absolutePath)
        val unrelated = File(context.cacheDir, "indexed-png-unrelated.png")
        val unrelatedGeneration = store.currentGenerationFor(unrelated.absolutePath)
        val sourceGeneration = store.currentGenerationFor(source.absolutePath)

        val info = store.build(source.absolutePath)
        assertTrue(store.currentGenerationFor(source.absolutePath) > sourceGeneration)
        assertEquals(unrelatedGeneration, store.currentGenerationFor(unrelated.absolutePath))
        assertEquals(37, info.sourceWidth)
        assertEquals(29, info.sourceHeight)
        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(3, 5, 34, 27)
            val bitmap = decoder!!.decodeRegion(region, 1)
            assertNotNull(bitmap)
            bitmap!!
            for (point in listOf(0 to 0, 15 to 10, 30 to 21)) {
                val sourceX = region.left + point.first
                val sourceY = region.top + point.second
                assertEquals(
                    Color.rgb(
                        (sourceX * 7 + sourceY * 3) and 0xff,
                        (sourceX * 5 + sourceY * 11) and 0xff,
                        (sourceX * 13 + sourceY) and 0xff,
                    ),
                    bitmap.getPixel(point.first, point.second),
                )
            }
            bitmap.recycle()
            assertDecodedSize(decoder.decodeRegion(region, 2), 16, 11)
            assertDecodedSize(decoder.decodeRegion(region, 4), 8, 6)
        }
        assertTrue(store.delete(source.absolutePath))
    }

    @Test
    fun truecolorTransparencyStaysTransparentAndPremultiplied() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-png-trns-fixture.png")
        val transparentX = 7
        val transparentY = 5
        val transparentColor = intArrayOf(
            (transparentX * 7 + transparentY * 3) and 0xff,
            (transparentX * 5 + transparentY * 11) and 0xff,
            (transparentX * 13 + transparentY) and 0xff,
        )
        createRgbFixture(source, width = 23, height = 17, transparentColor = transparentColor)
        val store = IndexedPngStore(context)
        store.delete(source.absolutePath)
        store.build(source.absolutePath)

        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val bitmap = decoder!!.decodeRegion(Rect(0, 0, 23, 17), 1)
            assertNotNull(bitmap)
            bitmap!!
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(transparentX, transparentY))
            assertEquals(255, Color.alpha(bitmap.getPixel(0, 0)))
            bitmap.recycle()
        }
        assertTrue(store.delete(source.absolutePath))
    }

    @Test
    fun buildPersistDecodeAcrossTilesAndSamplesThenDelete() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-png-fixture.png")
        createFixture(source)
        val store = IndexedPngStore(context)
        store.delete(source.absolutePath)

        assertEquals(IndexedPngStatus.Absent, store.status(source.absolutePath))
        val info = store.build(source.absolutePath)
        assertEquals(1100, info.sourceWidth)
        assertEquals(700, info.sourceHeight)
        assertTrue(info.levelCount > 1)
        assertTrue(info.tileCount > info.levelCount)
        assertTrue(info.indexBytes > 0L)
        assertTrue(store.status(source.absolutePath) is IndexedPngStatus.Ready)

        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(480, 240, 1050, 690)
            val sampleOne = decoder!!.decodeRegion(region, 1)
            assertNotNull(sampleOne)
            sampleOne!!
            assertEquals(570, sampleOne.width)
            assertEquals(450, sampleOne.height)
            val sourceX = 480 + sampleOne.width / 2
            val sourceY = 240 + sampleOne.height / 2
            val expected = Color.argb(
                255,
                (sourceX * 3 + sourceY) and 0xff,
                (sourceY * 5 + sourceX / 3) and 0xff,
                (sourceX + sourceY * 2) and 0xff,
            )
            assertEquals(expected, sampleOne.getPixel(sampleOne.width / 2, sampleOne.height / 2))
            sampleOne.recycle()
            assertDecodedSize(decoder.decodeRegion(region, 2), 285, 225)
            assertDecodedSize(decoder.decodeRegion(region, 4), 143, 113)
            assertDecodedSize(decoder.decodeRegion(region, 16), 36, 29)
        }

        val relocated = File(context.cacheDir, "indexed-png-fixture-relocated.png")
        store.delete(relocated.absolutePath)
        relocated.delete()
        assertTrue(source.renameTo(relocated))
        assertTrue(store.relocate(source.absolutePath, relocated.absolutePath))
        assertTrue(store.status(relocated.absolutePath) is IndexedPngStatus.Ready)
        assertTrue(relocated.renameTo(source))
        assertTrue(store.relocate(relocated.absolutePath, source.absolutePath))
        assertTrue(store.status(source.absolutePath) is IndexedPngStatus.Ready)

        assertTrue(source.setLastModified(source.lastModified() + 2_000L))
        assertTrue(store.status(source.absolutePath) is IndexedPngStatus.Invalid)
        assertEquals(null, store.openDecoder(source.absolutePath))
        assertTrue(store.delete(source.absolutePath))
        assertEquals(IndexedPngStatus.Absent, store.status(source.absolutePath))
    }

    @Test
    fun adam7PngBuildsAndDecodes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-png-adam7-fixture.png")
        context.assets.open("interlaced-fixture.png").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val store = IndexedPngStore(context)
        store.delete(source.absolutePath)

        val info = store.build(source.absolutePath)
        assertTrue(info.sourceWidth > 0)
        assertTrue(info.sourceHeight > 0)
        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(0, 0, info.sourceWidth, info.sourceHeight)
            val sampleOne = decoder!!.decodeRegion(region, 1)
            assertNotNull(sampleOne)
            sampleOne!!
            val centerX = info.sourceWidth / 2
            val centerY = info.sourceHeight / 2
            val actual = sampleOne.getPixel(centerX, centerY)
            assertChannelNear(128 + ((centerX + centerY) and 0x7f), Color.alpha(actual))
            assertChannelNear((centerX * 7 + centerY) and 0xff, Color.red(actual))
            assertChannelNear((centerY * 11 + centerX) and 0xff, Color.green(actual))
            assertChannelNear((centerX * 3 + centerY * 5) and 0xff, Color.blue(actual))
            sampleOne.recycle()
            assertDecodedSize(
                decoder.decodeRegion(region, 2),
                (info.sourceWidth + 1) / 2,
                (info.sourceHeight + 1) / 2,
            )
        }
        assertTrue(store.delete(source.absolutePath))
    }

    private fun assertChannelNear(expected: Int, actual: Int) {
        assertTrue("expected=$expected actual=$actual", kotlin.math.abs(expected - actual) <= 2)
    }

    private fun assertDecodedSize(bitmap: Bitmap?, width: Int, height: Int) {
        assertNotNull(bitmap)
        bitmap!!
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        assertTrue(Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 0)
        bitmap.recycle()
    }

    private fun createFixture(destination: File) {
        val bitmap = Bitmap.createBitmap(1100, 700, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(
                    x,
                    y,
                    Color.argb(
                        255,
                        (x * 3 + y) and 0xff,
                        (y * 5 + x / 3) and 0xff,
                        (x + y * 2) and 0xff,
                    ),
                )
            }
        }
        FileOutputStream(destination).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }

    private fun createRgbFixture(
        destination: File,
        width: Int,
        height: Int,
        transparentColor: IntArray? = null,
    ) {
        val scanlines = ByteArray((width * 3 + 1) * height)
        for (y in 0 until height) {
            val row = y * (width * 3 + 1)
            scanlines[row] = 0
            for (x in 0 until width) {
                val pixel = row + 1 + x * 3
                scanlines[pixel] = (x * 7 + y * 3).toByte()
                scanlines[pixel + 1] = (x * 5 + y * 11).toByte()
                scanlines[pixel + 2] = (x * 13 + y).toByte()
            }
        }
        val compressor = Deflater(6)
        compressor.setInput(scanlines)
        compressor.finish()
        val compressed = ByteArray(scanlines.size + 128)
        val compressedBytes = compressor.deflate(compressed)
        compressor.end()

        DataOutputStream(FileOutputStream(destination)).use { output ->
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            val header = java.io.ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.writeInt(width)
                    data.writeInt(height)
                    data.writeByte(8)
                    data.writeByte(2)
                    data.writeByte(0)
                    data.writeByte(0)
                    data.writeByte(0)
                }
            }.toByteArray()
            writePngChunk(output, "IHDR", header)
            transparentColor?.let { color ->
                val transparency = java.io.ByteArrayOutputStream().also { bytes ->
                    DataOutputStream(bytes).use { data ->
                        data.writeShort(color[0])
                        data.writeShort(color[1])
                        data.writeShort(color[2])
                    }
                }.toByteArray()
                writePngChunk(output, "tRNS", transparency)
            }
            writePngChunk(output, "IDAT", compressed.copyOf(compressedBytes))
            writePngChunk(output, "IEND", byteArrayOf())
        }
    }

    private fun writePngChunk(output: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(data.size)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        output.writeInt(crc.value.toInt())
    }
}
