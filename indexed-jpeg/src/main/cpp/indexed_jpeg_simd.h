#pragma once

#include <cstddef>
#include <cstdint>

#if defined(__GNUC__)
#define INDEXED_JPEG_SIMD_EXPORT __attribute__((visibility("default")))
#else
#define INDEXED_JPEG_SIMD_EXPORT
#endif

extern "C" INDEXED_JPEG_SIMD_EXPORT int indexed_jpeg_simd_decode_rgba(
    const uint8_t* encoded,
    size_t encodedBytes,
    uint32_t expectedWidth,
    uint32_t expectedHeight,
    uint8_t* destination,
    size_t destinationStride
);

// A pyramid decoder is tied to one serialized caller. Reusing it avoids
// rebuilding libjpeg's permanent pools and tables for every independent tile.
extern "C" INDEXED_JPEG_SIMD_EXPORT void* indexed_jpeg_simd_decoder_create();

extern "C" INDEXED_JPEG_SIMD_EXPORT void indexed_jpeg_simd_decoder_destroy(
    void* decoder
);

extern "C" INDEXED_JPEG_SIMD_EXPORT int indexed_jpeg_simd_decoder_decode_rgba(
    void* decoder,
    const uint8_t* encoded,
    size_t encodedBytes,
    uint32_t expectedWidth,
    uint32_t expectedHeight,
    uint8_t* destination,
    size_t destinationStride
);

extern "C" INDEXED_JPEG_SIMD_EXPORT int indexed_jpeg_simd_decoder_decode_rgb565(
    void* decoder,
    const uint8_t* encoded,
    size_t encodedBytes,
    uint32_t expectedWidth,
    uint32_t expectedHeight,
    uint8_t* destination,
    size_t destinationStride
);

extern "C" INDEXED_JPEG_SIMD_EXPORT int indexed_jpeg_simd_compiled_with_simd();
