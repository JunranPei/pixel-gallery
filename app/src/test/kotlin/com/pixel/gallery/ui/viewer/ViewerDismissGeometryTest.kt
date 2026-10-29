package com.pixel.gallery.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ViewerDismissGeometryTest {
    private val portrait = Rect(400f, 0f, 680f, 1000f)
    private val landscape = Rect(0f, 300f, 1080f, 900f)
    private val target = ViewerThumbnailTarget(Rect(40f, 150f, 240f, 350f), 24f)

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, .001f)
        assertEquals(expected.top, actual.top, .001f)
        assertEquals(expected.right, actual.right, .001f)
        assertEquals(expected.bottom, actual.bottom, .001f)
    }

    @Test fun dragScalesImageAndRevealsBackgroundSymmetrically() {
        val down = dismissDragVisual(portrait, 20f, 160f, 1200f, 1f)
        val up = dismissDragVisual(portrait, 20f, -160f, 1200f, 1f)
        assertTrue(down.scale < 1f && down.scale >= .86f)
        assertEquals(down.scale, up.scale, 0f)
        assertEquals(down.backdropAlpha, up.backdropAlpha, 0f)
        assertTrue(down.backdropAlpha < 1f)
        assertEquals(0f, down.chromeAlpha, 0f)
        val downCenter = portrait.center * down.scale + down.translation
        val upCenter = portrait.center * up.scale + up.translation
        assertEquals(16f, downCenter.x - portrait.center.x, .001f)
        assertEquals(128f, downCenter.y - portrait.center.y, .001f)
        assertEquals(-128f, upCenter.y - portrait.center.y, .001f)
    }

    @Test fun firstClosingFrameExactlyContinuesTheFingerPosition() {
        val start = dismissDragVisual(portrait, -60f, 220f, 1200f, 1f)
        val first = dismissCloseVisual(portrait, start, target, 0f)
        assertEquals(start.scale, first.scale, .001f)
        assertEquals(start.translation.x, first.translation.x, .001f)
        assertEquals(start.translation.y, first.translation.y, .001f)
        assertRect(start.clip!!, first.clip!!)
        assertEquals(start.backdropAlpha, first.backdropAlpha, 0f)
    }

    @Test fun portraitAndLandscapeImagesLandExactlyInSquareThumbnail() {
        for (source in listOf(portrait, landscape)) {
            val start = dismissDragVisual(source, 30f, -180f, 1200f, 1f)
            val end = dismissCloseVisual(source, start, target, 1f)
            assertRect(target.bounds, end.clip!!.scaledAndTranslated(end.scale, end.translation))
            val pixels = source.scaledAndTranslated(end.scale, end.translation)
            assertEquals(source.width / source.height, pixels.width / pixels.height, .001f)
            assertTrue(pixels.width >= target.bounds.width - .001f)
            assertTrue(pixels.height >= target.bounds.height - .001f)
            assertEquals(24f, end.cornerRadius * end.scale, .001f)
            assertEquals(0f, end.backdropAlpha, 0f)
            assertEquals(1f, end.imageAlpha, 0f)
        }
    }

    @Test fun cropNeverRevealsEmptyPixelsDuringAspectRatioChange() {
        for (source in listOf(portrait, landscape)) {
            val start = dismissDragVisual(source, -90f, 200f, 1200f, 1f)
            for (step in 0..100) {
                val frame = dismissCloseVisual(source, start, target, step / 100f)
                val image = source.scaledAndTranslated(frame.scale, frame.translation)
                val crop = frame.clip!!.scaledAndTranslated(frame.scale, frame.translation)
                assertTrue(crop.left >= image.left - .002f)
                assertTrue(crop.top >= image.top - .002f)
                assertTrue(crop.right <= image.right + .002f)
                assertTrue(crop.bottom <= image.bottom + .002f)
            }
        }
    }

    @Test fun cancellationRestoresImageBackdropAndChrome() {
        val start = dismissDragVisual(portrait, 50f, 100f, 1200f, 1f)
        val end = dismissReturnVisual(start, 1f)
        assertEquals(1f, end.scale, 0f)
        assertEquals(Offset.Zero, end.translation)
        assertEquals(1f, end.backdropAlpha, 0f)
        assertEquals(1f, end.chromeAlpha, 0f)
        assertEquals(1f, end.imageAlpha, 0f)
    }

    @Test fun missingThumbnailFadesInPlaceRatherThanSlidingPageOffscreen() {
        val start = dismissDragVisual(portrait, -50f, 150f, 1200f, 1f)
        val startCenter = portrait.center * start.scale + start.translation
        for (step in 0..10) {
            val frame = dismissCloseVisual(portrait, start, null, step / 10f)
            val center = portrait.center * frame.scale + frame.translation
            assertEquals(startCenter.x, center.x, .001f)
            assertEquals(startCenter.y, center.y, .001f)
            assertEquals(1f - step / 10f, frame.imageAlpha, .001f)
        }
        assertEquals(0f, dismissCloseVisual(portrait, start, null, 1f).backdropAlpha, 0f)
    }

    @Test fun extremeDragKeepsPositiveScaleAndValidAlpha() {
        val frame = dismissDragVisual(portrait, 0f, 100000f, 1200f, 1f)
        assertEquals(.86f, frame.scale, .001f)
        assertTrue(frame.backdropAlpha in 0f..1f)
        assertTrue(frame.chromeAlpha in 0f..1f)
    }
}
