package io.github.indexedbmp

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
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class IndexedBmpStoreInstrumentedTest {
    @Test
    fun activateDecodeAcrossRowsAndSamplesThenInvalidateAndDelete() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-bmp-fixture.bmp")
        createFixture(source, 1100, 700)
        val store = IndexedBmpStore(context)
        store.delete(source.absolutePath)

        assertEquals(IndexedBmpStatus.Absent, store.status(source.absolutePath))
        val info = store.build(source.absolutePath)
        assertEquals(1100, info.sourceWidth)
        assertEquals(700, info.sourceHeight)
        assertEquals(24, info.bitsPerPixel)
        assertEquals(false, info.topDown)
        assertEquals(56L, info.indexBytes)
        assertTrue(store.status(source.absolutePath) is IndexedBmpStatus.Ready)

        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(480, 240, 1050, 690)
            assertRegion(decoder!!.decodeRegion(region, 1), region, 1)
            assertRegion(decoder.decodeRegion(region, 2), region, 2)
            assertRegion(decoder.decodeRegion(region, 4), region, 4)
            assertRegion(decoder.decodeRegion(region, 16), region, 16)
        }

        val relocated = File(context.cacheDir, "indexed-bmp-fixture-relocated.bmp")
        store.delete(relocated.absolutePath)
        relocated.delete()
        assertTrue(source.renameTo(relocated))
        assertTrue(store.relocate(source.absolutePath, relocated.absolutePath))
        assertTrue(store.status(relocated.absolutePath) is IndexedBmpStatus.Ready)
        assertTrue(relocated.renameTo(source))
        assertTrue(store.relocate(relocated.absolutePath, source.absolutePath))
        assertTrue(store.status(source.absolutePath) is IndexedBmpStatus.Ready)

        assertTrue(source.setLastModified(source.lastModified() + 2_000L))
        assertTrue(store.status(source.absolutePath) is IndexedBmpStatus.Invalid)
        assertEquals(null, store.openDecoder(source.absolutePath))
        assertTrue(store.delete(source.absolutePath))
        assertEquals(IndexedBmpStatus.Absent, store.status(source.absolutePath))
    }

    private fun assertRegion(bitmap: Bitmap?, region: Rect, sample: Int) {
        assertNotNull(bitmap)
        bitmap!!
        assertEquals((region.width() + sample - 1) / sample, bitmap.width)
        assertEquals((region.height() + sample - 1) / sample, bitmap.height)
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

    private fun createFixture(destination: File, width: Int, height: Int) {
        val rowStride = ((width * 24 + 31) / 32) * 4
        val pixelBytes = rowStride * height
        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('B'.code.toByte())
            put('M'.code.toByte())
            putInt(54 + pixelBytes)
            putInt(0)
            putInt(54)
            putInt(40)
            putInt(width)
            putInt(height)
            putShort(1)
            putShort(24)
            putInt(0)
            putInt(pixelBytes)
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(0)
        }
        FileOutputStream(destination).use { output ->
            output.write(header.array())
            val row = ByteArray(rowStride)
            for (fileY in 0 until height) {
                row.fill(0)
                val sourceY = height - 1 - fileY
                for (x in 0 until width) {
                    val color = expectedColor(x, sourceY)
                    val offset = x * 3
                    row[offset] = Color.blue(color).toByte()
                    row[offset + 1] = Color.green(color).toByte()
                    row[offset + 2] = Color.red(color).toByte()
                }
                output.write(row)
            }
        }
    }
}
