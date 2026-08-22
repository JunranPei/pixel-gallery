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
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class IndexedPngStoreInstrumentedTest {
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
}
