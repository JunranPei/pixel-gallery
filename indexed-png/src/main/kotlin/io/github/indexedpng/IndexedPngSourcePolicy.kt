package io.github.indexedpng

import java.io.RandomAccessFile

/**
 * Display semantics supported by the version-1 PNG tile pyramid.
 *
 * The pyramid contains premultiplied RGBA8 pixels and Android bitmaps produced from it are sRGB.
 * Sources requiring animation, colour-profile, wide-gamut, limited-range, or HDR interpretation
 * must therefore remain on the platform decoder.
 */
enum class IndexedPngSourceCompatibility(
    val canUseSrgbTilePyramid: Boolean,
    val description: String,
) {
    SDR_SRGB_STILL(true, "static SDR sRGB PNG"),
    ANIMATED(false, "animated PNG"),
    ICC_PROFILE(false, "PNG with an embedded ICC profile"),
    NON_SRGB_COLOR(false, "PNG with non-sRGB or incomplete colour metadata"),
    MASTERING_METADATA(false, "PNG with mastering-display metadata"),
    MALFORMED(false, "malformed or unsupported PNG structure"),
}

/** Shared source policy for index creation, indexed region decoding, and indexed previews. */
object IndexedPngSourcePolicy {
    /** Bump whenever cached RGBA output semantics or eligibility rules change. */
    const val CACHE_POLICY_VERSION = 2

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
    private val srgbChromaticities = intArrayOf(
        31_270, 32_900,
        64_000, 33_000,
        30_000, 60_000,
        15_000, 6_000,
    )

    fun inspect(sourcePath: String): IndexedPngSourceCompatibility = try {
        RandomAccessFile(sourcePath, "r").use(::inspect)
    } catch (_: Exception) {
        IndexedPngSourceCompatibility.MALFORMED
    }

    private fun inspect(input: RandomAccessFile): IndexedPngSourceCompatibility {
        if (input.length() < MINIMUM_PNG_BYTES) return IndexedPngSourceCompatibility.MALFORMED
        if (!input.readBytesExact(pngSignature.size).contentEquals(pngSignature)) {
            return IndexedPngSourceCompatibility.MALFORMED
        }

        var chunkCount = 0
        var seenHeader = false
        var seenImageData = false
        var imageDataEnded = false
        var animated = false
        var hasIccProfile = false
        var hasHdrMetadata = false
        var srgbIntent: Int? = null
        var gamma: Long? = null
        var chromaticities: LongArray? = null
        var cicp: ByteArray? = null

        while (input.filePointer + CHUNK_OVERHEAD <= input.length()) {
            if (++chunkCount > MAX_CHUNK_COUNT) return IndexedPngSourceCompatibility.MALFORMED
            val chunkBytes = input.readInt().toLong() and UINT32_MASK
            val typeBytes = input.readBytesExact(CHUNK_TYPE_BYTES)
            if (typeBytes.any { byte ->
                    val value = byte.toInt() and 0xff
                    value !in 'A'.code..'Z'.code && value !in 'a'.code..'z'.code
                }
            ) {
                return IndexedPngSourceCompatibility.MALFORMED
            }
            val type = typeBytes.toString(Charsets.US_ASCII)
            val dataStart = input.filePointer
            val crcStart = dataStart + chunkBytes
            if (crcStart < dataStart || crcStart > input.length() - CRC_BYTES) {
                return IndexedPngSourceCompatibility.MALFORMED
            }
            if (!seenHeader && type != "IHDR") return IndexedPngSourceCompatibility.MALFORMED

            when (type) {
                "IHDR" -> {
                    if (seenHeader || chunkBytes != IHDR_BYTES) {
                        return IndexedPngSourceCompatibility.MALFORMED
                    }
                    seenHeader = true
                }
                "PLTE" -> if (seenImageData) return IndexedPngSourceCompatibility.MALFORMED
                "IDAT" -> {
                    if (!seenHeader || imageDataEnded) return IndexedPngSourceCompatibility.MALFORMED
                    seenImageData = true
                }
                "IEND" -> {
                    if (!seenImageData || chunkBytes != 0L) {
                        return IndexedPngSourceCompatibility.MALFORMED
                    }
                    return classify(
                        animated = animated,
                        hasIccProfile = hasIccProfile,
                        hasHdrMetadata = hasHdrMetadata,
                        srgbIntent = srgbIntent,
                        gamma = gamma,
                        chromaticities = chromaticities,
                        cicp = cicp,
                    )
                }
                "acTL", "fcTL", "fdAT" -> animated = true
                "iCCP" -> {
                    if (seenImageData || hasIccProfile) return IndexedPngSourceCompatibility.MALFORMED
                    hasIccProfile = true
                }
                "mDCV", "cLLI" -> {
                    if (seenImageData) return IndexedPngSourceCompatibility.MALFORMED
                    hasHdrMetadata = true
                }
                "sRGB" -> {
                    if (seenImageData || srgbIntent != null || chunkBytes != SRGB_BYTES) {
                        return IndexedPngSourceCompatibility.MALFORMED
                    }
                    val intent = input.readUnsignedByte()
                    if (intent !in 0..3) return IndexedPngSourceCompatibility.MALFORMED
                    srgbIntent = intent
                }
                "gAMA" -> {
                    if (seenImageData || gamma != null || chunkBytes != GAMMA_BYTES) {
                        return IndexedPngSourceCompatibility.MALFORMED
                    }
                    gamma = input.readInt().toLong() and UINT32_MASK
                    if (gamma == 0L) return IndexedPngSourceCompatibility.MALFORMED
                }
                "cHRM" -> {
                    if (seenImageData || chromaticities != null || chunkBytes != CHROMATICITIES_BYTES) {
                        return IndexedPngSourceCompatibility.MALFORMED
                    }
                    chromaticities = LongArray(CHROMATICITY_VALUE_COUNT) {
                        input.readInt().toLong() and UINT32_MASK
                    }
                }
                "cICP" -> {
                    if (seenImageData || cicp != null || chunkBytes != CICP_BYTES) {
                        return IndexedPngSourceCompatibility.MALFORMED
                    }
                    cicp = input.readBytesExact(CICP_BYTES.toInt())
                }
                else -> {
                    // Unknown critical chunks change image interpretation and cannot be flattened safely.
                    if (typeBytes[0].toInt() and ANCILLARY_BIT == 0) {
                        return IndexedPngSourceCompatibility.MALFORMED
                    }
                }
            }

            input.seek(crcStart + CRC_BYTES)
            if (seenImageData && type != "IDAT") imageDataEnded = true
        }
        return IndexedPngSourceCompatibility.MALFORMED
    }

