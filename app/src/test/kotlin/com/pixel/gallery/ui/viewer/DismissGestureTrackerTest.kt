package com.pixel.gallery.ui.viewer

import org.junit.Assert.*
import org.junit.Test

class DismissGestureTrackerTest {
    private fun stroke(eligible: Boolean = true, density: Float = 1f) =
        DismissGestureTracker(8f * density, density).apply { begin(100f, 200f, eligible) }

    @Test fun upAndDownDismissOnceAfterRelease() {
        for (dy in listOf(-200f, 200f)) {
            val tracker = stroke()
            tracker.move(100f, 200f + dy)
            assertEquals(DismissGestureTracker.Intent.DISMISS, tracker.intent)
            assertTrue(tracker.finish(800f, 0f))
            assertFalse(tracker.finish(800f, 0f))
        }
    }

    @Test fun tapsAndJitterStayWithContent() {
        val tracker = stroke()
        tracker.move(103f, 204f)
        assertEquals(DismissGestureTracker.Intent.UNDECIDED, tracker.intent)
        assertFalse(tracker.finish(800f, 3000f))
    }

    @Test fun horizontalPagingCannotBecomeDismissalLater() {
        val tracker = stroke()
        tracker.move(130f, 204f)
        tracker.move(130f, 600f)
        assertEquals(DismissGestureTracker.Intent.REJECTED, tracker.intent)
        assertFalse(tracker.finish(800f, 0f))
    }

    @Test fun diagonalStartStaysWithContent() {
        val tracker = stroke()
        tracker.move(120f, 220f)
        tracker.move(120f, 500f)
        assertFalse(tracker.finish(800f, 0f))
    }

    @Test fun zoomedImageOrExcludedControlsCannotDismiss() {
        val tracker = stroke(eligible = false)
        tracker.move(100f, 600f)
        assertFalse(tracker.finish(800f, 3000f))
    }

    @Test fun addingFingerOrSystemCancellationRejectsWholeStroke() {
        val tracker = stroke()
        tracker.move(100f, 500f)
        tracker.cancel()
        tracker.move(100f, 600f)
        assertFalse(tracker.finish(800f, 3000f))
    }

    @Test fun shortSlowDragReturns() {
        val tracker = stroke()
        tracker.move(100f, 250f)
        assertFalse(tracker.finish(800f, 100f))
    }

    @Test fun reversingBeforeReleaseUsesFinalDisplacement() {
        val tracker = stroke()
        tracker.move(100f, 500f)
        tracker.move(100f, 220f)
        assertFalse(tracker.finish(800f, 0f))
    }

    @Test fun shortDeliberateFlingWorksInBothDirections() {
        for (direction in listOf(-1f, 1f)) {
            val tracker = stroke()
            tracker.move(100f, 200f + direction * 50f)
            assertTrue(tracker.finish(800f, direction * 1500f))
        }
    }

    @Test fun tinyFastFlickDoesNotDismiss() {
        val tracker = stroke()
        tracker.move(100f, 215f)
        assertFalse(tracker.finish(800f, 4000f))
    }

    @Test fun fastReverseCancelsEvenPastDistanceThreshold() {
        val tracker = stroke()
        tracker.move(100f, 500f)
        assertFalse(tracker.finish(800f, -1500f))
    }

    @Test fun landscapeThresholdUsesShorterHeight() {
        val landscape = stroke()
        landscape.move(100f, 300f)
        assertTrue(landscape.finish(360f, 0f))
        val portrait = stroke()
        portrait.move(100f, 300f)
        assertFalse(portrait.finish(800f, 0f))
    }

    @Test fun thresholdsScaleWithDensity() {
        val tracker = stroke(density = 3f)
        tracker.move(100f, 200f + 50f * 3f)
        assertFalse(tracker.finish(2400f, 1500f))
        tracker.begin(100f, 200f, true)
        tracker.move(100f, 200f + 50f * 3f)
        assertTrue(tracker.finish(2400f, 4500f))
    }

    @Test fun newStrokeCanDismissAfterCancellation() {
        val tracker = stroke()
        tracker.cancel()
        tracker.begin(100f, 200f, true)
        tracker.move(100f, 500f)
        assertTrue(tracker.finish(800f, 0f))
    }
}
