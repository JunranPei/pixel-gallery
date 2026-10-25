package com.pixel.gallery.ui.viewer

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationGestureTrackerTest {
    private fun pair(degrees: Float, radius: Float = 100f, x: Float = 0f): Pair<RotationPointer, RotationPointer> {
        val angle = Math.toRadians(degrees.toDouble())
        val dx = cos(angle).toFloat() * radius
        val dy = sin(angle).toFloat() * radius
        return RotationPointer(7, x - dx, -dy) to RotationPointer(42, x + dx, dy)
    }
    private fun start(degrees: Float = 0f, radius: Float = 100f) = RotationGestureTracker(8f, 48f).apply {
        val (first, second) = pair(degrees, radius)
        begin(first, second)
    }
    private fun RotationGestureTracker.sample(degrees: Float, radius: Float = 100f, x: Float = 0f) {
        val (first, second) = pair(degrees, radius, x)
        move(first, second)
    }

    @Test fun clockwiseAndCounterclockwiseAreBothAcceptedOnlyOnFinish() {
        for (angle in listOf(45f, -45f)) {
            val tracker = start()
            tracker.sample(angle)
            assertEquals(RotationGestureTracker.Intent.ROTATION, tracker.intent)
            assertTrue(tracker.finish())
            assertFalse(tracker.finish())
        }
    }

    @Test fun smallTwistDoesNotRotate() {
        for (angle in listOf(0f, 5f, 15f, 34f, -34f)) {
            val tracker = start()
            tracker.sample(angle)
            assertFalse(tracker.finish())
        }
    }

    @Test fun returningBelowThresholdBeforeReleaseCancelsRotation() {
        val tracker = start()
        tracker.sample(50f)
        tracker.sample(20f)
        assertFalse(tracker.finish())
    }

    @Test fun crossingAngleBoundaryDoesNotTurnSmallJitterIntoFullTurn() {
        val small = start(179f)
        small.sample(-179f)
        assertFalse(small.finish())
        val large = start(170f)
        large.sample(-140f)
        assertTrue(large.finish())
    }

    @Test fun zoomWinsAndLaterFingerDriftCannotRotate() {
        val tracker = start()
        tracker.sample(3f, radius = 130f)
        assertEquals(RotationGestureTracker.Intent.TRANSFORM, tracker.intent)
        tracker.sample(65f, radius = 140f)
        assertFalse(tracker.finish())
    }

    @Test fun panWinsAndLaterFingerDriftCannotRotate() {
        val tracker = start()
        tracker.sample(2f, x = 30f)
        assertEquals(RotationGestureTracker.Intent.TRANSFORM, tracker.intent)
        tracker.sample(70f, x = 60f)
        assertFalse(tracker.finish())
    }

    @Test fun closeOrCrossingFingersAreRejected() {
        val close = start(radius = 20f)
        close.sample(90f, radius = 20f)
        assertFalse(close.finish())
        val crossing = start()
        crossing.sample(45f)
        crossing.sample(90f, radius = 10f)
        assertFalse(crossing.finish())
    }

    @Test fun pointerReplacementCannotCreateAFalseHalfTurn() {
        val tracker = start()
        val (first, second) = pair(50f)
        tracker.move(first.copy(id = 8), second)
        assertFalse(tracker.finish())
    }

    @Test fun cancellationNeverCommits() {
        val tracker = start()
        tracker.sample(60f)
        tracker.cancel()
        assertFalse(tracker.finish())
    }

    @Test fun remainingFingerCannotAlterCompletedPairAngle() {
        val tracker = start()
        tracker.sample(45f)
        tracker.releasePair()
        tracker.sample(0f)
        assertTrue(tracker.finish())
    }

    @Test fun aLongTwistStillCommitsOnlyOnce() {
        val tracker = start()
        for (angle in 30..360 step 30) tracker.sample(angle.toFloat())
        assertTrue(tracker.finish())
        assertFalse(tracker.finish())
    }

    @Test fun aNewStrokeCanRotateAfterCancellation() {
        val tracker = start()
        tracker.cancel()
        val (first, second) = pair(0f)
        tracker.begin(first, second)
        tracker.sample(-50f)
        assertTrue(tracker.finish())
    }

    @Test fun viewportResizeKeepsFitAndUnderFitRelativeToViewport() {
        assertEquals(1f, previewScaleAfterResize(1f, 4f, 2f), 0.0001f)
        assertEquals(0.5f, previewScaleAfterResize(0.5f, 4f, 2f), 0.0001f)
    }

    @Test fun viewportResizeKeepsZoomedSourcePixelsAndRoundTrips() {
        val landscape = previewScaleAfterResize(4f, 4f, 2f)
        assertEquals(1f, landscape / 2f, 0.0001f)
        assertEquals(4f, previewScaleAfterResize(landscape, 2f, 4f), 0.0001f)
    }
}
