package com.pixel.gallery.ui.viewer

import org.junit.Assert.*
import org.junit.Test

class VideoOutputReadinessTest {
    @Test fun oldTextureFrameCannotUncoverANewSurface() {
        val readiness = VideoOutputReadiness()
        val texture = Any()
        val surface = Any()
        readiness.bind(texture, true)
        readiness.rendered(texture)
        val previous = readiness.generation
        readiness.bind(surface, false)
        readiness.rendered(texture)
        assertFalse(readiness.readyAfter(previous, false))
        readiness.rendered(surface)
        assertTrue(readiness.readyAfter(previous, false))
    }

    @Test fun alreadyRenderedOutgoingOutputDoesNotCompleteHandoff() {
        val readiness = VideoOutputReadiness()
        val output = Any()
        readiness.bind(output, true)
        val previous = readiness.generation
        readiness.rendered(output)
        assertFalse(readiness.readyAfter(previous, true))
        assertFalse(readiness.readyAfter(previous, false))
    }

    @Test fun rapidReversalRejectsIntermediateSurfaceFrame() {
        val readiness = VideoOutputReadiness()
        val first = Any()
        val intermediate = Any()
        val final = Any()
        readiness.bind(first, true)
        readiness.bind(intermediate, false)
        val previous = readiness.generation
        readiness.bind(final, true)
        readiness.rendered(intermediate)
        assertFalse(readiness.readyAfter(previous, true))
        readiness.rendered(final)
        assertTrue(readiness.readyAfter(previous, true))
        assertFalse(readiness.readyAfter(previous, false))
    }

    @Test fun repeatedBindingDoesNotInvalidateAnAlreadyReadyOutput() {
        val readiness = VideoOutputReadiness()
        val output = Any()
        readiness.bind(output, false)
        readiness.rendered(output)
        readiness.bind(output, false)
        assertEquals(1, readiness.generation)
        assertTrue(readiness.readyAfter(0, false))
    }
}
