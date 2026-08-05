package io.github.indexedraw

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IndexedRawStoreInstrumentedTest {
    @Test
    fun ordinaryRasterIsRejectedWithoutCreatingIndex() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "not-a-raw.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val store = IndexedRawStore(context)
        assertTrue(store.status(source.absolutePath) is IndexedRawStatus.Unsupported)
    }
}
