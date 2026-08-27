package com.davemorrissey.labs.subscaleview

import org.junit.Assert.assertEquals
import org.junit.Test

class StoredSampleSelectionTest {
    private val adaptiveSamples = listOf(1, 2, 3, 4, 8)

    @Test
    fun sampleThreeUsesTheExistingFivePercentReconstructionAllowance() {
        assertEquals(2, selectStoredSampleSize(adaptiveSamples, 2.85f))
        assertEquals(3, selectStoredSampleSize(adaptiveSamples, 2.86f))
        assertEquals(3, selectStoredSampleSize(adaptiveSamples, 2.99f))
        assertEquals(3, selectStoredSampleSize(adaptiveSamples, 3.00f))
        assertEquals(3, selectStoredSampleSize(adaptiveSamples, 3.99f))
        assertEquals(4, selectStoredSampleSize(adaptiveSamples, 4.00f))
    }

    @Test
    fun intermediateLevelDoesNotRemainPastItsQualityAllowance() {
        assertEquals(0.35f, maximumScaleForStoredSample(3), 0.00001f)
        assertEquals(0.25f, maximumScaleForStoredSample(4), 0.00001f)
    }

    @Test
    fun stableDonghanLandingSwitchesFromTwoToThree() {
        assertEquals(
            3,
            selectRequiredStoredSampleSize(
                availableSamples = adaptiveSamples,
                effectiveScale = 0.336184f,
                currentSampleSize = 2,
                maximumSample = 4,
            ),
        )
    }

    @Test
    fun zoomingPastIntermediateQualityLimitReturnsToTwoImmediately() {
        assertEquals(
            2,
            selectRequiredStoredSampleSize(
                availableSamples = adaptiveSamples,
                effectiveScale = 0.351f,
                currentSampleSize = 3,
                maximumSample = 4,
            ),
        )
    }

    @Test
    fun fullImageLimitStillAppliesToAdaptiveDirectory() {
        assertEquals(
            3,
            selectStoredSampleSize(
                availableSamples = adaptiveSamples,
                inverseScale = 8f,
                maximumSample = 3,
            ),
        )
    }

    @Test
    fun conventionalDirectoryKeepsPowerOfTwoBehaviour() {
        val conventional = listOf(1, 2, 4, 8)
        assertEquals(2, selectStoredSampleSize(conventional, 3.9f))
        assertEquals(4, selectStoredSampleSize(conventional, 4f))
    }
}
