#include "indexed_jpeg_simd.h"

#include <csetjmp>
#include <cstdio>
#include <limits>
#include <new>

extern "C" {
#include <jpeglib.h>
}

namespace {

struct JpegError {
    jpeg_error_mgr base{};
    std::jmp_buf jump{};
};

struct Decoder {
    jpeg_decompress_struct info{};
    JpegError error{};
    bool created = false;
};

void errorExit(j_common_ptr info) {
    auto* error = reinterpret_cast<JpegError*>(info->err);
    std::longjmp(error->jump, 1);
}

}  // namespace

extern "C" void* indexed_jpeg_simd_decoder_create() {
    auto* decoder = new (std::nothrow) Decoder();
    if (decoder == nullptr) return nullptr;
    decoder->info.err = jpeg_std_error(&decoder->error.base);
    decoder->error.base.error_exit = errorExit;
    if (setjmp(decoder->error.jump)) {
        if (decoder->created) jpeg_destroy_decompress(&decoder->info);
        delete decoder;
        return nullptr;
    }
    jpeg_create_decompress(&decoder->info);
    decoder->created = true;
    return decoder;
}

extern "C" void indexed_jpeg_simd_decoder_destroy(void* decoderValue) {
    auto* decoder = static_cast<Decoder*>(decoderValue);
    if (decoder == nullptr) return;
    if (decoder->created) jpeg_destroy_decompress(&decoder->info);
    delete decoder;
}

extern "C" int indexed_jpeg_simd_decoder_decode_rgba(
    void* decoderValue,
    const uint8_t* encoded,
    size_t encodedBytes,
    uint32_t expectedWidth,
    uint32_t expectedHeight,
    uint8_t* destination,
    size_t destinationStride
) {
    auto* decoder = static_cast<Decoder*>(decoderValue);
    if (encoded == nullptr || encodedBytes == 0 || destination == nullptr ||
        decoder == nullptr || !decoder->created ||
        expectedWidth == 0 || expectedHeight == 0 ||
        encodedBytes > std::numeric_limits<unsigned long>::max() ||
        destinationStride < static_cast<size_t>(expectedWidth) * 4u) {
        return 0;
    }

    auto& info = decoder->info;
    if (setjmp(decoder->error.jump)) {
        jpeg_abort_decompress(&info);
        return 0;
    }

    jpeg_mem_src(
        &info,
        encoded,
        static_cast<unsigned long>(encodedBytes));
    if (jpeg_read_header(&info, TRUE) != JPEG_HEADER_OK || info.arith_code ||
        info.image_width != expectedWidth || info.image_height != expectedHeight) {
        jpeg_abort_decompress(&info);
        return 0;
    }

    info.out_color_space = JCS_EXT_RGBA;
    // These tiles are already low-frequency, quality-90 overview data. The
    // fast integer IDCT and merged chroma upsampling materially reduce the
    // work per decoded pixel while preserving the same dimensions and RGBA8
    // output contract. sample=1 continues to use the accurate seek decoder.
    info.dct_method = JDCT_IFAST;
    info.do_fancy_upsampling = FALSE;
    info.do_block_smoothing = FALSE;
    if (!jpeg_start_decompress(&info) || info.output_components != 4 ||
        info.output_width != expectedWidth || info.output_height != expectedHeight) {
        jpeg_abort_decompress(&info);
        return 0;
    }

    while (info.output_scanline < info.output_height) {
        JSAMPROW row = destination +
                       static_cast<size_t>(info.output_scanline) * destinationStride;
        if (jpeg_read_scanlines(&info, &row, 1) != 1) break;
    }
    const bool complete = info.output_scanline == info.output_height;
    if (!complete || !jpeg_finish_decompress(&info)) {
        jpeg_abort_decompress(&info);
        return 0;
    }
    return 1;
}

extern "C" int indexed_jpeg_simd_decode_rgba(
    const uint8_t* encoded,
    size_t encodedBytes,
    uint32_t expectedWidth,
    uint32_t expectedHeight,
    uint8_t* destination,
    size_t destinationStride
) {
    void* decoder = indexed_jpeg_simd_decoder_create();
    if (decoder == nullptr) return 0;
    const int result = indexed_jpeg_simd_decoder_decode_rgba(
        decoder,
        encoded,
        encodedBytes,
        expectedWidth,
        expectedHeight,
        destination,
        destinationStride);
    indexed_jpeg_simd_decoder_destroy(decoder);
    return result;
}

extern "C" int indexed_jpeg_simd_compiled_with_simd() {
#ifdef WITH_SIMD
    return 1;
#else
    return 0;
#endif
}
