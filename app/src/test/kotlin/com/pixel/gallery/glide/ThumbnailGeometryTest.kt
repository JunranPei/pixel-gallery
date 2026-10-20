package com.pixel.gallery.glide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailGeometryTest {
    @Test fun sampledAllocationRoundsUpWithoutOverflowOrEnlargingSmallOriginals() {
        assertEquals(817, thumbnailSampledDimension(13071, 16))
        assertEquals(802, thumbnailSampledDimension(12829, 16))
        assertEquals(801, thumbnailSampledDimension(1601, 2))
        assertEquals(601, thumbnailSampledDimension(1201, 2))
        assertEquals(80, thumbnailSampledDimension(80, 1))
        assertEquals(1, thumbnailSampledDimension(1, 64))
        assertEquals(1073741824, thumbnailSampledDimension(Int.MAX_VALUE, 2))
    }
    @Test fun viewerRejectsTinyButCorrectlyProportionedPreviews() {
        assertFalse(thumbnailMeetsMinimumLongEdge(145, 160, 17008, 18826, 512))
        assertFalse(thumbnailMeetsMinimumLongEdge(160, 157, 13071, 12829, 512))
        assertFalse(thumbnailMeetsMinimumLongEdge(511, 255, 4000, 2000, 512))
        assertTrue(thumbnailMeetsMinimumLongEdge(512, 256, 4000, 2000, 512))
        assertTrue(thumbnailMeetsMinimumLongEdge(554, 438, 9419, 7462, 512))
    }

    @Test fun viewerCapsRequiredResolutionAtTheOriginalWithoutUpscaling() {
        assertTrue(thumbnailMeetsMinimumLongEdge(80, 60, 80, 60, 512))
        assertFalse(thumbnailMeetsMinimumLongEdge(40, 30, 80, 60, 512))
        assertTrue(thumbnailMeetsMinimumLongEdge(128, 256, 128, 256, 512))
    }

    @Test fun minimumResolutionUsesTheLongEdgeForPanoramasAndRotatedImages() {
        assertTrue(thumbnailMeetsMinimumLongEdge(512, 6, 40000, 400, 512))
        assertTrue(thumbnailMeetsMinimumLongEdge(6, 512, 400, 40000, 512))
        assertTrue(thumbnailMeetsMinimumLongEdge(341, 512, 7952, 5304, 512))
    }

    @Test fun minimumResolutionDoesNotInferSmallOriginalsFromPartialMetadata() {
        assertFalse(thumbnailMeetsMinimumLongEdge(160, 120, 0, 120, 512))
        assertFalse(thumbnailMeetsMinimumLongEdge(160, 120, 0, 0, 512))
        assertFalse(thumbnailMeetsMinimumLongEdge(0, 512, 4000, 3000, 512))
        assertTrue(thumbnailMeetsMinimumLongEdge(512, 384, 0, 0, 512))
        assertTrue(thumbnailMeetsMinimumLongEdge(160, 120, 4000, 3000, 0))
    }

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
