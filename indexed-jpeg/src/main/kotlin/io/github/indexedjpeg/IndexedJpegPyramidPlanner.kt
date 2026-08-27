package io.github.indexedjpeg

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Chooses the smallest useful JPEG pyramid for one source image.
 *
 * Power-of-two levels remain the compatibility baseline. An intermediate 3x level is added only
 * when geometry predicts that it removes at least two 1024px decodes from a shallow viewport and
 * its estimated compressed size can be amortized by a small number of cold viewports.
 */
internal object IndexedJpegPyramidPlanner {
    private const val TILE_SIZE = 1024
    private const val RGB_565_BYTES_PER_PIXEL = 2L
    private const val MIN_SAMPLE2_TILES = 6
    private const val MIN_SAVED_TILES = 2
    private const val MIN_SAVED_PERCENT = 25
    private const val STORAGE_AMORTIZATION_VIEWPORTS = 4L
    private const val ESTIMATE_NUMERATOR = 5L
    private const val ESTIMATE_DENOMINATOR = 4L
    private const val MAX_SAMPLE = 1 shl 20

    data class Layer(
        val sampleSize: Int,
        /** Zero means that only the container-wide limit applies. */
        val maximumBytes: Int = 0,
    )

    data class Plan(
        val layers: List<Layer>,
        val sample2TilesAtWorstShallowViewport: Int = 0,
        val sample3TilesAtWorstShallowViewport: Int = 0,
        val estimatedSample3Bytes: Long = 0L,
    )

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceBytes: Long,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Plan {
        val maximumSample = maximumPyramidSample(
            sourceWidth,
            sourceHeight,
            viewportWidth,
            viewportHeight,
        )
        if (maximumSample < 2) return Plan(emptyList())

        val baseline = ArrayList<Layer>()
        var sample = 2
        while (sample in 2..maximumSample) {
            baseline += Layer(sample)
            if (sample > maximumSample / 2) break
            sample *= 2
        }
        if (maximumSample < 4 || sourceBytes <= 0L) return Plan(baseline)

        val geometry = intermediateGeometry(
            sourceWidth,
            sourceHeight,
            viewportWidth,
            viewportHeight,
        )
        val savedTiles = geometry.sample2Tiles - geometry.sample3Tiles
        if (
            geometry.sample2Tiles < MIN_SAMPLE2_TILES ||
            savedTiles < MIN_SAVED_TILES ||
            savedTiles * 100 < geometry.sample2Tiles * MIN_SAVED_PERCENT
        ) {
            return Plan(
                layers = baseline,
                sample2TilesAtWorstShallowViewport = geometry.sample2Tiles,
                sample3TilesAtWorstShallowViewport = geometry.sample3Tiles,
            )
        }

        // A 1/3 layer contains roughly 1/9 of the source pixels. Use the source's actual
        // compressed bytes per pixel, with a conservative 25% allowance for independently
        // encoded tile headers and the fixed overview quality.
        val estimatedBytes = ceilDiv(sourceBytes, 9L)
            .coerceAtMost(Long.MAX_VALUE / ESTIMATE_NUMERATOR)
            .times(ESTIMATE_NUMERATOR)
            .let { ceilDiv(it, ESTIMATE_DENOMINATOR) }
        val savedDecodeBytes = savedTiles.toLong() * TILE_SIZE * TILE_SIZE *
            RGB_565_BYTES_PER_PIXEL
        val byteBudget = savedDecodeBytes
            .coerceAtMost(Long.MAX_VALUE / STORAGE_AMORTIZATION_VIEWPORTS)
            .times(STORAGE_AMORTIZATION_VIEWPORTS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
        if (estimatedBytes > byteBudget || byteBudget <= 0L) {
            return Plan(
                layers = baseline,
                sample2TilesAtWorstShallowViewport = geometry.sample2Tiles,
                sample3TilesAtWorstShallowViewport = geometry.sample3Tiles,
                estimatedSample3Bytes = estimatedBytes,
            )
        }

        val adaptive = ArrayList<Layer>(baseline.size + 1)
        baseline.forEach { layer ->
            adaptive += layer
            if (layer.sampleSize == 2) {
                // Native construction applies this limit to the real encoded result. A poor
                // source-size estimate therefore costs build time, never unbounded index space.
                adaptive += Layer(sampleSize = 3, maximumBytes = byteBudget.toInt())
            }
        }
        return Plan(
            layers = adaptive,
            sample2TilesAtWorstShallowViewport = geometry.sample2Tiles,
            sample3TilesAtWorstShallowViewport = geometry.sample3Tiles,
            estimatedSample3Bytes = estimatedBytes,
        )
    }

    private data class Geometry(val sample2Tiles: Int, val sample3Tiles: Int)

    private fun intermediateGeometry(
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Geometry {
        var bestSample2 = 0
        var pairedSample3 = 0
        val viewports = arrayOf(
            viewportWidth to viewportHeight,
            viewportHeight to viewportWidth,
        ).distinct()
        // sample=3 is quality-complete from downsample 3 until sample=4 becomes complete.
        val downsampleTenths = intArrayOf(30, 32, 35, 37, 39)
        for ((viewWidth, viewHeight) in viewports) {
            for (downsampleTenthsValue in downsampleTenths) {
                val visibleWidth = min(
                    sourceWidth.toLong(),
                    ceilDiv(viewWidth.toLong() * downsampleTenthsValue, 10L),
                )
                val visibleHeight = min(
                    sourceHeight.toLong(),
                    ceilDiv(viewHeight.toLong() * downsampleTenthsValue, 10L),
                )
                val sample2 = worstPhaseTileCount(
                    sourceWidth,
                    sourceHeight,
                    visibleWidth,
                    visibleHeight,
                    2,
                )
                val sample3 = worstPhaseTileCount(
                    sourceWidth,
                    sourceHeight,
                    visibleWidth,
                    visibleHeight,
                    3,
                )
                if (sample2 - sample3 > bestSample2 - pairedSample3 ||
                    (sample2 - sample3 == bestSample2 - pairedSample3 && sample2 > bestSample2)
                ) {
                    bestSample2 = sample2
                    pairedSample3 = sample3
                }
            }
        }
        return Geometry(bestSample2, pairedSample3)
    }

    private fun worstPhaseTileCount(
        sourceWidth: Int,
        sourceHeight: Int,
        visibleWidth: Long,
        visibleHeight: Long,
        sampleSize: Int,
    ): Int {
        val sourceTileSpan = TILE_SIZE.toLong() * sampleSize
        val totalAcross = ceilDiv(sourceWidth.toLong(), sourceTileSpan)
        val totalDown = ceilDiv(sourceHeight.toLong(), sourceTileSpan)
        // Add one for an unaligned viewport crossing both edge blocks, then clamp to the grid.
        val visibleAcross = min(totalAcross, ceilDiv(visibleWidth, sourceTileSpan) + 1L)
        val visibleDown = min(totalDown, ceilDiv(visibleHeight, sourceTileSpan) + 1L)
        return (visibleAcross * visibleDown).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun maximumPyramidSample(
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            return 0
        }
        val width = sourceWidth.toDouble()
        val height = sourceHeight.toDouble()
        val portraitScale = min(viewportWidth / width, viewportHeight / height)
        val landscapeScale = min(viewportHeight / width, viewportWidth / height)
        val requiredScale = min(1.0, max(portraitScale, landscapeScale))
        val targetWidth = max(1, ceil(width * requiredScale).toInt())
        val targetHeight = max(1, ceil(height * requiredScale).toInt())
        if (ceilDiv(sourceWidth, 2) < targetWidth || ceilDiv(sourceHeight, 2) < targetHeight) {
            return 0
        }

        var maximumSample = 2
        while (maximumSample <= MAX_SAMPLE / 2) {
            val next = maximumSample * 2
            // Preserve the existing fit-screen contract: no more than 5% reconstruction upscale.
            if (
                ceilDiv(sourceWidth, next).toLong() * 105L < targetWidth.toLong() * 100L ||
                ceilDiv(sourceHeight, next).toLong() * 105L < targetHeight.toLong() * 100L
            ) {
                break
            }
            maximumSample = next
        }
        return maximumSample
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private fun ceilDiv(value: Long, divisor: Long): Long =
        value / divisor + if (value % divisor == 0L) 0L else 1L
}
