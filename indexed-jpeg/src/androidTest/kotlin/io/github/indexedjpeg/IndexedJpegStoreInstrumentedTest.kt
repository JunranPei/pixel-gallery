package io.github.indexedjpeg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
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
            assertRegionMatchesPlatform(
                source = source,
                indexed = decoder,
                region = Rect(0, 0, 180, 288),
                sampleSize = 1,
            )
            assertRegionMatchesPlatform(
                source = source,
                indexed = decoder,
                region = Rect(0, 48, 180, 336),
                sampleSize = 1,
            )
            // Start beyond the first horizontal Huffman checkpoint. A broken
            // persisted entropy state still returns a correctly-sized, but
            // visibly corrupt, bitmap here.
            assertRegionMatchesPlatform(
                source = source,
                indexed = decoder,
                region = Rect(320, 48, 500, 336),
                sampleSize = 1,
            )
        }

        val relocated = File(context.cacheDir, "indexed-jpeg-fixture-relocated.jpg")
        store.delete(relocated.absolutePath)
        relocated.delete()
        assertTrue(source.renameTo(relocated))
        assertTrue(store.relocate(source.absolutePath, relocated.absolutePath))
        assertTrue(store.status(relocated.absolutePath) is IndexedJpegStatus.Ready)
        assertTrue(relocated.renameTo(source))
        assertTrue(store.relocate(relocated.absolutePath, source.absolutePath))
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Ready)

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

    @Suppress("DEPRECATION")
    private fun assertRegionMatchesPlatform(
        source: File,
        indexed: IndexedJpegRegionDecoder,
        region: Rect,
        sampleSize: Int,
    ) {
        val actual = indexed.decodeRegion(region, sampleSize)
        assertNotNull(actual)
        actual!!
        val platform = BitmapRegionDecoder.newInstance(source.absolutePath, false)
        assertNotNull(platform)
        val expected = try {
            platform!!.decodeRegion(
                region,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } finally {
            platform!!.recycle()
        }
        assertNotNull(expected)
        expected!!
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)

        var totalDifference = 0L
        var samples = 0L
        val stepX = (actual.width / 32).coerceAtLeast(1)
        val stepY = (actual.height / 32).coerceAtLeast(1)
        for (y in 0 until actual.height step stepY) {
            for (x in 0 until actual.width step stepX) {
                val expectedColor = expected.getPixel(x, y)
                val actualColor = actual.getPixel(x, y)
                totalDifference += kotlin.math.abs(Color.red(expectedColor) - Color.red(actualColor))
                totalDifference += kotlin.math.abs(Color.green(expectedColor) - Color.green(actualColor))
                totalDifference += kotlin.math.abs(Color.blue(expectedColor) - Color.blue(actualColor))
                samples += 3
            }
        }
        val meanDifference = totalDifference.toDouble() / samples.coerceAtLeast(1)
        val centerX = actual.width / 2
        val centerY = actual.height / 2
        val actualCenter = actual.getPixel(centerX, centerY)
        val expectedCenter = expected.getPixel(centerX, centerY)
        assertTrue(
            "Mean RGB difference for $region was $meanDifference; " +
                "center actual=${Integer.toHexString(actualCenter)} " +
                "expected=${Integer.toHexString(expectedCenter)}",
            meanDifference <= 5.0,
        )
        expected.recycle()
        actual.recycle()
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
