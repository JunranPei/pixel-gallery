package com.pixel.gallery.ui.viewer

import org.junit.Assert.*
import org.junit.Test

class VideoOutputReadinessTest {
    @Test fun commitFromAnEarlierBindingCannotClearTheCover() {
        val readiness = VideoOutputReadiness()
        val first = Any()
        readiness.bind(first, true)
        readiness.rendered(first)
        val committedGeneration = readiness.generation
        val second = Any()
        readiness.bind(second, true)
        readiness.rendered(second)
        assertFalse(readiness.canReveal(committedGeneration))
        assertTrue(readiness.canReveal(readiness.generation))
        readiness.unbind(second)
        assertFalse(readiness.canReveal(readiness.generation))
    }

    @Test fun commitMustWaitForBothDecoderAndBuffer() {
        val readiness = VideoOutputReadiness()
        val output = Any()
        readiness.bind(output, false)
        readiness.bufferAvailable(output, readiness.generation)
        assertFalse(readiness.canReveal(readiness.generation))
        readiness.rendered(output)
        assertTrue(readiness.canReveal(readiness.generation))
    }

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
        assertFalse(readiness.readyAfter(previous, false))
        readiness.bufferAvailable(surface, readiness.generation)
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
        readiness.bufferAvailable(output, readiness.generation)
        readiness.bind(output, false)
        assertEquals(1, readiness.generation)
        assertTrue(readiness.readyAfter(0, false))
    }

    @Test fun bufferAloneDoesNotReplaceDecoderReadiness() {
        val readiness = VideoOutputReadiness()
        val output = Any()
        readiness.bind(output, false)
        readiness.bufferAvailable(output, readiness.generation)
        assertFalse(readiness.readyAfter(0, false))
        readiness.rendered(output)
        assertTrue(readiness.readyAfter(0, false))
    }

    @Test fun destroyedOutputCannotUncoverOrAcceptALateProbe() {
        val readiness = VideoOutputReadiness()
        val output = Any()
        readiness.bind(output, false)
        val oldGeneration = readiness.generation
        readiness.rendered(output)
        readiness.unbind(output)
        readiness.bufferAvailable(output, oldGeneration)
        assertFalse(readiness.readyAfter(0, false))
        readiness.bind(output, false)
        readiness.rendered(output)
        readiness.bufferAvailable(output, oldGeneration)
        assertFalse(readiness.readyAfter(0, false))
        readiness.bufferAvailable(output, readiness.generation)
        assertTrue(readiness.readyAfter(oldGeneration, false))
    }

    @Test fun lateOldViewDestructionCannotInvalidateTheNewOutput() {
        val readiness = VideoOutputReadiness()
        val old = Any()
        val current = Any()
        readiness.bind(old, false)
        val previous = readiness.generation
        readiness.bind(current, true)
        readiness.rendered(current)
        readiness.unbind(old)
        assertTrue(readiness.readyAfter(previous, true))
    }
}
