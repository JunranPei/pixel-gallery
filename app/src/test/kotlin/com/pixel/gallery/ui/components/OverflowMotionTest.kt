package com.pixel.gallery.ui.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class OverflowMotionTest {
    private val closed = OverflowMotion.closed(Rect(320f, 32f, 368f, 80f))
    private val open = OverflowMotion.opened(Rect(132f, 72f, 368f, 392f), 32f)

    @Test fun startsAtButtonAndLandsExactlyOnMenu() {
        assertEquals(closed, OverflowMotion.frame(closed, open, 0f, true, 1f))
        assertEquals(open, OverflowMotion.frame(closed, open, 1f, true, 1f))
        assertEquals(closed, OverflowMotion.frame(open, closed, 1f, false, 1f))
    }
    @Test fun openingHasBoundedOvershootAndValidCorners() {
        val frames = (0..450).map { OverflowMotion.frame(closed, open, it / 450f, true, 1f) }
        assertTrue(frames.any { it.bounds.width > open.bounds.width })
        assertTrue(frames.all { it.bounds.width < open.bounds.width * 1.05f && it.corner >= 0f && it.corner <= it.bounds.minDimension / 2 + .001f })
    }
    @Test fun earlyCloseAndReopenArePositionContinuous() {
        val opening = OverflowMotion.frame(closed, open, .23f, true, 1f)
        assertEquals(opening, OverflowMotion.frame(opening, closed, 0f, false, 1f))
        val closing = OverflowMotion.frame(opening, closed, .31f, false, 1f)
        assertEquals(closing, OverflowMotion.frame(closing, open, 0f, true, 1f))
    }
    @Test fun contentsDisappearBeforeSurfaceReturns() {
        val early = OverflowMotion.frame(open, closed, .2f, false, 1f)
        assertEquals(0f, early.contentAlpha, .001f)
        assertEquals(1f, early.surfaceAlpha, .001f)
        assertTrue(early.bounds.width > closed.bounds.width)
    }
}
