package io.github.indexedtiff

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
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class IndexedTiffStoreInstrumentedTest {
    @Test
    fun activatesAndDecodesNativeTilesAcrossSamples() {
        exerciseFixture(tiled = true)
    }

    @Test
    fun activatesAndDecodesNativeStripsAcrossSamples() {
        exerciseFixture(tiled = false)
    }

    private fun exerciseFixture(tiled: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-tiff-${if (tiled) "tiles" else "strips"}.tif")
        writeRgbTiff(source, width = 383, height = 271, tiled = tiled)
        val store = IndexedTiffStore(context)
        store.delete(source.absolutePath)

        assertEquals(IndexedTiffStatus.Absent, store.status(source.absolutePath))
        val info = store.build(source.absolutePath)
        assertEquals(383, info.sourceWidth)
        assertEquals(271, info.sourceHeight)
        assertEquals(1, info.levelCount)
        assertEquals(
            if (tiled) IndexedTiffInfo.StorageMode.TILES else IndexedTiffInfo.StorageMode.STRIPS,
            info.storageMode,
        )
        assertTrue(info.indexBytes > 0L)
        assertTrue(store.status(source.absolutePath) is IndexedTiffStatus.Ready)

        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(93, 47, 357, 253)
            assertRegion(decoder!!.decodeRegion(region, 1), region, 1)
            assertRegion(decoder.decodeRegion(region, 2), region, 2)
            assertRegion(decoder.decodeRegion(region, 4), region, 4)
        }

        val relocated = File(
            context.cacheDir,
            "indexed-tiff-${if (tiled) "tiles" else "strips"}-relocated.tif",
        )
        store.delete(relocated.absolutePath)
        relocated.delete()
        assertTrue(source.renameTo(relocated))
        assertTrue(store.relocate(source.absolutePath, relocated.absolutePath))
        assertTrue(store.status(relocated.absolutePath) is IndexedTiffStatus.Ready)
        assertTrue(relocated.renameTo(source))
        assertTrue(store.relocate(relocated.absolutePath, source.absolutePath))
        assertTrue(store.status(source.absolutePath) is IndexedTiffStatus.Ready)

        assertTrue(source.setLastModified(source.lastModified() + 2_000L))
        assertTrue(store.status(source.absolutePath) is IndexedTiffStatus.Invalid)
        assertEquals(null, store.openDecoder(source.absolutePath))
        assertTrue(store.delete(source.absolutePath))
        assertEquals(IndexedTiffStatus.Absent, store.status(source.absolutePath))
    }

    private fun assertRegion(bitmap: Bitmap?, region: Rect, sample: Int) {
        assertNotNull(bitmap)
        bitmap!!
        val expectedWidth = (region.width() + sample - 1) / sample
        val expectedHeight = (region.height() + sample - 1) / sample
        assertEquals(expectedWidth, bitmap.width)
        assertEquals(expectedHeight, bitmap.height)
        val outputX = bitmap.width / 2
        val outputY = bitmap.height / 2
        val sourceX = region.left + outputX * sample
        val sourceY = region.top + outputY * sample
        assertEquals(expectedColor(sourceX, sourceY), bitmap.getPixel(outputX, outputY))
        bitmap.recycle()
    }

    private fun expectedColor(x: Int, y: Int): Int = Color.rgb(
        (x * 3 + y) and 0xff,
        (y * 5 + x / 3) and 0xff,
        (x + y * 2) and 0xff,
    )

    private fun writeRgbTiff(destination: File, width: Int, height: Int, tiled: Boolean) {
        val blockWidth = if (tiled) 128 else width
        val blockHeight = if (tiled) 128 else 32
        val columns = (width + blockWidth - 1) / blockWidth
        val rows = (height + blockHeight - 1) / blockHeight
        val blockCount = columns * rows
        val entryCount = if (tiled) 13 else 12
        val ifdOffset = 8
        val ifdBytes = 2 + entryCount * 12 + 4
        val bitsOffset = ifdOffset + ifdBytes
        val blockOffsetsArrayOffset = align4(bitsOffset + 6)
        val blockByteCountsArrayOffset = blockOffsetsArrayOffset + blockCount * 4
        val pixelDataOffset = align4(blockByteCountsArrayOffset + blockCount * 4)
        val blockBytes = blockWidth * blockHeight * 3
        val totalBytes = pixelDataOffset + blockCount * blockBytes
        val output = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)

        output.put(0x49).put(0x49).putShort(42).putInt(ifdOffset)
        output.position(ifdOffset)
        output.putShort(entryCount.toShort())
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            output.putShort(tag.toShort())
            output.putShort(type.toShort())
            output.putInt(count)
            if (type == TYPE_SHORT && count == 1) {
                output.putShort(value.toShort()).putShort(0)
            } else {
                output.putInt(value)
            }
        }
        entry(256, TYPE_LONG, 1, width)
        entry(257, TYPE_LONG, 1, height)
        entry(258, TYPE_SHORT, 3, bitsOffset)
        entry(259, TYPE_SHORT, 1, 1)
        entry(262, TYPE_SHORT, 1, 2)
        if (!tiled) entry(273, TYPE_LONG, blockCount, blockOffsetsArrayOffset)
        entry(274, TYPE_SHORT, 1, 1)
        entry(277, TYPE_SHORT, 1, 3)
        if (!tiled) entry(278, TYPE_LONG, 1, blockHeight)
        if (!tiled) entry(279, TYPE_LONG, blockCount, blockByteCountsArrayOffset)
        entry(284, TYPE_SHORT, 1, 1)
        if (tiled) {
            entry(322, TYPE_LONG, 1, blockWidth)
            entry(323, TYPE_LONG, 1, blockHeight)
            entry(324, TYPE_LONG, blockCount, blockOffsetsArrayOffset)
            entry(325, TYPE_LONG, blockCount, blockByteCountsArrayOffset)
        }
        entry(339, TYPE_SHORT, 1, 1)
        output.putInt(0)

        output.position(bitsOffset)
        output.putShort(8).putShort(8).putShort(8)
        for (block in 0 until blockCount) {
            output.putInt(blockOffsetsArrayOffset + block * 4, pixelDataOffset + block * blockBytes)
            output.putInt(blockByteCountsArrayOffset + block * 4, blockBytes)
        }
        for (blockY in 0 until rows) {
            for (blockX in 0 until columns) {
                val block = blockY * columns + blockX
                output.position(pixelDataOffset + block * blockBytes)
                for (localY in 0 until blockHeight) {
                    for (localX in 0 until blockWidth) {
                        val x = blockX * blockWidth + localX
                        val y = blockY * blockHeight + localY
                        if (x < width && y < height) {
                            val color = expectedColor(x, y)
                            output.put(Color.red(color).toByte())
                            output.put(Color.green(color).toByte())
                            output.put(Color.blue(color).toByte())
                        } else {
                            output.put(0).put(0).put(0)
                        }
                    }
                }
            }
        }
        destination.writeBytes(output.array())
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()

    private companion object {
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4
    }
}
