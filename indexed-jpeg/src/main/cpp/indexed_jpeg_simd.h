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

extern "C" INDEXED_JPEG_SIMD_EXPORT int indexed_jpeg_simd_compiled_with_simd();
