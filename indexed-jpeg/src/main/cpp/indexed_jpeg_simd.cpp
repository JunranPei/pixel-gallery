#include "indexed_jpeg_simd.h"

#include <csetjmp>
#include <cstdio>
#include <limits>

extern "C" {
#include <jpeglib.h>
}

namespace {

struct JpegError {
    jpeg_error_mgr base{};
    std::jmp_buf jump{};
};

void errorExit(j_common_ptr info) {
    auto* error = reinterpret_cast<JpegError*>(info->err);
    std::longjmp(error->jump, 1);
}

}  // namespace

extern "C" int indexed_jpeg_simd_decode_rgba(
    const uint8_t* encoded,
    size_t encodedBytes,
    uint32_t expectedWidth,
    uint32_t expectedHeight,
    uint8_t* destination,
    size_t destinationStride
) {
    if (encoded == nullptr || encodedBytes == 0 || destination == nullptr ||
        expectedWidth == 0 || expectedHeight == 0 ||
        encodedBytes > std::numeric_limits<unsigned long>::max() ||
        destinationStride < static_cast<size_t>(expectedWidth) * 4u) {
        return 0;
    }

    jpeg_decompress_struct info{};
    JpegError error{};
    info.err = jpeg_std_error(&error.base);
    error.base.error_exit = errorExit;
    bool created = false;

    if (setjmp(error.jump)) {
        if (created) jpeg_destroy_decompress(&info);
        return 0;
    }

    jpeg_create_decompress(&info);
    created = true;
    jpeg_mem_src(
        &info,
        encoded,
        static_cast<unsigned long>(encodedBytes));
    if (jpeg_read_header(&info, TRUE) != JPEG_HEADER_OK || info.arith_code ||
        info.image_width != expectedWidth || info.image_height != expectedHeight) {
        jpeg_destroy_decompress(&info);
        return 0;
    }

    info.out_color_space = JCS_EXT_RGBA;
    info.dct_method = JDCT_ISLOW;
    if (!jpeg_start_decompress(&info) || info.output_components != 4 ||
        info.output_width != expectedWidth || info.output_height != expectedHeight) {
        jpeg_destroy_decompress(&info);
        return 0;
    }

    while (info.output_scanline < info.output_height) {
        JSAMPROW row = destination +
                       static_cast<size_t>(info.output_scanline) * destinationStride;
        if (jpeg_read_scanlines(&info, &row, 1) != 1) break;
    }
    const bool complete = info.output_scanline == info.output_height;
    if (complete) jpeg_finish_decompress(&info);
    jpeg_destroy_decompress(&info);
    return complete ? 1 : 0;
}

extern "C" int indexed_jpeg_simd_compiled_with_simd() {
#ifdef WITH_SIMD
    return 1;
#else
    return 0;
#endif
}
