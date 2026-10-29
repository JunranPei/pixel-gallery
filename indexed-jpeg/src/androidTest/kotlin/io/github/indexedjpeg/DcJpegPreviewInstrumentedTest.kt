package io.github.indexedjpeg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DcJpegPreviewInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun nativeBitmapDimensionsColorsAndRepeat() {
        val file = fixture("dc-native-colors.jpg", 8193, 65)
        val uri = Uri.fromFile(file)
        repeat(3) {
            val bitmap = DcJpegPreview.decode(context.contentResolver, uri)!!
            assertEquals(513, bitmap.width); assertEquals(5, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            assertEquals(255, Color.alpha(bitmap.getPixel(0, 0)))
            assertColor(Color.rgb(225, 45, 75), bitmap.getPixel(2, 2))
            assertColor(Color.rgb(35, 100, 220), bitmap.getPixel(512, 4))
            bitmap.recycle()
        }
        val sample32 = DcJpegPreview.decode(context.contentResolver, uri, 257)!!
        assertEquals(257, sample32.width); assertEquals(3, sample32.height)
        sample32.recycle()
        assertNull(DcJpegPreview.decode(context.contentResolver, uri, 514))
    }

    @Test fun unsupportedAndDamagedFilesReturnNullAndRecover() {
        val small = fixture("dc-native-small.jpg", 17, 19)
        assertNull(DcJpegPreview.decode(context.contentResolver, Uri.fromFile(small)))
        val smallPreview = DcJpegPreview.decode(context.contentResolver, Uri.fromFile(small), 1)!!
        assertEquals(1, smallPreview.width); assertEquals(1, smallPreview.height)
        smallPreview.recycle()
        val progressive = File(context.cacheDir, "dc-native-progressive.jpg")
        context.assets.open("progressive-fixture.jpg").use { input ->
            progressive.outputStream().use { input.copyTo(it) }
        }
        assertNull(DcJpegPreview.decode(context.contentResolver, Uri.fromFile(progressive), 1))
        val valid = fixture("dc-native-valid.jpg", 8193, 65)
        val bytes = valid.readBytes()
        for (length in listOf(0, 10, bytes.size / 2, bytes.size - 2)) {
            val damaged = File(context.cacheDir, "dc-native-cut-$length.jpg")
            damaged.writeBytes(bytes.copyOf(length))
            assertNull("truncated $length", DcJpegPreview.decode(context.contentResolver, Uri.fromFile(damaged)))
        }
        assertNull(DcJpegPreview.decode(context.contentResolver, Uri.fromFile(File(context.cacheDir, "dc-missing.jpg"))))
        val png = File(context.cacheDir, "dc-native-other.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).let { b ->
            png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
        }
        assertNull(DcJpegPreview.decode(context.contentResolver, Uri.fromFile(png), 1))
        assertNotNull(DcJpegPreview.decode(context.contentResolver, Uri.fromFile(valid)).also { it?.recycle() })
    }

    @Test fun cancellationBeforeOpenAndInvalidMinimum() {
        val missing = Uri.fromFile(File(context.cacheDir, "dc-cancel-missing.jpg"))
        assertNull(DcJpegPreview.decode(context.contentResolver, missing, cancelled = AtomicBoolean(true)))
        try {
            DcJpegPreview.decode(context.contentResolver, missing, 0)
            fail("zero minimum must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun standardFitSupportsCameraChromaAndRejectsInsufficientDetail() {
        val file = fixture("fit-native-colors.jpg", 8193, 65)
        val uri = Uri.fromFile(file)
        repeat(3) {
            val bitmap = checkNotNull(DcJpegPreview.decodeFit(context.contentResolver, uri, 512, 512))
            assertEquals(1025, bitmap.width); assertEquals(9, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            assertColor(Color.rgb(225, 45, 75), bitmap.getPixel(2, 2))
            assertColor(Color.rgb(35, 100, 220), bitmap.getPixel(1024, 8))
            bitmap.recycle()
        }
        for ((target, sample) in listOf(512 to 8, 2048 to 4, 4096 to 2, 8193 to 1)) {
            val scaled = checkNotNull(DcJpegPreview.decodeFit(context.contentResolver, uri, target, target))
            assertEquals((8193 + sample - 1) / sample, scaled.width)
            assertEquals((65 + sample - 1) / sample, scaled.height)
            assertEquals(255, Color.alpha(scaled.getPixel(0, 0)))
            assertEquals(sample.toLong(), DcJpegPreview.lastFitTimings()[5])
            scaled.recycle()
        }
        assertNull(DcJpegPreview.decodeFit(context.contentResolver, uri, 512, 512, AtomicBoolean(true)))
        val progressive = File(context.cacheDir, "fit-native-progressive.jpg")
        context.assets.open("progressive-fixture.jpg").use { input ->
            progressive.outputStream().use { input.copyTo(it) }
        }
        assertNull(DcJpegPreview.decodeFit(context.contentResolver, Uri.fromFile(progressive), 1, 1))
        val bytes = file.readBytes()
        val cut = File(context.cacheDir, "fit-native-cut.jpg")
        cut.writeBytes(bytes.copyOf(bytes.size - 2))
        assertNull(DcJpegPreview.decodeFit(context.contentResolver, Uri.fromFile(cut), 512, 512))
        assertNotNull(DcJpegPreview.decodeFit(context.contentResolver, uri, 512, 512).also { it?.recycle() })
    }

    @Test fun fitRejectsWideColorAndNonJpegWithoutChangingInputs() {
        val wide = Bitmap.createBitmap(257, 129, Bitmap.Config.ARGB_8888, false,
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        wide.eraseColor(Color.rgb(200, 80, 25))
        val file = File(context.cacheDir, "fit-wide-p3.jpg")
        file.outputStream().use { assertTrue(wide.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        wide.recycle()
        val before = file.readBytes()
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        assertTrue(bounds.outColorSpace?.isWideGamut == true)
        assertNull(DcJpegPreview.decodeFit(context.contentResolver, Uri.fromFile(file), 1, 1))
        assertArrayEquals(before, file.readBytes())
        val png = File(context.cacheDir, "fit-not-jpeg.png")
        Bitmap.createBitmap(17, 19, Bitmap.Config.ARGB_8888).let { b ->
            png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
        }
        assertNull(DcJpegPreview.decodeFit(context.contentResolver, Uri.fromFile(png), 1, 1))
        assertNull(DcJpegPreview.decodeFit(context.contentResolver, Uri.fromFile(File(context.cacheDir, "fit-missing.jpg")), 1, 1))
    }

    private fun fixture(name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width) { x -> if (x < 4096) Color.rgb(225, 45, 75) else Color.rgb(35, 100, 220) }
        for (y in 0 until height) bitmap.setPixels(row, 0, width, 0, y, width, 1)
        return File(context.cacheDir, name).also {
            it.outputStream().use { out -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)) }
            bitmap.recycle()
        }
    }

    private fun assertColor(expected: Int, actual: Int) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 4)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 4)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 4)
    }
}
