package com.pixel.gallery.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.MonotonicFrameClock
import com.pixel.gallery.model.ViewerOpeningEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ViewerOpeningGeometryTest {
    private val thumbnail = ViewerThumbnailTarget(Rect(40f, 150f, 240f, 350f), 24f)

    @Test fun thumbnailStartsAtExactCellBoundsWithoutStretchingPortraitOrLandscape() {
        for (ratio in listOf(.25f, 4f, 1f, 16f / 9f)) {
            val fit = openingFitBounds(1080f, 1920f, ratio)!!
            val first = openingVisual(ViewerOpeningEffect.THUMBNAIL, fit, thumbnail, 0f)
            val crop = first.clip!!.scaledAndTranslated(first.scale, first.translation)
            assertEquals(thumbnail.bounds.left, crop.left, .001f)
            assertEquals(thumbnail.bounds.top, crop.top, .001f)
            assertEquals(thumbnail.bounds.right, crop.right, .001f)
            assertEquals(thumbnail.bounds.bottom, crop.bottom, .001f)
            assertEquals(thumbnail.cornerRadius, first.cornerRadius * first.scale, .001f)
            val pixels = fit.scaledAndTranslated(first.scale, first.translation)
            assertEquals(ratio, pixels.width / pixels.height, .001f)
        }
    }

    @Test fun cropRemainsInsideImageThroughoutExpansion() {
        for (ratio in listOf(.1f, 10f, 1f)) {
            val fit = openingFitBounds(1080f, 1920f, ratio)!!
            for (step in 0..99) {
                val frame = openingVisual(ViewerOpeningEffect.THUMBNAIL, fit, thumbnail, step / 100f)
                val pixels = fit.scaledAndTranslated(frame.scale, frame.translation)
                val crop = frame.clip!!.scaledAndTranslated(frame.scale, frame.translation)
                assertTrue(crop.left >= pixels.left - .01f)
                assertTrue(crop.top >= pixels.top - .01f)
                assertTrue(crop.right <= pixels.right + .01f)
                assertTrue(crop.bottom <= pixels.bottom + .01f)
            }
        }
    }

    @Test fun allEffectsEndWithNoResidualScaleClipOrTransparency() {
        for (effect in ViewerOpeningEffect.entries) {
            assertEquals(ViewerDismissVisual(), openingVisual(effect, thumbnail.bounds, thumbnail, 1f))
        }
    }

    @Test fun scaleFadeKeepsImageCenteredAndIncreasesOpacity() {
        val fit = openingFitBounds(1080f, 1920f, 2f)!!
        for (step in 0..10) {
            val p = step / 10f
            val frame = openingVisual(ViewerOpeningEffect.SCALE_FADE, fit, null, p)
            val center = fit.center * frame.scale + frame.translation
            assertEquals(fit.center.x, center.x, .001f)
            assertEquals(fit.center.y, center.y, .001f)
            assertEquals(p, frame.imageAlpha, .001f)
            assertNull(frame.clip)
            assertTrue(frame.scale in .94f..1f)
        }
    }

    @Test fun unavailableAnchorUsesScaleFadeAndNoneIsAlwaysIdentity() {
        for (step in 0..10) {
            val p = step / 10f
            assertEquals(openingVisual(ViewerOpeningEffect.SCALE_FADE, thumbnail.bounds, null, p),
                openingVisual(ViewerOpeningEffect.THUMBNAIL, thumbnail.bounds, null, p))
            assertEquals(ViewerDismissVisual(), openingVisual(ViewerOpeningEffect.NONE, thumbnail.bounds, thumbnail, p))
        }
    }

    @Test fun corruptOrMissingPreferencePreservesNoAnimation() {
        assertEquals(ViewerOpeningEffect.NONE, ViewerOpeningEffect.fromPreference(null))
        assertEquals(ViewerOpeningEffect.NONE, ViewerOpeningEffect.fromPreference("unknown"))
        for (effect in ViewerOpeningEffect.entries) assertEquals(effect, ViewerOpeningEffect.fromPreference(effect.name))
    }

    @Test fun invalidDimensionsHaveNoThumbnailGeometry() {
        assertNull(openingFitBounds(0f, 100f, 1f))
        assertNull(openingFitBounds(100f, 0f, 1f))
        assertNull(openingFitBounds(100f, 100f, Float.NaN))
        assertNull(openingFitBounds(100f, 100f, 0f))
    }

    @Test fun cancelAndResizeReleaseOpeningStateAndIgnoreOtherPagesReadiness() {
        val key = ViewerTransitionKey("test", 1)
        val opening = ViewerOpeningTransition(key, true)
        opening.ready(ViewerTransitionKey("neighbor", 1))
        assertFalse(opening.contentReady)
        opening.ready(key)
        assertTrue(opening.contentReady)
        opening.resize(IntSize(1080, 1920))
        assertTrue(opening.isRunning)
        opening.resize(IntSize(1920, 1080))
        assertFalse(opening.isRunning)
        assertEquals(ViewerDismissVisual(), opening.visual)
        opening.cancel()
        assertEquals(ViewerDismissVisual(), opening.visual)
        assertEquals(Offset.Zero, opening.visual.translation)
    }

    private class FrameClock : MonotonicFrameClock {
        var frames = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
            return onFrame(++frames * 16_666_667L)
        }
    }

    @Test fun completedEntrySchedulesNoMoreFramesAndCannotReplay() = runBlocking {
        val opening = ViewerOpeningTransition(ViewerTransitionKey("test", 1), true)
        opening.resize(IntSize(1080, 1920))
        opening.ready(opening.key)
        val clock = FrameClock()
        withContext(clock) { opening.play(ViewerOpeningEffect.SCALE_FADE, null, 2f, false) }
        assertFalse(opening.isRunning)
        assertEquals(ViewerDismissVisual(), opening.visual)
        assertTrue(clock.frames in 14..18)
        val finishedFrames = clock.frames
        withContext(clock) { opening.play(ViewerOpeningEffect.THUMBNAIL, null, 2f, true) }
        assertEquals(finishedFrames, clock.frames)
    }

    @Test fun cancellationStopsFrameSchedulingAndRestoresIdentity() = runBlocking {
        val opening = ViewerOpeningTransition(ViewerTransitionKey("test", 1), true)
        opening.resize(IntSize(1080, 1920))
        opening.ready(opening.key)
        val clock = FrameClock()
        val animation = launch(clock) { opening.play(ViewerOpeningEffect.SCALE_FADE, null, 2f, false) }
        yield()
        opening.cancel()
        animation.join()
        val cancelledFrames = clock.frames
        repeat(3) { yield() }
        assertEquals(cancelledFrames, clock.frames)
        assertFalse(opening.isRunning)
        assertEquals(ViewerDismissVisual(), opening.visual)
    }

    @Test fun disabledOpeningDoesNotWaitForLayoutOrRequestAnimationFrames() = runBlocking {
        val opening = ViewerOpeningTransition(null, false)
        val clock = FrameClock()
        withContext(clock) { opening.play(ViewerOpeningEffect.NONE, null, 1f, false) }
        assertEquals(0, clock.frames)
        assertEquals(ViewerDismissVisual(), opening.visual)
    }

    @Test fun missingPreviewReleasesTheViewerAfterABoundedWait() = runBlocking {
        val opening = ViewerOpeningTransition(ViewerTransitionKey("missing", 1), true)
        opening.resize(IntSize(1080, 1920))
        val clock = FrameClock()
        withContext(clock) { opening.play(ViewerOpeningEffect.THUMBNAIL, null, 1f, true) }
        assertFalse(opening.contentReady)
        assertFalse(opening.isRunning)
        assertEquals(ViewerDismissVisual(), opening.visual)
        assertTrue(clock.frames in 14..18)
    }

    @Test fun animationStartsWithoutWaitingForTheViewerPreview() = runBlocking {
        val opening = ViewerOpeningTransition(ViewerTransitionKey("loading", 1), true)
        opening.resize(IntSize(1080, 1920))
        val clock = FrameClock()
        val animation = launch(clock, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            opening.play(ViewerOpeningEffect.SCALE_FADE, null, 1f, false)
        }
        try {
            repeat(2) { yield() }
            assertFalse(opening.contentReady)
            assertTrue("Opening must request frames before the viewer preview is ready", clock.frames > 0)
        } finally {
            opening.cancel()
            animation.join()
        }
    }

    @Test fun firstPlacedFrameUsesTheCapturedCellInWindowCoordinates() {
        val key = ViewerTransitionKey("captured", 1)
        val seed = ViewerOpeningSeed(key, thumbnail)
        val opening = ViewerOpeningTransition(key, true, seed, ViewerOpeningEffect.THUMBNAIL, .5f)
        opening.resize(IntSize(1080, 1920))
        opening.windowOrigin = Offset(12f, 28f)
        val first = opening.visual
        val crop = first.clip!!.scaledAndTranslated(first.scale, first.translation)
        val target = thumbnail.bounds.translate(-opening.windowOrigin)
        assertEquals(target.left, crop.left, .001f)
        assertEquals(target.top, crop.top, .001f)
        assertEquals(target.right, crop.right, .001f)
        assertEquals(target.bottom, crop.bottom, .001f)
    }

    @Test fun noneAlsoReleasesAnAlreadyPendingEntryWithoutFrames() = runBlocking {
        val opening = ViewerOpeningTransition(null, true)
        val clock = FrameClock()
        withContext(clock) { opening.play(ViewerOpeningEffect.NONE, null, 1f, false) }
        assertFalse(opening.isRunning)
        assertEquals(0, clock.frames)
    }
}
