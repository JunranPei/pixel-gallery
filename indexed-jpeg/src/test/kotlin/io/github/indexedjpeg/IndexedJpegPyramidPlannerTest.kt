package io.github.indexedjpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexedJpegPyramidPlannerTest {
    @Test
    fun smallImageDoesNotCreatePyramid() {
        val plan = IndexedJpegPyramidPlanner.plan(
            sourceWidth = 1920,
            sourceHeight = 1080,
            sourceBytes = 1_500_000,
            viewportWidth = 1080,
            viewportHeight = 1920,
        )

        assertTrue(plan.layers.isEmpty())
    }

    @Test
    fun longImageAddsOnlyUsefulIntermediateLevel() {
        val plan = IndexedJpegPyramidPlanner.plan(
            sourceWidth = 8_000,
            sourceHeight = 24_000,
            sourceBytes = 24L * 1024L * 1024L,
            viewportWidth = 928,
            viewportHeight = 2006,
        )

        assertEquals(listOf(2, 3, 4, 8), plan.layers.map { it.sampleSize })
        assertTrue(plan.sample2TilesAtWorstShallowViewport >= 6)
        assertTrue(
            plan.sample3TilesAtWorstShallowViewport <=
                plan.sample2TilesAtWorstShallowViewport - 2,
        )
        assertTrue(plan.layers.first { it.sampleSize == 3 }.maximumBytes > 0)
    }

    @Test
    fun expensiveIntermediateLevelIsRejectedByStorageModel() {
        val plan = IndexedJpegPyramidPlanner.plan(
            sourceWidth = 8_000,
            sourceHeight = 24_000,
            sourceBytes = 900L * 1024L * 1024L,
            viewportWidth = 928,
            viewportHeight = 2006,
        )

        assertEquals(listOf(2, 4, 8), plan.layers.map { it.sampleSize })
        assertTrue(plan.estimatedSample3Bytes > 0L)
    }

    @Test
    fun ordinaryMediumImageKeepsPowerOfTwoLevels() {
        val plan = IndexedJpegPyramidPlanner.plan(
            sourceWidth = 4_000,
            sourceHeight = 3_000,
            sourceBytes = 8L * 1024L * 1024L,
            viewportWidth = 928,
            viewportHeight = 2006,
        )

        assertEquals(listOf(2), plan.layers.map { it.sampleSize })
    }

    @Test
    fun largeCameraJpegUsesIntermediateLevelWithinMeasuredStorageBudget() {
        val plan = IndexedJpegPyramidPlanner.plan(
            sourceWidth = 16_320,
            sourceHeight = 12_240,
            sourceBytes = 34_285_842L,
            viewportWidth = 1_080,
            viewportHeight = 2_400,
        )

        assertEquals(listOf(2, 3, 4, 8), plan.layers.map { it.sampleSize })
        val intermediate = plan.layers.single { it.sampleSize == 3 }
        assertTrue(plan.estimatedSample3Bytes <= intermediate.maximumBytes.toLong())
    }
}
