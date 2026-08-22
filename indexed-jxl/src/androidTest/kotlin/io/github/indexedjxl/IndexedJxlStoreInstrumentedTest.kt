package io.github.indexedjxl

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IndexedJxlStoreInstrumentedTest {
    @Test
    fun buildsAndReadsLosslessStillImagePyramid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "indexed-jxl-fixture.jxl")
        context.assets.open("still-spline.jxl").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val store = IndexedJxlStore(context)
        store.delete(source.absolutePath)

        try {
            assertTrue(store.status(source.absolutePath) is IndexedJxlStatus.Absent)
            val info = store.build(source.absolutePath)
            assertTrue(info.indexBytes > 0L)
            assertTrue(info.sourceWidth > 0)
            assertTrue(info.sourceHeight > 0)
            assertTrue(info.levelCount > 0)
            assertTrue(info.tileCount > 0)
            assertTrue(store.status(source.absolutePath) is IndexedJxlStatus.Ready)

            val decoder = store.openDecoder(source.absolutePath)
            assertNotNull(decoder)
            decoder!!.use {
                assertEquals(info.sourceWidth, it.sourceWidth)
                assertEquals(info.sourceHeight, it.sourceHeight)
                val sample = 2
                val bitmap = it.decodeRegion(
                    Rect(0, 0, info.sourceWidth, info.sourceHeight),
                    sample,
                )
                assertNotNull(bitmap)
                bitmap!!
                assertEquals((info.sourceWidth + sample - 1) / sample, bitmap.width)
                assertEquals((info.sourceHeight + sample - 1) / sample, bitmap.height)
                bitmap.recycle()
            }

            val relocated = File(context.cacheDir, "indexed-jxl-fixture-relocated.jxl")
            store.delete(relocated.absolutePath)
            relocated.delete()
            assertTrue(source.renameTo(relocated))
            assertTrue(store.relocate(source.absolutePath, relocated.absolutePath))
            assertTrue(store.status(relocated.absolutePath) is IndexedJxlStatus.Ready)
            assertTrue(relocated.renameTo(source))
            assertTrue(store.relocate(relocated.absolutePath, source.absolutePath))
            assertTrue(store.status(source.absolutePath) is IndexedJxlStatus.Ready)
            assertTrue(store.delete(source.absolutePath))
            assertTrue(store.status(source.absolutePath) is IndexedJxlStatus.Absent)
        } finally {
            store.delete(source.absolutePath)
            source.delete()
        }
    }
}
