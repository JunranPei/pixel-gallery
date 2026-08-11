package com.pixel.gallery.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerGestureHandoffTest {

    @Test
    fun pointerTakeoverKeepsHandoffBlockedUntilPointerUp() {
        var interactions = 0
        interactions = updatePreviewInteractionCount(interactions, 1) // zoom animation
        interactions = updatePreviewInteractionCount(interactions, 1) // pointer stroke
        interactions = updatePreviewInteractionCount(interactions, -1) // cancelled animation

        assertFalse(canHandoff(interactions))

        interactions = updatePreviewInteractionCount(interactions, -1) // pointer up
        assertTrue(canHandoff(interactions))
    }

    @Test
    fun pendingSecondDoubleTapBridgesOldAndReverseAnimations() {
        var interactions = 0
        interactions = updatePreviewInteractionCount(interactions, 1) // first animation
        interactions = updatePreviewInteractionCount(interactions, 1) // tap recognizer pending
        interactions = updatePreviewInteractionCount(interactions, -1) // old animation cancelled

        assertFalse(canHandoff(interactions))
        assertTrue(shouldDoubleTapReturnToFit(zoomedIntent = true, currentRenderedScale = 1.001f))

        interactions = updatePreviewInteractionCount(interactions, 1) // reverse animation
        interactions = updatePreviewInteractionCount(interactions, -1) // double tap resolved
        assertFalse(canHandoff(interactions))

        interactions = updatePreviewInteractionCount(interactions, -1) // reverse animation settled
        assertTrue(canHandoff(interactions))
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

    private fun canHandoff(interactions: Int): Boolean = canHandoffPreviewToTiles(
        ssivBaseDrawn = true,
        isActivePage = true,
        imageAssigned = true,
        subsamplingReady = false,
        previewGestureInProgress = false,
        previewInteractionCount = interactions,
    )
}
