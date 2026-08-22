package io.github.indexedheif

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
class IndexedHeifStoreInstrumentedTest {
    @Test
    fun platformRegionTilesPersistDecodeAcrossSamplesThenDelete() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // PNG bytes deliberately exercise the platform BitmapRegionDecoder-to-index boundary on
        // every test device without assuming its encoder can create a HEIF fixture.
        val source = File(context.cacheDir, "indexed-heif-platform-fixture.heic")
        createPlatformFixture(source)
        val store = IndexedHeifStore(context)
        store.delete(source.absolutePath)

        assertEquals(IndexedHeifStatus.Absent, store.status(source.absolutePath))
        val info = store.build(source.absolutePath)
        assertEquals(1100, info.sourceWidth)
        assertEquals(700, info.sourceHeight)
        assertTrue(info.levelCount > 1)
        assertTrue(info.tileCount > info.levelCount)
        assertTrue(info.indexBytes > 0L)
        assertTrue(store.status(source.absolutePath) is IndexedHeifStatus.Ready)

        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(480, 240, 1050, 690)
            assertRegion(decoder!!.decodeRegion(region, 1), region, 1)
            assertRegion(decoder.decodeRegion(region, 2), region, 2)
            assertRegion(decoder.decodeRegion(region, 4), region, 4)
            assertRegion(decoder.decodeRegion(region, 16), region, 16)
        }

        val relocated = File(context.cacheDir, "indexed-heif-platform-fixture-relocated.heic")
        store.delete(relocated.absolutePath)
        relocated.delete()
        assertTrue(source.renameTo(relocated))
        assertTrue(store.relocate(source.absolutePath, relocated.absolutePath))
        assertTrue(store.status(relocated.absolutePath) is IndexedHeifStatus.Ready)
        assertTrue(relocated.renameTo(source))
        assertTrue(store.relocate(relocated.absolutePath, source.absolutePath))
        assertTrue(store.status(source.absolutePath) is IndexedHeifStatus.Ready)

        assertTrue(source.setLastModified(source.lastModified() + 2_000L))
        assertTrue(store.status(source.absolutePath) is IndexedHeifStatus.Invalid)
        assertEquals(null, store.openDecoder(source.absolutePath))
        assertTrue(store.delete(source.absolutePath))
        assertEquals(IndexedHeifStatus.Absent, store.status(source.absolutePath))
    }

    @Test
    fun relocatePreservesReadyIndex() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-heif-relocate-source.heic")
        val destination = File(context.cacheDir, "indexed-heif-relocate-destination.heic")
        createPlatformFixture(source)
        val store = IndexedHeifStore(context)
        store.delete(source.absolutePath)
        store.delete(destination.absolutePath)
        destination.delete()

        try {
            store.build(source.absolutePath)
            assertTrue(source.renameTo(destination))
            assertTrue(store.relocate(source.absolutePath, destination.absolutePath))
            assertTrue(store.status(destination.absolutePath) is IndexedHeifStatus.Ready)
            store.openDecoder(destination.absolutePath).use { decoder ->
                assertNotNull(decoder)
                val decoded = decoder!!.decodeRegion(Rect(10, 20, 210, 120), 2)
                assertNotNull(decoded)
                assertEquals(100, decoded!!.width)
                assertEquals(50, decoded.height)
                decoded.recycle()
            }
        } finally {
            store.delete(source.absolutePath)
            store.delete(destination.absolutePath)
            source.delete()
            destination.delete()
        }
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

    private fun createPlatformFixture(destination: File) {
        val bitmap = Bitmap.createBitmap(1100, 700, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) bitmap.setPixel(x, y, expectedColor(x, y))
        }
        FileOutputStream(destination).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
