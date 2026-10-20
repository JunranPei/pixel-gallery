package com.pixel.gallery.glide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailGeometryTest {
    @Test fun rejectsPaddedCanonAndSonyPortraitPreviews() {
        assertFalse(thumbnailMatchesSourceAspect(120, 160, 5808, 3872, 270))
        assertFalse(thumbnailMatchesSourceAspect(120, 160, 7952, 5304, 270))
    }

    @Test fun matchesLandscapePortraitSquareAndPanorama() {
        assertTrue(thumbnailMatchesSourceAspect(160, 120, 4000, 3000, 0))
        assertTrue(thumbnailMatchesSourceAspect(120, 160, 4000, 3000, 90))
        assertTrue(thumbnailMatchesSourceAspect(200, 200, 4000, 4000, 180))
        assertTrue(thumbnailMatchesSourceAspect(512, 64, 16000, 2000, 0))
    }

    @Test fun allowsDecodeRoundingWithoutHidingLargeAspectErrors() {
        assertTrue(thumbnailMatchesSourceAspect(242, 363, 5808, 3872, 270))
        assertTrue(thumbnailMatchesSourceAspect(332, 497, 7952, 5304, 270))
        assertTrue(thumbnailMatchesSourceAspect(133, 200, 5808, 3872, 270))
        assertFalse(thumbnailMatchesSourceAspect(150, 200, 5808, 3872, 270))
    }

    @Test fun handlesNegativeAndFullTurnRotation() {
        assertTrue(thumbnailMatchesSourceAspect(120, 160, 4000, 3000, -90))
        assertTrue(thumbnailMatchesSourceAspect(160, 120, 4000, 3000, 360))
        assertTrue(thumbnailMatchesSourceAspect(120, 160, 4000, 3000, 450))
    }

    @Test fun leavesUnknownBoundsAloneAndAvoidsOverflow() {
        assertTrue(thumbnailMatchesSourceAspect(120, 160, 0, 0, 270))
        assertTrue(thumbnailMatchesSourceAspect(0, 160, 4000, 3000, 0))
        assertTrue(thumbnailMatchesSourceAspect(100000, 50000, 2000000000, 1000000000, 0))
        assertFalse(thumbnailMatchesSourceAspect(100000, 100000, 2000000000, 1000000000, 0))
    }

    @Test fun downsamplingBoundsPortraitAndExtremePanoramaMemory() {
        assertEquals(8, thumbnailFitSampleSize(5808, 3872, 512, 512))
        assertEquals(16, thumbnailFitSampleSize(5808, 3872, 200, 200))
        assertEquals(64, thumbnailFitSampleSize(40000, 400, 512, 512))
        assertEquals(64, thumbnailFitSampleSize(400, 40000, 512, 512))
        assertEquals(1, thumbnailFitSampleSize(80, 60, 200, 200))
        assertEquals(1, thumbnailFitSampleSize(0, 0, 200, 200))
    }
}