    private fun classify(
        animated: Boolean,
        hasIccProfile: Boolean,
        hasHdrMetadata: Boolean,
        srgbIntent: Int?,
        gamma: Long?,
        chromaticities: LongArray?,
        cicp: ByteArray?,
    ): IndexedPngSourceCompatibility {
        if (animated) return IndexedPngSourceCompatibility.ANIMATED
        if (hasHdrMetadata) return IndexedPngSourceCompatibility.MASTERING_METADATA

        // cICP has the highest PNG colour-chunk priority. Still reject a simultaneous ICC profile:
        // older Android decoders may ignore cICP and honour that lower-priority profile instead.
        if (cicp != null) {
            if (hasIccProfile) return IndexedPngSourceCompatibility.ICC_PROFILE
            return if (cicp.contentEquals(SRGB_CICP)) {
                IndexedPngSourceCompatibility.SDR_SRGB_STILL
            } else {
                IndexedPngSourceCompatibility.NON_SRGB_COLOR
            }
        }
        if (hasIccProfile) return IndexedPngSourceCompatibility.ICC_PROFILE
        if (srgbIntent != null) return IndexedPngSourceCompatibility.SDR_SRGB_STILL

        if (gamma == null && chromaticities == null) {
            // Android and the ordinary viewer path both treat an untagged PNG as sRGB.
            return IndexedPngSourceCompatibility.SDR_SRGB_STILL
        }
        if (gamma == null || chromaticities == null) {
            return IndexedPngSourceCompatibility.NON_SRGB_COLOR
        }
        val canonicalGamma = withinTolerance(gamma, SRGB_GAMMA, GAMMA_TOLERANCE)
        val canonicalChromaticities = chromaticities.indices.all { index ->
            withinTolerance(
                chromaticities[index],
                srgbChromaticities[index].toLong(),
                CHROMATICITY_TOLERANCE,
            )
        }
        return if (canonicalGamma && canonicalChromaticities) {
            IndexedPngSourceCompatibility.SDR_SRGB_STILL
        } else {
            IndexedPngSourceCompatibility.NON_SRGB_COLOR
        }
    }

    private fun withinTolerance(value: Long, expected: Long, tolerance: Long): Boolean =
        value in (expected - tolerance)..(expected + tolerance)

    private fun RandomAccessFile.readBytesExact(count: Int): ByteArray =
        ByteArray(count).also(::readFully)

    private val SRGB_CICP = byteArrayOf(1, 13, 0, 1)

    private const val UINT32_MASK = 0xffff_ffffL
    private const val MINIMUM_PNG_BYTES = 33L
    private const val CHUNK_OVERHEAD = 12L
    private const val CHUNK_TYPE_BYTES = 4
    private const val CRC_BYTES = 4L
    private const val IHDR_BYTES = 13L
    private const val SRGB_BYTES = 1L
    private const val GAMMA_BYTES = 4L
    private const val CHROMATICITIES_BYTES = 32L
    private const val CICP_BYTES = 4L
    private const val CHROMATICITY_VALUE_COUNT = 8
    private const val ANCILLARY_BIT = 0x20
    private const val MAX_CHUNK_COUNT = 1_000_000
    private const val SRGB_GAMMA = 45_455L
    private const val GAMMA_TOLERANCE = 2L
    private const val CHROMATICITY_TOLERANCE = 2L
}
