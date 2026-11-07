package com.pixel.gallery.ui.components

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GalleryMotionTest {
    private class Clock : MonotonicFrameClock {
        private var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(1); now += 16_666_667L
            return onFrame(now)
        }
    }
    @Test fun layoutAndPageSpringsDoNotOvershootAtRest() {
        for (spec in listOf(GalleryMotion.fraction(), GalleryMotion.navigation<Float>(), GalleryMotion.reorder<Float>())) {
            val a = TargetBasedAnimation(spec, Float.VectorConverter, 0f, 1f)
            for (t in 0L..1000L) assertTrue(a.getValueFromNanos(t * 1_000_000) in 0f..1f)
        }
    }
    @Test fun sheetReleaseAndReversalStayBoundedAndFinishExactly() = runBlocking(Clock()) {
        var expanded = false
        val state = VerticalRevealState(false, this) { expanded = it }
        state.start(); state.dragBy(-80f, 400f)
        assertEquals(.2f, state.fraction, .0001f)
        state.finish(-1600f, 600f)
        assertTrue(expanded)
        repeat(5) { delay(1); assertTrue(state.fraction in 0f..1f) }
        val before = state.fraction
        state.settle(false)
        assertEquals(before, state.fraction, 0f)
        repeat(150) { delay(1); assertTrue(state.fraction in 0f..1f) }
        assertEquals(0f, state.fraction, 0f)
        state.settle(true)
        withTimeout(5000) { while (state.fraction != 1f) delay(1) }
        assertEquals(1f, state.fraction, 0f)
    }
    @Test fun grabbingCancelsSettlingAndCancelRestoresStart() = runBlocking(Clock()) {
        val state = VerticalRevealState(true, this) {}
        state.settle(false); delay(3)
        state.start()
        val grabbed = state.fraction
        delay(20)
        assertEquals(grabbed, state.fraction, 0f)
        state.dragBy(10000f, 400f)
        assertEquals(0f, state.fraction, 0f)
        state.cancel()
        val expected = if (grabbed >= .5f) 1f else 0f
        withTimeout(5000) { while (state.fraction != expected) delay(1) }
        assertEquals(expected, state.fraction, 0f)
    }
}
