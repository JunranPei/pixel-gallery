package io.github.indexedjpeg

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
class IndexedJpegStoreInstrumentedTest {
    @Test
    fun buildPersistDecodeMultipleSamplesAndDelete() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-jpeg-fixture.jpg")
        createFixture(source)
        val store = IndexedJpegStore(context)
        store.delete(source.absolutePath)

        assertEquals(IndexedJpegStatus.Absent, store.status(source.absolutePath))
        val info = store.build(source.absolutePath)
        assertEquals(512, info.sourceWidth)
        assertEquals(384, info.sourceHeight)
        assertTrue(info.indexBytes > 0L)
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Ready)

        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(64, 48, 448, 336)
            assertDecodedSize(decoder!!.decodeRegion(region, 1), 384, 288)
            assertDecodedSize(decoder.decodeRegion(region, 2), 192, 144)
            assertDecodedSize(decoder.decodeRegion(region, 16), 24, 18)
        }

        assertTrue(source.setLastModified(source.lastModified() + 2_000L))
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Invalid)
        assertEquals(null, store.openDecoder(source.absolutePath))
        assertTrue(store.delete(source.absolutePath))
        assertEquals(IndexedJpegStatus.Absent, store.status(source.absolutePath))
        assertEquals(null, store.openDecoder(source.absolutePath))
    }

    @Test
    fun progressiveJpegBuildsAndDecodesIndexedRegions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-jpeg-progressive-fixture.jpg")
        context.assets.open("progressive-fixture.jpg").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val store = IndexedJpegStore(context)
        store.delete(source.absolutePath)

        val info = store.build(source.absolutePath)
        assertTrue(info.scanCount > 1)
        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(64, 48, 448, 336)
            assertDecodedSize(decoder!!.decodeRegion(region, 1), 384, 288)
            assertDecodedSize(decoder.decodeRegion(region, 4), 96, 72)
        }

        assertTrue(store.delete(source.absolutePath))
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
        val bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(
                    x,
                    y,
                    Color.rgb((x * 3) and 0xff, (y * 5) and 0xff, (x + y) and 0xff),
                )
            }
        }
        FileOutputStream(destination).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 91, output))
        }
        bitmap.recycle()
    }
}
