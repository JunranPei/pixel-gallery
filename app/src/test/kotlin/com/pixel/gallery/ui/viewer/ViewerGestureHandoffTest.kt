package com.pixel.gallery.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerGestureHandoffTest {

    @Test
    fun pointerStrokeKeepsHandoffBlockedUntilPointerUp() {
        assertFalse(canHandoff(pointerStrokeActive = true))
        assertTrue(canHandoff(pointerStrokeActive = false))
    }

    @Test
    fun zoomLifecycleKeepsHandoffBlockedDuringAnimation() {
        assertFalse(canHandoff(zoomGestureInProgress = true))
        assertTrue(shouldDoubleTapReturnToFit(zoomedIntent = true, currentRenderedScale = 1.001f))
        assertTrue(canHandoff(zoomGestureInProgress = false))
    }

    @Test
    fun tilesCannotReceiveTouchesBeforeAtomicHandoff() {
        assertFalse(
            canTilesReceiveInput(
                isActivePage = true,
                subsamplingReady = false,
                previewOwnsTransform = true,
            ),
        )
        assertTrue(
            canTilesReceiveInput(
                isActivePage = true,
                subsamplingReady = true,
                previewOwnsTransform = false,
            ),
        )
    }

    private fun canHandoff(
        pointerStrokeActive: Boolean = false,
        zoomGestureInProgress: Boolean = false,
    ): Boolean = canHandoffPreviewToTiles(
        ssivBaseDrawn = true,
        isActivePage = true,
        imageAssigned = true,
        subsamplingReady = false,
        previewGestureInProgress = zoomGestureInProgress,
        previewPointerStrokeActive = pointerStrokeActive,
    )
}
