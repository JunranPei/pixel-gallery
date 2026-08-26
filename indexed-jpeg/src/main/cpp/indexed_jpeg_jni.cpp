#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <csetjmp>
#include <cmath>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <sys/mman.h>
#include <utility>
#include <vector>
#include <unistd.h>

extern "C" {
#include "jpeglib.h"
}

namespace {

constexpr uint8_t kMagic[8] = {'I', 'J', 'X', 'M', 'C', 'U', '0', '1'};
// Version 2 stores the complete entropy position and an ABI-independent bit
// buffer field. Version 1 could truncate state on 64-bit devices and silently
// decode corrupt tiles, especially for large JPEG files. Version 3 appended an
// optional fit-screen JPEG but captured coefficients through libjpeg's fast MCU
// path, whose read-ahead made the recorded seek checkpoints unreliable. Version
// 4 keeps the same overview layout while preserving the original index path.
// Version 5 stores one complete power-of-two low-frequency layer. Version 6
// stores every power-of-two layer from 1/2 through the fit-screen level.
// Version 7 stores the generated levels as independently decodable JPEG tiles
// in one indexed container. A viewer can therefore read only the two-
// dimensional blocks intersecting its viewport instead of region-decoding a
// complete overview JPEG stream from its beginning.
constexpr uint32_t kLegacyTileFormatVersion = 2;
constexpr uint32_t kBrokenOverviewFormatVersion = 3;
constexpr uint32_t kFitOverviewFormatVersion = 4;
constexpr uint32_t kSingleLayerFormatVersion = 5;
constexpr uint32_t kWholeLayerPyramidFormatVersion = 6;
constexpr uint32_t kFormatVersion = 7;
constexpr uint32_t kOverviewMarker = 0x3152564F;  // OVR1
constexpr uint32_t kPyramidMarker = 0x31525950;  // PYR1
constexpr uint32_t kIndexedPyramidMarker = 0x32525950;  // PYR2
constexpr uint32_t kEndMarker = 0x31444E45;  // END1
constexpr uint32_t kMaxScans = 1024;
constexpr uint32_t kMaxRows = 1u << 20;
constexpr uint32_t kMaxRecordsPerRow = 1u << 20;
constexpr uint32_t kMaxOverviewPixels = 12u * 1024u * 1024u;
constexpr uint32_t kMaxOverviewBytes = 24u * 1024u * 1024u;
constexpr uint32_t kMaxPyramidBytes = 128u * 1024u * 1024u;
constexpr uint32_t kMaxPyramidLayers = 16u;
constexpr uint32_t kMaxPyramidTiles = 1u << 18;
constexpr uint32_t kPyramidTileSize = 1024u;
constexpr int kOverviewJpegQuality = 90;
constexpr uint64_t kSerializedOffsetBytes = 27u;

struct JpegError {
    jpeg_error_mgr base{};
    jmp_buf jump{};
    char message[JMSG_LENGTH_MAX]{};
};

void errorExit(j_common_ptr info) {
    auto* error = reinterpret_cast<JpegError*>(info->err);
    (*info->err->format_message)(info, error->message);
    longjmp(error->jump, 1);
}

struct IndexHandle {
    std::string sourcePath;
    huffman_index index{};
    uint32_t width = 0;
    uint32_t height = 0;
    int64_t sourceBytes = 0;
    int64_t sourceModifiedMillis = 0;
};

struct Header {
    uint32_t formatVersion = 0;
    int64_t sourceBytes = 0;
    int64_t sourceModifiedMillis = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t scanCount = 0;
    uint32_t totalRows = 0;
    uint32_t sampleSize = 0;
    uint32_t overviewWidth = 0;
    uint32_t overviewHeight = 0;
    uint32_t overviewSampleSize = 0;
    uint32_t overviewBytes = 0;
    long overviewOffset = 0;
};

struct PyramidTile {
    uint32_t x = 0;
    uint32_t y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t bytes = 0;
    uint64_t dataOffset = 0;
    std::vector<uint8_t> encoded;
};

struct PyramidLayer {
    uint32_t sampleSize = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t bytes = 0;
    uint32_t tileSize = 0;
    uint32_t tilesAcross = 0;
    uint32_t tilesDown = 0;
    std::vector<uint8_t> encoded;
    std::vector<PyramidTile> tiles;
};

struct PyramidHandle {
    FILE* file = nullptr;
    uint32_t sourceWidth = 0;
    uint32_t sourceHeight = 0;
    std::vector<PyramidLayer> layers;

    ~PyramidHandle() {
        if (file != nullptr) fclose(file);
    }
};

const PyramidTile* findPyramidTile(
    PyramidHandle* handle,
    int sampleSize,
    int tileX,
    int tileY
) {
    if (handle == nullptr || sampleSize <= 0 || tileX < 0 || tileY < 0) return nullptr;
    const PyramidLayer* selected = nullptr;
    for (const auto& layer : handle->layers) {
        if (layer.sampleSize == static_cast<uint32_t>(sampleSize)) {
            selected = &layer;
            break;
        }
    }
    if (selected == nullptr || static_cast<uint32_t>(tileX) >= selected->tilesAcross ||
        static_cast<uint32_t>(tileY) >= selected->tilesDown) {
        return nullptr;
    }
    const size_t position = static_cast<size_t>(tileY) * selected->tilesAcross +
                            static_cast<size_t>(tileX);
    return position < selected->tiles.size() ? &selected->tiles[position] : nullptr;
}

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        chars_ = value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
    }
    ~UtfChars() {
        if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
    }
    const char* get() const { return chars_; }
private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

void throwIOException(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

bool writeBytes(FILE* file, const void* data, size_t bytes) {
    return fwrite(data, 1, bytes, file) == bytes;
}

bool readBytes(FILE* file, void* data, size_t bytes) {
    return fread(data, 1, bytes, file) == bytes;
}

bool writeU8(FILE* file, uint8_t value) { return writeBytes(file, &value, 1); }
bool readU8(FILE* file, uint8_t* value) { return readBytes(file, value, 1); }

bool writeU16(FILE* file, uint16_t value) {
    uint8_t bytes[2] = {static_cast<uint8_t>(value), static_cast<uint8_t>(value >> 8)};
    return writeBytes(file, bytes, sizeof(bytes));
}

bool readU16(FILE* file, uint16_t* value) {
    uint8_t bytes[2];
    if (!readBytes(file, bytes, sizeof(bytes))) return false;
    *value = static_cast<uint16_t>(bytes[0]) |
             static_cast<uint16_t>(bytes[1] << 8);
    return true;
}

bool writeU32(FILE* file, uint32_t value) {
    uint8_t bytes[4] = {
        static_cast<uint8_t>(value), static_cast<uint8_t>(value >> 8),
        static_cast<uint8_t>(value >> 16), static_cast<uint8_t>(value >> 24),
    };
    return writeBytes(file, bytes, sizeof(bytes));
}

bool readU32(FILE* file, uint32_t* value) {
    uint8_t bytes[4];
    if (!readBytes(file, bytes, sizeof(bytes))) return false;
    *value = static_cast<uint32_t>(bytes[0]) |
             (static_cast<uint32_t>(bytes[1]) << 8) |
             (static_cast<uint32_t>(bytes[2]) << 16) |
             (static_cast<uint32_t>(bytes[3]) << 24);
    return true;
}

bool writeU64(FILE* file, uint64_t value) {
    uint8_t bytes[8];
    for (int i = 0; i < 8; ++i) bytes[i] = static_cast<uint8_t>(value >> (i * 8));
    return writeBytes(file, bytes, sizeof(bytes));
}

bool readU64(FILE* file, uint64_t* value) {
    uint8_t bytes[8];
    if (!readBytes(file, bytes, sizeof(bytes))) return false;
    uint64_t result = 0;
    for (int i = 0; i < 8; ++i) result |= static_cast<uint64_t>(bytes[i]) << (i * 8);
    *value = result;
    return true;
}

bool writeOffset(FILE* file, const huffman_offset_data& value) {
    return writeU64(file, static_cast<uint64_t>(value.bitstream_offset)) &&
           writeU16(file, static_cast<uint16_t>(value.prev_dc[0])) &&
           writeU16(file, static_cast<uint16_t>(value.prev_dc[1])) &&
           writeU16(file, static_cast<uint16_t>(value.prev_dc[2])) &&
           writeU16(file, value.EOBRUN) &&
           writeU64(file, static_cast<uint64_t>(value.get_buffer)) &&
           writeU16(file, value.restarts_to_go) &&
           writeU8(file, value.next_restart_num);
}

bool readOffset(FILE* file, huffman_offset_data* value) {
    uint16_t dc0, dc1, dc2;
    uint64_t bitstreamOffset, buffer;
    return readU64(file, &bitstreamOffset) &&
           (value->bitstream_offset = static_cast<unsigned long long>(bitstreamOffset), true) &&
           readU16(file, &dc0) && (value->prev_dc[0] = static_cast<int16_t>(dc0), true) &&
           readU16(file, &dc1) && (value->prev_dc[1] = static_cast<int16_t>(dc1), true) &&
           readU16(file, &dc2) && (value->prev_dc[2] = static_cast<int16_t>(dc2), true) &&
           readU16(file, &value->EOBRUN) &&
           readU64(file, &buffer) &&
           (value->get_buffer = static_cast<unsigned long long>(buffer), true) &&
           readU16(file, &value->restarts_to_go) &&
           readU8(file, &value->next_restart_num);
}

void freeIndex(huffman_index* index) {
    if (index == nullptr) return;
    if (index->scan != nullptr) {
        for (int scan = 0; scan < index->scan_count; ++scan) {
            if (index->scan[scan].offset != nullptr) {
                for (int row = 0; row < index->total_iMCU_rows; ++row) {
                    free(index->scan[scan].offset[row]);
                }
                free(index->scan[scan].offset);
            }
        }
        free(index->scan);
    }
    std::memset(index, 0, sizeof(*index));
}

uint32_t ceilDiv(uint32_t value, uint32_t divisor) {
    return value / divisor + (value % divisor == 0u ? 0u : 1u);
}

uint32_t maximumPyramidSample(
    uint32_t sourceWidth,
    uint32_t sourceHeight,
    int viewportWidth,
    int viewportHeight
) {
    if (sourceWidth == 0 || sourceHeight == 0 ||
        viewportWidth <= 0 || viewportHeight <= 0) {
        return 0;
    }
    const double width = static_cast<double>(sourceWidth);
    const double height = static_cast<double>(sourceHeight);
    const double portraitScale = std::min(
        viewportWidth / width, viewportHeight / height);
    const double landscapeScale = std::min(
        viewportHeight / width, viewportWidth / height);
    const double requiredScale = std::min(
        1.0, std::max(portraitScale, landscapeScale));
    const uint32_t targetWidth = std::max<uint32_t>(
        1u, static_cast<uint32_t>(std::ceil(width * requiredScale)));
    const uint32_t targetHeight = std::max<uint32_t>(
        1u, static_cast<uint32_t>(std::ceil(height * requiredScale)));
    if (ceilDiv(sourceWidth, 2u) < targetWidth ||
        ceilDiv(sourceHeight, 2u) < targetHeight) {
        return 0;
    }

    uint32_t maximumSample = 2u;
    while (maximumSample <= (1u << 20) / 2u) {
        const uint32_t next = maximumSample * 2u;
        // Allow at most 5% reconstruction upscale at the fit-screen boundary.
        if (static_cast<uint64_t>(ceilDiv(sourceWidth, next)) * 105u <
                static_cast<uint64_t>(targetWidth) * 100u ||
            static_cast<uint64_t>(ceilDiv(sourceHeight, next)) * 105u <
                static_cast<uint64_t>(targetHeight) * 100u) {
            break;
        }
        maximumSample = next;
    }
    return maximumSample;
}

std::vector<uint8_t> encodeScaledJpegFromSource(
    const char* sourcePath,
    uint32_t sampleSize,
    uint32_t expectedWidth,
    uint32_t expectedHeight
) {
    if (sourcePath == nullptr || sampleSize < 2u ||
        (sampleSize & (sampleSize - 1u)) != 0u ||
        expectedWidth == 0 || expectedHeight == 0) {
        return {};
    }
    FILE* input = fopen(sourcePath, "rb");
    if (input == nullptr) return {};

    jpeg_decompress_struct decoder{};
    jpeg_compress_struct compressor{};
    JpegError decodeError{};
    JpegError encodeError{};
    decoder.err = jpeg_std_error(&decodeError.base);
    decodeError.base.error_exit = errorExit;
    compressor.err = jpeg_std_error(&encodeError.base);
    encodeError.base.error_exit = errorExit;
    bool decoderCreated = false;
    bool compressorCreated = false;
    unsigned char* encoded = nullptr;
    unsigned long encodedBytes = 0;
    unsigned char* decodedRow = nullptr;
    unsigned char* outputRow = nullptr;

    if (setjmp(decodeError.jump) || setjmp(encodeError.jump)) {
        free(decodedRow);
        if (outputRow != decodedRow) free(outputRow);
        free(encoded);
        if (compressorCreated) jpeg_destroy_compress(&compressor);
        if (decoderCreated) jpeg_destroy_decompress(&decoder);
        fclose(input);
        return {};
    }

    jpeg_create_decompress(&decoder);
    decoderCreated = true;
    jpeg_stdio_src(&decoder, input);
    if (jpeg_read_header(&decoder, TRUE) != JPEG_HEADER_OK || decoder.arith_code) {
        jpeg_destroy_decompress(&decoder);
        fclose(input);
        return {};
    }
    const uint32_t nativeSample = std::min<uint32_t>(sampleSize, 8u);
    const uint32_t postSample = sampleSize / nativeSample;
    decoder.scale_num = 1;
    decoder.scale_denom = static_cast<unsigned int>(nativeSample);
    decoder.out_color_space = JCS_EXT_RGB;
    if (!jpeg_start_decompress(&decoder) || decoder.output_components != 3 ||
        decoder.output_width == 0 || decoder.output_height == 0) {
        jpeg_destroy_decompress(&decoder);
        fclose(input);
        return {};
    }
    const size_t decodedRowBytes =
        static_cast<size_t>(decoder.output_width) * 3u;
    const size_t outputRowBytes = static_cast<size_t>(expectedWidth) * 3u;
    if (decodedRowBytes / 3u != decoder.output_width ||
        outputRowBytes / 3u != expectedWidth) {
        jpeg_destroy_decompress(&decoder);
        fclose(input);
        return {};
    }
    decodedRow = static_cast<unsigned char*>(malloc(decodedRowBytes));
    outputRow = postSample == 1u
        ? decodedRow
        : static_cast<unsigned char*>(malloc(outputRowBytes));
    if (decodedRow == nullptr || outputRow == nullptr) {
        free(decodedRow);
        if (outputRow != decodedRow) free(outputRow);
        jpeg_destroy_decompress(&decoder);
        fclose(input);
        return {};
    }

    jpeg_create_compress(&compressor);
    compressorCreated = true;
    jpeg_mem_dest(&compressor, &encoded, &encodedBytes);
    compressor.image_width = expectedWidth;
    compressor.image_height = expectedHeight;
    compressor.input_components = 3;
    compressor.in_color_space = JCS_RGB;
    jpeg_set_defaults(&compressor);
    jpeg_set_quality(&compressor, kOverviewJpegQuality, TRUE);
    jpeg_start_compress(&compressor, TRUE);

    uint32_t decodedY = 0;
    for (uint32_t outputY = 0; outputY < expectedHeight; ++outputY) {
        const uint32_t targetY = std::min<uint32_t>(
            outputY * postSample,
            static_cast<uint32_t>(decoder.output_height - 1u));
        while (decoder.output_scanline <= targetY) {
            JSAMPROW row = decodedRow;
            if (jpeg_read_scanlines(&decoder, &row, 1) != 1) {
                free(decodedRow);
                if (outputRow != decodedRow) free(outputRow);
                jpeg_destroy_compress(&compressor);
                jpeg_destroy_decompress(&decoder);
                free(encoded);
                fclose(input);
                return {};
            }
            decodedY = static_cast<uint32_t>(decoder.output_scanline - 1u);
        }
        if (decodedY != targetY) {
            free(decodedRow);
            if (outputRow != decodedRow) free(outputRow);
            jpeg_destroy_compress(&compressor);
            jpeg_destroy_decompress(&decoder);
            free(encoded);
            fclose(input);
            return {};
        }
        if (postSample > 1u) {
            for (uint32_t outputX = 0; outputX < expectedWidth; ++outputX) {
                const uint32_t inputX = std::min<uint32_t>(
                    outputX * postSample,
                    static_cast<uint32_t>(decoder.output_width - 1u));
                std::memcpy(
                    outputRow + static_cast<size_t>(outputX) * 3u,
                    decodedRow + static_cast<size_t>(inputX) * 3u,
                    3u);
            }
        }
        JSAMPROW row = outputRow;
        if (jpeg_write_scanlines(&compressor, &row, 1) != 1) {
            free(decodedRow);
            if (outputRow != decodedRow) free(outputRow);
            jpeg_destroy_compress(&compressor);
            jpeg_destroy_decompress(&decoder);
            free(encoded);
            fclose(input);
            return {};
        }
    }
    while (decoder.output_scanline < decoder.output_height) {
        JSAMPROW row = decodedRow;
        if (jpeg_read_scanlines(&decoder, &row, 1) != 1) break;
    }
    jpeg_finish_decompress(&decoder);
    jpeg_finish_compress(&compressor);
    free(decodedRow);
    if (outputRow != decodedRow) free(outputRow);
    jpeg_destroy_compress(&compressor);
    jpeg_destroy_decompress(&decoder);
    fclose(input);
    if (encoded == nullptr || encodedBytes == 0 ||
        encodedBytes > kMaxPyramidBytes) {
        free(encoded);
        return {};
    }
    std::vector<uint8_t> result(encoded, encoded + encodedBytes);
    free(encoded);
    return result;
}

bool encodeScaledTilesFromSource(
    const char* sourcePath,
    uint32_t sampleSize,
    uint32_t expectedWidth,
    uint32_t expectedHeight,
    PyramidLayer* layer
) {
    if (sourcePath == nullptr || layer == nullptr || sampleSize < 2u ||
        (sampleSize & (sampleSize - 1u)) != 0u ||
        expectedWidth == 0 || expectedHeight == 0) {
        return false;
    }
    const uint32_t tilesAcross = ceilDiv(expectedWidth, kPyramidTileSize);
    const uint32_t tilesDown = ceilDiv(expectedHeight, kPyramidTileSize);
    const uint64_t tileCount = static_cast<uint64_t>(tilesAcross) * tilesDown;
    if (tilesAcross == 0 || tilesDown == 0 || tileCount > kMaxPyramidTiles) {
        return false;
    }

    FILE* input = fopen(sourcePath, "rb");
    if (input == nullptr) return false;
    jpeg_decompress_struct decoder{};
    JpegError decodeError{};
    JpegError encodeError{};
    decoder.err = jpeg_std_error(&decodeError.base);
    decodeError.base.error_exit = errorExit;
    encodeError.base.error_exit = errorExit;
    bool decoderCreated = false;
    unsigned char* decodedRow = nullptr;
    unsigned char* outputRow = nullptr;
    uint64_t* accumulatedRow = nullptr;
    auto* encoders = static_cast<jpeg_compress_struct*>(
        calloc(tilesAcross, sizeof(jpeg_compress_struct)));
    auto* encoded = static_cast<unsigned char**>(
        calloc(tilesAcross, sizeof(unsigned char*)));
    auto* encodedBytes = static_cast<unsigned long*>(
        calloc(tilesAcross, sizeof(unsigned long)));
    auto* encoderCreated = static_cast<uint8_t*>(calloc(tilesAcross, 1u));

    auto cleanup = [&]() {
        if (encoders != nullptr && encoderCreated != nullptr) {
            for (uint32_t x = 0; x < tilesAcross; ++x) {
                if (encoderCreated[x] != 0u) jpeg_destroy_compress(&encoders[x]);
            }
        }
        if (encoded != nullptr) {
            for (uint32_t x = 0; x < tilesAcross; ++x) free(encoded[x]);
        }
        free(encoders);
        free(encoded);
        free(encodedBytes);
        free(encoderCreated);
        free(decodedRow);
        if (outputRow != decodedRow) free(outputRow);
        free(accumulatedRow);
        if (decoderCreated) jpeg_destroy_decompress(&decoder);
        fclose(input);
    };

    if (encoders == nullptr || encoded == nullptr || encodedBytes == nullptr ||
        encoderCreated == nullptr || setjmp(decodeError.jump) ||
        setjmp(encodeError.jump)) {
        cleanup();
        return false;
    }

    jpeg_create_decompress(&decoder);
    decoderCreated = true;
    jpeg_stdio_src(&decoder, input);
    if (jpeg_read_header(&decoder, TRUE) != JPEG_HEADER_OK || decoder.arith_code) {
        cleanup();
        return false;
    }
    const uint32_t nativeSample = std::min<uint32_t>(sampleSize, 8u);
    const uint32_t postSample = sampleSize / nativeSample;
    decoder.scale_num = 1;
    decoder.scale_denom = static_cast<unsigned int>(nativeSample);
    decoder.out_color_space = JCS_EXT_RGB;
    if (!jpeg_start_decompress(&decoder) || decoder.output_components != 3 ||
        decoder.output_width == 0 || decoder.output_height == 0) {
        cleanup();
        return false;
    }
    const size_t decodedRowBytes = static_cast<size_t>(decoder.output_width) * 3u;
    const size_t outputRowBytes = static_cast<size_t>(expectedWidth) * 3u;
    if (decodedRowBytes / 3u != decoder.output_width ||
        outputRowBytes / 3u != expectedWidth ||
        decoder.output_width < expectedWidth ||
        decoder.output_height < expectedHeight ||
        (postSample == 1u &&
         (decoder.output_width != expectedWidth ||
          decoder.output_height != expectedHeight))) {
        cleanup();
        return false;
    }
    decodedRow = static_cast<unsigned char*>(malloc(decodedRowBytes));
    outputRow = postSample == 1u
        ? decodedRow
        : static_cast<unsigned char*>(malloc(outputRowBytes));
    if (postSample > 1u) {
        const size_t accumulatedItems = static_cast<size_t>(expectedWidth) * 3u;
        if (accumulatedItems / 3u != expectedWidth ||
            accumulatedItems > SIZE_MAX / sizeof(uint64_t)) {
            cleanup();
            return false;
        }
        accumulatedRow = static_cast<uint64_t*>(
            calloc(accumulatedItems, sizeof(uint64_t)));
    }
    if (decodedRow == nullptr || outputRow == nullptr ||
        (postSample > 1u && accumulatedRow == nullptr)) {
        cleanup();
        return false;
    }

    layer->sampleSize = sampleSize;
    layer->width = expectedWidth;
    layer->height = expectedHeight;
    layer->tileSize = kPyramidTileSize;
    layer->tilesAcross = tilesAcross;
    layer->tilesDown = tilesDown;
    layer->bytes = 0;
    layer->encoded.clear();
    layer->tiles.clear();
    layer->tiles.reserve(static_cast<size_t>(tileCount));

    for (uint32_t outputY = 0; outputY < expectedHeight; ++outputY) {
        const uint32_t tileY = outputY / kPyramidTileSize;
        const uint32_t localY = outputY % kPyramidTileSize;
        const uint32_t tileHeight = std::min(
            kPyramidTileSize, expectedHeight - tileY * kPyramidTileSize);
        if (localY == 0u) {
            for (uint32_t tileX = 0; tileX < tilesAcross; ++tileX) {
                encoders[tileX] = jpeg_compress_struct{};
                encoders[tileX].err = jpeg_std_error(&encodeError.base);
                encodeError.base.error_exit = errorExit;
                encoded[tileX] = nullptr;
                encodedBytes[tileX] = 0;
                jpeg_create_compress(&encoders[tileX]);
                encoderCreated[tileX] = 1u;
                jpeg_mem_dest(
                    &encoders[tileX], &encoded[tileX], &encodedBytes[tileX]);
                encoders[tileX].image_width = std::min(
                    kPyramidTileSize,
                    expectedWidth - tileX * kPyramidTileSize);
                encoders[tileX].image_height = tileHeight;
                encoders[tileX].input_components = 3;
                encoders[tileX].in_color_space = JCS_RGB;
                jpeg_set_defaults(&encoders[tileX]);
                jpeg_set_quality(&encoders[tileX], kOverviewJpegQuality, TRUE);
                jpeg_start_compress(&encoders[tileX], TRUE);
            }
        }

        if (postSample == 1u) {
            JSAMPROW row = decodedRow;
            if (jpeg_read_scanlines(&decoder, &row, 1) != 1) {
                cleanup();
                return false;
            }
        } else {
            const uint32_t sourceYStart = outputY * postSample;
            const uint32_t sourceYEnd = std::min<uint32_t>(
                decoder.output_height,
                sourceYStart + postSample);
            if (decoder.output_scanline != sourceYStart ||
                sourceYStart >= sourceYEnd) {
                cleanup();
                return false;
            }
            std::memset(
                accumulatedRow,
                0,
                static_cast<size_t>(expectedWidth) * 3u * sizeof(uint64_t));
            for (uint32_t sourceY = sourceYStart; sourceY < sourceYEnd; ++sourceY) {
                JSAMPROW row = decodedRow;
                if (jpeg_read_scanlines(&decoder, &row, 1) != 1) {
                    cleanup();
                    return false;
                }
                for (uint32_t outputX = 0; outputX < expectedWidth; ++outputX) {
                    const uint32_t sourceXStart = outputX * postSample;
                    const uint32_t sourceXEnd = std::min<uint32_t>(
                        decoder.output_width,
                        sourceXStart + postSample);
                    for (uint32_t sourceX = sourceXStart; sourceX < sourceXEnd; ++sourceX) {
                        const size_t inputOffset = static_cast<size_t>(sourceX) * 3u;
                        const size_t outputOffset = static_cast<size_t>(outputX) * 3u;
                        accumulatedRow[outputOffset] += decodedRow[inputOffset];
                        accumulatedRow[outputOffset + 1u] += decodedRow[inputOffset + 1u];
                        accumulatedRow[outputOffset + 2u] += decodedRow[inputOffset + 2u];
                    }
                }
            }
            const uint64_t rowsInBox = sourceYEnd - sourceYStart;
            for (uint32_t outputX = 0; outputX < expectedWidth; ++outputX) {
                const uint32_t sourceXStart = outputX * postSample;
                const uint32_t sourceXEnd = std::min<uint32_t>(
                    decoder.output_width,
                    sourceXStart + postSample);
                const uint64_t pixelCount =
                    rowsInBox * static_cast<uint64_t>(sourceXEnd - sourceXStart);
                const size_t offset = static_cast<size_t>(outputX) * 3u;
                outputRow[offset] = static_cast<unsigned char>(
                    (accumulatedRow[offset] + pixelCount / 2u) / pixelCount);
                outputRow[offset + 1u] = static_cast<unsigned char>(
                    (accumulatedRow[offset + 1u] + pixelCount / 2u) / pixelCount);
                outputRow[offset + 2u] = static_cast<unsigned char>(
                    (accumulatedRow[offset + 2u] + pixelCount / 2u) / pixelCount);
            }
        }
        for (uint32_t tileX = 0; tileX < tilesAcross; ++tileX) {
            JSAMPROW row = outputRow +
                static_cast<size_t>(tileX) * kPyramidTileSize * 3u;
            if (jpeg_write_scanlines(&encoders[tileX], &row, 1) != 1) {
                cleanup();
                return false;
            }
        }

        if (localY + 1u == tileHeight) {
            for (uint32_t tileX = 0; tileX < tilesAcross; ++tileX) {
                jpeg_finish_compress(&encoders[tileX]);
                if (encoded[tileX] == nullptr || encodedBytes[tileX] == 0 ||
                    encodedBytes[tileX] > UINT32_MAX) {
                    cleanup();
                    return false;
                }
                PyramidTile tile;
                tile.x = tileX;
                tile.y = tileY;
                tile.width = std::min(
                    kPyramidTileSize,
                    expectedWidth - tileX * kPyramidTileSize);
                tile.height = tileHeight;
                tile.bytes = static_cast<uint32_t>(encodedBytes[tileX]);
                tile.encoded.assign(
                    encoded[tileX], encoded[tileX] + encodedBytes[tileX]);
                free(encoded[tileX]);
                encoded[tileX] = nullptr;
                encodedBytes[tileX] = 0;
                jpeg_destroy_compress(&encoders[tileX]);
                encoderCreated[tileX] = 0u;
                const uint64_t nextBytes =
                    static_cast<uint64_t>(layer->bytes) + tile.bytes;
                if (nextBytes > kMaxPyramidBytes || nextBytes > UINT32_MAX) {
                    cleanup();
                    return false;
                }
                layer->bytes = static_cast<uint32_t>(nextBytes);
                layer->tiles.push_back(std::move(tile));
            }
        }
    }

    while (decoder.output_scanline < decoder.output_height) {
        JSAMPROW row = decodedRow;
        if (jpeg_read_scanlines(&decoder, &row, 1) != 1) break;
    }
    jpeg_finish_decompress(&decoder);
    decoderCreated = false;
    jpeg_destroy_decompress(&decoder);
    fclose(input);
    input = nullptr;
    free(decodedRow);
    if (outputRow != decodedRow) free(outputRow);
    free(accumulatedRow);
    decodedRow = nullptr;
    outputRow = nullptr;
    accumulatedRow = nullptr;
    free(encoders);
    free(encoded);
    free(encodedBytes);
    free(encoderCreated);
    return layer->tiles.size() == tileCount && layer->bytes > 0;
}

std::vector<PyramidLayer> encodePyramidFromSource(
    const char* sourcePath,
    uint32_t sourceWidth,
    uint32_t sourceHeight,
    int viewportWidth,
    int viewportHeight
) {
    std::vector<PyramidLayer> layers;
    const uint32_t maximumSample = maximumPyramidSample(
        sourceWidth, sourceHeight, viewportWidth, viewportHeight);
    uint64_t totalBytes = 0;
    for (uint32_t sample = 2u; sample != 0u && sample <= maximumSample;
         sample *= 2u) {
        PyramidLayer layer;
        const uint32_t layerWidth = ceilDiv(sourceWidth, sample);
        const uint32_t layerHeight = ceilDiv(sourceHeight, sample);
        if (!encodeScaledTilesFromSource(
                sourcePath, sample, layerWidth, layerHeight, &layer)) {
            return {};
        }
        totalBytes += layer.bytes;
        if (totalBytes > kMaxPyramidBytes || layers.size() >= kMaxPyramidLayers) {
            return {};
        }
        layers.push_back(std::move(layer));
        if (sample > maximumSample / 2u) break;
    }
    return layers;
}

uint32_t pyramidPayloadBytes(const std::vector<PyramidLayer>& layers) {
    if (layers.empty() || layers.size() > kMaxPyramidLayers) return 0;
    uint64_t total = 8u + static_cast<uint64_t>(layers.size()) * 28u;
    for (const auto& layer : layers) {
        if (layer.tiles.empty() ||
            layer.tiles.size() != static_cast<uint64_t>(layer.tilesAcross) * layer.tilesDown) {
            return 0;
        }
        total += static_cast<uint64_t>(layer.tiles.size()) * 20u;
        for (const auto& tile : layer.tiles) total += tile.encoded.size();
    }
    return total <= kMaxPyramidBytes && total <= UINT32_MAX
        ? static_cast<uint32_t>(total) : 0u;
}

bool writeIndex(
    const char* path,
    const huffman_index& index,
    uint32_t width,
    uint32_t height,
    int64_t sourceBytes,
    int64_t sourceModifiedMillis,
    const std::vector<PyramidLayer>& layers
) {
    std::unique_ptr<FILE, decltype(&fclose)> file(fopen(path, "wb"), fclose);
    if (!file) return false;
    const uint32_t pyramidBytes = pyramidPayloadBytes(layers);
    if (!layers.empty() && pyramidBytes == 0) return false;
    const PyramidLayer* finest = layers.empty() ? nullptr : &layers.front();
    if (!writeBytes(file.get(), kMagic, sizeof(kMagic)) ||
        !writeU32(file.get(), kFormatVersion) ||
        !writeU64(file.get(), static_cast<uint64_t>(sourceBytes)) ||
        !writeU64(file.get(), static_cast<uint64_t>(sourceModifiedMillis)) ||
        !writeU32(file.get(), width) || !writeU32(file.get(), height) ||
        !writeU32(file.get(), static_cast<uint32_t>(index.scan_count)) ||
        !writeU32(file.get(), static_cast<uint32_t>(index.total_iMCU_rows)) ||
        !writeU32(file.get(), static_cast<uint32_t>(index.MCU_sample_size)) ||
        !writeU32(file.get(), finest == nullptr ? 0u : finest->width) ||
        !writeU32(file.get(), finest == nullptr ? 0u : finest->height) ||
        !writeU32(file.get(), finest == nullptr ? 0u : finest->sampleSize) ||
        !writeU32(file.get(), pyramidBytes)) {
        return false;
    }
    for (int scanNo = 0; scanNo < index.scan_count; ++scanNo) {
        const auto& scan = index.scan[scanNo];
        const uint64_t records64 = static_cast<uint64_t>(scan.MCU_rows_per_iMCU_row) *
                                   static_cast<uint64_t>(scan.MCUs_per_row);
        if (records64 == 0 || records64 > kMaxRecordsPerRow) return false;
        const uint32_t records = static_cast<uint32_t>(records64);
        if (!writeU64(file.get(), static_cast<uint64_t>(scan.bitstream_offset)) ||
            !writeU32(file.get(), static_cast<uint32_t>(scan.comps_in_scan)) ||
            !writeU32(file.get(), static_cast<uint32_t>(scan.MCUs_per_row)) ||
            !writeU32(file.get(), static_cast<uint32_t>(scan.MCU_rows_per_iMCU_row)) ||
            !writeOffset(file.get(), scan.prev_MCU_offset) ||
            !writeU32(file.get(), records)) {
            return false;
        }
        for (int row = 0; row < index.total_iMCU_rows; ++row) {
            if (scan.offset == nullptr || scan.offset[row] == nullptr) return false;
            for (uint32_t item = 0; item < records; ++item) {
                if (!writeOffset(file.get(), scan.offset[row][item])) return false;
            }
        }
    }
    if (!writeU32(file.get(), kOverviewMarker)) return false;
    if (!layers.empty()) {
        if (!writeU32(file.get(), kIndexedPyramidMarker) ||
            !writeU32(file.get(), static_cast<uint32_t>(layers.size()))) {
            return false;
        }
        for (const auto& layer : layers) {
            if (!writeU32(file.get(), layer.sampleSize) ||
                !writeU32(file.get(), layer.width) ||
                !writeU32(file.get(), layer.height) ||
                !writeU32(file.get(), layer.bytes) ||
                !writeU32(file.get(), layer.tileSize) ||
                !writeU32(file.get(), layer.tilesAcross) ||
                !writeU32(file.get(), layer.tilesDown)) {
                return false;
            }
        }
        for (const auto& layer : layers) {
            for (const auto& tile : layer.tiles) {
                if (!writeU32(file.get(), tile.x) || !writeU32(file.get(), tile.y) ||
                    !writeU32(file.get(), tile.width) ||
                    !writeU32(file.get(), tile.height) ||
                    !writeU32(file.get(), tile.bytes)) {
                    return false;
                }
            }
        }
        for (const auto& layer : layers) {
            for (const auto& tile : layer.tiles) {
                if (!writeBytes(file.get(), tile.encoded.data(), tile.encoded.size())) {
                    return false;
                }
            }
        }
    }
    if (!writeU32(file.get(), kEndMarker) || fflush(file.get()) != 0) return false;
    const int descriptor = fileno(file.get());
    return descriptor >= 0 && fsync(descriptor) == 0;
}

bool readHeader(FILE* file, Header* header) {
    uint8_t magic[sizeof(kMagic)];
    uint32_t version;
    uint64_t sourceBytes, sourceModified;
    if (!readBytes(file, magic, sizeof(magic)) ||
        std::memcmp(magic, kMagic, sizeof(kMagic)) != 0 ||
        !readU32(file, &version) ||
        (version != kLegacyTileFormatVersion &&
         version != kBrokenOverviewFormatVersion &&
         version != kFitOverviewFormatVersion &&
         version != kSingleLayerFormatVersion &&
         version != kWholeLayerPyramidFormatVersion &&
         version != kFormatVersion) ||
        !readU64(file, &sourceBytes) || !readU64(file, &sourceModified) ||
        !readU32(file, &header->width) || !readU32(file, &header->height) ||
        !readU32(file, &header->scanCount) || !readU32(file, &header->totalRows) ||
        !readU32(file, &header->sampleSize)) {
        return false;
    }
    header->formatVersion = version;
    header->sourceBytes = static_cast<int64_t>(sourceBytes);
    header->sourceModifiedMillis = static_cast<int64_t>(sourceModified);
    if (version != kLegacyTileFormatVersion &&
        (!readU32(file, &header->overviewWidth) ||
         !readU32(file, &header->overviewHeight) ||
         !readU32(file, &header->overviewSampleSize) ||
         !readU32(file, &header->overviewBytes))) {
        return false;
    }
    const uint32_t maximumOverviewBytes =
        (version == kWholeLayerPyramidFormatVersion || version == kFormatVersion)
        ? kMaxPyramidBytes : kMaxOverviewBytes;
    const bool pyramidDimensionsValid =
        (version != kWholeLayerPyramidFormatVersion && version != kFormatVersion) ||
        (header->overviewSampleSize == 2u &&
         header->overviewWidth == ceilDiv(header->width, 2u) &&
         header->overviewHeight == ceilDiv(header->height, 2u));
    const bool overviewValid = header->overviewBytes == 0
        ? header->overviewWidth == 0 && header->overviewHeight == 0 &&
          header->overviewSampleSize == 0
        : header->overviewBytes <= maximumOverviewBytes &&
          header->overviewWidth > 0 && header->overviewHeight > 0 &&
          header->overviewSampleSize >= 2 &&
          (header->overviewSampleSize & (header->overviewSampleSize - 1u)) == 0 &&
          (version == kWholeLayerPyramidFormatVersion || version == kFormatVersion ||
           static_cast<uint64_t>(header->overviewWidth) * header->overviewHeight <=
               kMaxOverviewPixels) &&
          pyramidDimensionsValid;
    const bool checkpointsReliable =
        version != kBrokenOverviewFormatVersion || header->overviewBytes == 0;
    return header->width > 0 && header->height > 0 &&
           header->scanCount > 0 && header->scanCount <= kMaxScans &&
           header->totalRows > 0 && header->totalRows <= kMaxRows &&
           header->sampleSize > 0 && overviewValid && checkpointsReliable;
}

bool readIndex(
    const char* path,
    int64_t expectedBytes,
    int64_t expectedModified,
    Header* header,
    huffman_index* index,
    bool headerOnly
) {
    std::unique_ptr<FILE, decltype(&fclose)> file(fopen(path, "rb"), fclose);
    if (!file || !readHeader(file.get(), header) ||
        header->sourceBytes != expectedBytes ||
        header->sourceModifiedMillis != expectedModified) {
        return false;
    }
    if (headerOnly) return true;

    index->MCU_sample_size = static_cast<int>(header->sampleSize);
    index->scan_count = static_cast<int>(header->scanCount);
    index->total_iMCU_rows = static_cast<int>(header->totalRows);
    index->scan = static_cast<huffman_scan_header*>(
        calloc(header->scanCount, sizeof(huffman_scan_header)));
    if (index->scan == nullptr) return false;

    for (uint32_t scanNo = 0; scanNo < header->scanCount; ++scanNo) {
        auto& scan = index->scan[scanNo];
        uint32_t comps, mcus, mcuRows, records;
        uint64_t scanBitstreamOffset;
        if (!readU64(file.get(), &scanBitstreamOffset)) {
            freeIndex(index);
            return false;
        }
        scan.bitstream_offset = static_cast<unsigned long long>(scanBitstreamOffset);
        if (!readU32(file.get(), &comps) || !readU32(file.get(), &mcus) ||
            !readU32(file.get(), &mcuRows) ||
            !readOffset(file.get(), &scan.prev_MCU_offset) ||
            !readU32(file.get(), &records) || records == 0 ||
            records > kMaxRecordsPerRow ||
            static_cast<uint64_t>(mcus) * mcuRows != records) {
            freeIndex(index);
            return false;
        }
        scan.comps_in_scan = static_cast<int>(comps);
        scan.MCUs_per_row = static_cast<int>(mcus);
        scan.MCU_rows_per_iMCU_row = static_cast<int>(mcuRows);
        scan.offset = static_cast<huffman_offset_data**>(
            calloc(header->totalRows, sizeof(huffman_offset_data*)));
        if (scan.offset == nullptr) {
            freeIndex(index);
            return false;
        }
        for (uint32_t row = 0; row < header->totalRows; ++row) {
            scan.offset[row] = static_cast<huffman_offset_data*>(
                calloc(records, sizeof(huffman_offset_data)));
            if (scan.offset[row] == nullptr) {
                freeIndex(index);
                return false;
            }
            for (uint32_t item = 0; item < records; ++item) {
                if (!readOffset(file.get(), &scan.offset[row][item])) {
                    freeIndex(index);
                    return false;
                }
            }
        }
    }
    uint32_t marker;
    if (header->formatVersion != kLegacyTileFormatVersion) {
        if (!readU32(file.get(), &marker) || marker != kOverviewMarker) {
            freeIndex(index);
            return false;
        }
        header->overviewOffset = ftell(file.get());
        if (header->overviewOffset < 0 ||
            (header->overviewBytes > 0 &&
             fseek(file.get(), static_cast<long>(header->overviewBytes), SEEK_CUR) != 0)) {
            freeIndex(index);
            return false;
        }
    }
    if (!readU32(file.get(), &marker) || marker != kEndMarker ||
        fgetc(file.get()) != EOF) {
        freeIndex(index);
        return false;
    }
    return true;
}

bool skipIndexRecords(FILE* file, const Header* header) {
    for (uint32_t scanNo = 0; scanNo < header->scanCount; ++scanNo) {
        uint64_t scanBitstreamOffset;
        uint32_t comps, mcus, mcuRows, records;
        huffman_offset_data previous{};
        if (!readU64(file, &scanBitstreamOffset) ||
            !readU32(file, &comps) || !readU32(file, &mcus) ||
            !readU32(file, &mcuRows) ||
            !readOffset(file, &previous) ||
            !readU32(file, &records) || records == 0 ||
            records > kMaxRecordsPerRow ||
            static_cast<uint64_t>(mcus) * mcuRows != records) {
            return false;
        }
        const uint64_t bytesToSkip = static_cast<uint64_t>(header->totalRows) *
                                     records * kSerializedOffsetBytes;
        if (bytesToSkip > static_cast<uint64_t>(LONG_MAX) ||
            fseek(file, static_cast<long>(bytesToSkip), SEEK_CUR) != 0) {
            return false;
        }
    }
    return true;
}

bool seekOverviewPayload(FILE* file, Header* header) {
    if (!skipIndexRecords(file, header)) return false;
    uint32_t marker;
    if (!readU32(file, &marker) || marker != kOverviewMarker) return false;
    header->overviewOffset = ftell(file);
    return header->overviewOffset >= 0;
}

bool readOverview(
    const char* path,
    int64_t expectedBytes,
    int64_t expectedModified,
    Header* header,
    std::vector<uint8_t>* overview
) {
    std::unique_ptr<FILE, decltype(&fclose)> file(fopen(path, "rb"), fclose);
    if (!file || !readHeader(file.get(), header) ||
        header->formatVersion == kWholeLayerPyramidFormatVersion ||
        header->formatVersion == kFormatVersion ||
        header->sourceBytes != expectedBytes ||
        header->sourceModifiedMillis != expectedModified ||
        header->overviewBytes == 0 ||
        !seekOverviewPayload(file.get(), header)) {
        return false;
    }
    uint32_t marker;
    overview->resize(header->overviewBytes);
    if (!readBytes(file.get(), overview->data(), overview->size()) ||
        !readU32(file.get(), &marker) || marker != kEndMarker ||
        fgetc(file.get()) != EOF) {
        overview->clear();
        return false;
    }
    return true;
}

bool readPyramidDirectory(
    FILE* file,
    Header* header,
    std::vector<PyramidLayer>* layers,
    long* dataOffset
) {
    const bool tiled = header->formatVersion == kFormatVersion;
    if ((!tiled && header->formatVersion != kWholeLayerPyramidFormatVersion) ||
        header->overviewBytes == 0 ||
        !seekOverviewPayload(file, header)) {
        return false;
    }
    uint32_t marker, count;
    const uint32_t expectedMarker = tiled ? kIndexedPyramidMarker : kPyramidMarker;
    if (!readU32(file, &marker) || marker != expectedMarker ||
        !readU32(file, &count) || count == 0 || count > kMaxPyramidLayers) {
        return false;
    }
    layers->clear();
    layers->reserve(count);
    const uint32_t layerRecordBytes = tiled ? 28u : 16u;
    uint64_t payloadBytes = 8u + static_cast<uint64_t>(count) * layerRecordBytes;
    uint32_t expectedSample = 2u;
    for (uint32_t i = 0; i < count; ++i) {
        PyramidLayer layer;
        if (!readU32(file, &layer.sampleSize) ||
            !readU32(file, &layer.width) ||
            !readU32(file, &layer.height) ||
            !readU32(file, &layer.bytes) ||
            layer.sampleSize != expectedSample || layer.bytes == 0 ||
            layer.width != ceilDiv(header->width, layer.sampleSize) ||
            layer.height != ceilDiv(header->height, layer.sampleSize)) {
            layers->clear();
            return false;
        }
        if (tiled) {
            if (!readU32(file, &layer.tileSize) ||
                !readU32(file, &layer.tilesAcross) ||
                !readU32(file, &layer.tilesDown) ||
                layer.tileSize != kPyramidTileSize ||
                layer.tilesAcross != ceilDiv(layer.width, layer.tileSize) ||
                layer.tilesDown != ceilDiv(layer.height, layer.tileSize) ||
                static_cast<uint64_t>(layer.tilesAcross) * layer.tilesDown >
                    kMaxPyramidTiles) {
                layers->clear();
                return false;
            }
        } else {
            payloadBytes += layer.bytes;
        }
        layers->push_back(std::move(layer));
        if (expectedSample > UINT32_MAX / 2u && i + 1u < count) {
            layers->clear();
            return false;
        }
        expectedSample *= 2u;
    }
    if (tiled) {
        for (auto& layer : *layers) {
            const uint64_t tileCount =
                static_cast<uint64_t>(layer.tilesAcross) * layer.tilesDown;
            payloadBytes += tileCount * 20u;
            layer.tiles.reserve(static_cast<size_t>(tileCount));
            uint64_t layerBytes = 0;
            for (uint32_t y = 0; y < layer.tilesDown; ++y) {
                for (uint32_t x = 0; x < layer.tilesAcross; ++x) {
                    PyramidTile tile;
                    const uint32_t expectedWidth = std::min(
                        layer.tileSize, layer.width - x * layer.tileSize);
                    const uint32_t expectedHeight = std::min(
                        layer.tileSize, layer.height - y * layer.tileSize);
                    if (!readU32(file, &tile.x) || !readU32(file, &tile.y) ||
                        !readU32(file, &tile.width) ||
                        !readU32(file, &tile.height) ||
                        !readU32(file, &tile.bytes) ||
                        tile.x != x || tile.y != y ||
                        tile.width != expectedWidth ||
                        tile.height != expectedHeight || tile.bytes == 0) {
                        layers->clear();
                        return false;
                    }
                    layerBytes += tile.bytes;
                    payloadBytes += tile.bytes;
                    if (layerBytes > UINT32_MAX || payloadBytes > kMaxPyramidBytes ||
                        payloadBytes > UINT32_MAX) {
                        layers->clear();
                        return false;
                    }
                    layer.tiles.push_back(std::move(tile));
                }
            }
            if (layerBytes != layer.bytes) {
                layers->clear();
                return false;
            }
        }
    }
    if (payloadBytes != header->overviewBytes ||
        layers->front().width != header->overviewWidth ||
        layers->front().height != header->overviewHeight ||
        layers->front().sampleSize != header->overviewSampleSize) {
        layers->clear();
        return false;
    }
    *dataOffset = ftell(file);
    if (*dataOffset < 0) {
        layers->clear();
        return false;
    }
    uint64_t encodedBytes = 0;
    if (tiled) {
        uint64_t offset = static_cast<uint64_t>(*dataOffset);
        for (auto& layer : *layers) {
            for (auto& tile : layer.tiles) {
                tile.dataOffset = offset;
                offset += tile.bytes;
                encodedBytes += tile.bytes;
            }
        }
    } else {
        encodedBytes = payloadBytes -
            (8u + static_cast<uint64_t>(count) * layerRecordBytes);
    }
    if (encodedBytes > static_cast<uint64_t>(LONG_MAX) ||
        fseek(file, static_cast<long>(encodedBytes), SEEK_CUR) != 0 ||
        !readU32(file, &marker) || marker != kEndMarker || fgetc(file) != EOF) {
        layers->clear();
        return false;
    }
    return true;
}

bool validateIndexStructure(
    const char* path,
    int64_t expectedBytes,
    int64_t expectedModified,
    Header* header
) {
    std::unique_ptr<FILE, decltype(&fclose)> file(fopen(path, "rb"), fclose);
    if (!file || !readHeader(file.get(), header) ||
        header->sourceBytes != expectedBytes ||
        header->sourceModifiedMillis != expectedModified) {
        return false;
    }

    if ((header->formatVersion == kWholeLayerPyramidFormatVersion ||
         header->formatVersion == kFormatVersion) &&
        header->overviewBytes > 0) {
        std::vector<PyramidLayer> layers;
        long dataOffset = 0;
        return readPyramidDirectory(file.get(), header, &layers, &dataOffset);
    }

    if (header->formatVersion == kLegacyTileFormatVersion) {
        if (!skipIndexRecords(file.get(), header)) return false;
    } else if (!seekOverviewPayload(file.get(), header)) {
        return false;
    }

    if (header->overviewBytes > 0 &&
        fseek(file.get(), static_cast<long>(header->overviewBytes), SEEK_CUR) != 0) {
        return false;
    }
    uint32_t marker;
    return readU32(file.get(), &marker) && marker == kEndMarker &&
           fgetc(file.get()) == EOF;
}

bool readPyramid(
    const char* path,
    int64_t expectedBytes,
    int64_t expectedModified,
    uint32_t requestedSample,
    Header* header,
    std::vector<PyramidLayer>* layers,
    std::vector<uint8_t>* encoded
) {
    std::unique_ptr<FILE, decltype(&fclose)> file(fopen(path, "rb"), fclose);
    if (!file || !readHeader(file.get(), header) ||
        header->sourceBytes != expectedBytes ||
        header->sourceModifiedMillis != expectedModified) {
        return false;
    }
    long dataOffset = 0;
    if (!readPyramidDirectory(file.get(), header, layers, &dataOffset)) return false;
    if (encoded == nullptr) return true;
    if (header->formatVersion == kFormatVersion) return false;
    uint64_t skip = 0;
    const PyramidLayer* selected = nullptr;
    for (const auto& layer : *layers) {
        if (layer.sampleSize == requestedSample) {
            selected = &layer;
            break;
        }
        skip += layer.bytes;
    }
    if (selected == nullptr || skip > static_cast<uint64_t>(LONG_MAX) ||
        fseek(file.get(), dataOffset + static_cast<long>(skip), SEEK_SET) != 0) {
        return false;
    }
    encoded->resize(selected->bytes);
    if (!readBytes(file.get(), encoded->data(), encoded->size())) {
        encoded->clear();
        return false;
    }
    return true;
}

bool isPowerOfTwo(int value) {
    return value > 0 && (value & (value - 1)) == 0;
}

void logDecodeFailure(const char* stage) {
    __android_log_print(ANDROID_LOG_ERROR, "IndexedJpeg", "decode failed at %s", stage);
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_buildIndex(
    JNIEnv* env,
    jobject,
    jstring sourcePathValue,
    jstring destinationPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis,
    jint viewportWidth,
    jint viewportHeight
) {
    UtfChars sourcePath(env, sourcePathValue);
    UtfChars destinationPath(env, destinationPathValue);
    if (sourcePath.get() == nullptr || destinationPath.get() == nullptr) return nullptr;

    std::unique_ptr<FILE, decltype(&fclose)> input(fopen(sourcePath.get(), "rb"), fclose);
    if (!input) {
        throwIOException(env, "Unable to open the JPEG source");
        return nullptr;
    }

    jpeg_decompress_struct info{};
    JpegError error{};
    info.err = jpeg_std_error(&error.base);
    error.base.error_exit = errorExit;
    bool created = false;
    huffman_index index{};
    bool indexCreated = false;

    if (setjmp(error.jump)) {
        if (indexCreated) freeIndex(&index);
        if (created) jpeg_destroy_decompress(&info);
        remove(destinationPath.get());
        throwIOException(env, error.message[0] ? error.message : "JPEG index build failed");
        return nullptr;
    }

    jpeg_create_decompress(&info);
    created = true;
    jpeg_stdio_src(&info, input.get());
    if (jpeg_read_header(&info, TRUE) != JPEG_HEADER_OK || info.arith_code) {
        throwIOException(env, "Only Huffman-coded JPEG images can be indexed");
        jpeg_destroy_decompress(&info);
        return nullptr;
    }
    const uint32_t width = info.image_width;
    const uint32_t height = info.image_height;
    jpeg_create_huffman_index(&info, &index);
    indexCreated = true;
    if (!jpeg_build_huffman_index(&info, &index)) {
        freeIndex(&index);
        jpeg_destroy_decompress(&info);
        throwIOException(env, "JPEG index construction did not complete");
        return nullptr;
    }

    // The baseline builder records these counts implicitly; persist them
    // explicitly so the index format is pointer- and ABI-independent.
    if (!info.progressive_mode) {
        index.scan[0].bitstream_offset = 0;
        std::memset(&index.scan[0].prev_MCU_offset, 0,
                    sizeof(index.scan[0].prev_MCU_offset));
        index.scan[0].comps_in_scan = info.comps_in_scan;
        index.scan[0].MCUs_per_row =
            static_cast<int>((info.MCUs_per_row + index.MCU_sample_size - 1) /
                             index.MCU_sample_size);
    }

    const int scanCount = index.scan_count;
    const std::vector<PyramidLayer> layers = info.progressive_mode
        ? std::vector<PyramidLayer>{}
        : encodePyramidFromSource(
            sourcePath.get(), width, height,
            static_cast<int>(viewportWidth), static_cast<int>(viewportHeight));
    const uint32_t pyramidBytes = pyramidPayloadBytes(layers);
    const bool written = writeIndex(
        destinationPath.get(), index, width, height,
        static_cast<int64_t>(sourceBytes),
        static_cast<int64_t>(sourceModifiedMillis), layers);
    const PyramidLayer* finest = layers.empty() ? nullptr : &layers.front();
    freeIndex(&index);
    jpeg_destroy_decompress(&info);
    if (!written) {
        remove(destinationPath.get());
        throwIOException(env, "Unable to write the completed JPEG index");
        return nullptr;
    }

    jint values[8] = {
        static_cast<jint>(width),
        static_cast<jint>(height),
        static_cast<jint>(scanCount),
        static_cast<jint>(pyramidBytes),
        static_cast<jint>(finest == nullptr ? 0u : finest->width),
        static_cast<jint>(finest == nullptr ? 0u : finest->height),
        static_cast<jint>(finest == nullptr ? 0u : finest->sampleSize),
        static_cast<jint>(layers.size()),
    };
    jintArray result = env->NewIntArray(8);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 8, values);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_validateIndex(
    JNIEnv* env,
    jobject,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    UtfChars indexPath(env, indexPathValue);
    if (indexPath.get() == nullptr) return JNI_FALSE;
    Header header{};
    return validateIndexStructure(
        indexPath.get(), static_cast<int64_t>(sourceBytes),
        static_cast<int64_t>(sourceModifiedMillis), &header)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_readIndexMetadata(
    JNIEnv* env,
    jobject,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    UtfChars indexPath(env, indexPathValue);
    if (indexPath.get() == nullptr) return nullptr;
    std::unique_ptr<FILE, decltype(&fclose)> file(
        fopen(indexPath.get(), "rb"), fclose);
    Header header{};
    if (!file || !readHeader(file.get(), &header) ||
        header.sourceBytes != static_cast<int64_t>(sourceBytes) ||
        header.sourceModifiedMillis != static_cast<int64_t>(sourceModifiedMillis)) {
        return nullptr;
    }
    jint values[7] = {
        static_cast<jint>(header.formatVersion),
        static_cast<jint>(header.width),
        static_cast<jint>(header.height),
        static_cast<jint>(header.overviewWidth),
        static_cast<jint>(header.overviewHeight),
        static_cast<jint>(header.overviewSampleSize),
        static_cast<jint>(header.overviewBytes),
    };
    jintArray result = env->NewIntArray(7);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 7, values);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_readOverview(
    JNIEnv* env,
    jobject,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    UtfChars indexPath(env, indexPathValue);
    if (indexPath.get() == nullptr) return nullptr;
    Header header{};
    std::vector<uint8_t> overview;
    if (!readOverview(
            indexPath.get(),
            static_cast<int64_t>(sourceBytes),
            static_cast<int64_t>(sourceModifiedMillis),
            &header,
            &overview)) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(overview.size()));
    if (result != nullptr && !overview.empty()) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(overview.size()),
            reinterpret_cast<const jbyte*>(overview.data()));
    }
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_readOverviewMetadata(
    JNIEnv* env,
    jobject,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    UtfChars indexPath(env, indexPathValue);
    if (indexPath.get() == nullptr) return nullptr;
    std::unique_ptr<FILE, decltype(&fclose)> file(
        fopen(indexPath.get(), "rb"), fclose);
    Header header{};
    if (!file || !readHeader(file.get(), &header) ||
        header.formatVersion == kWholeLayerPyramidFormatVersion ||
        header.formatVersion == kFormatVersion ||
        header.sourceBytes != static_cast<int64_t>(sourceBytes) ||
        header.sourceModifiedMillis != static_cast<int64_t>(sourceModifiedMillis) ||
        header.overviewBytes == 0) {
        return nullptr;
    }
    jint values[7] = {
        static_cast<jint>(header.formatVersion),
        static_cast<jint>(header.width),
        static_cast<jint>(header.height),
        static_cast<jint>(header.overviewWidth),
        static_cast<jint>(header.overviewHeight),
        static_cast<jint>(header.overviewSampleSize),
        static_cast<jint>(header.overviewBytes),
    };
    jintArray result = env->NewIntArray(7);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 7, values);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_readPyramidMetadata(
    JNIEnv* env,
    jobject,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    UtfChars indexPath(env, indexPathValue);
    if (indexPath.get() == nullptr) return nullptr;
    Header header{};
    std::vector<PyramidLayer> layers;
    if (!readPyramid(
            indexPath.get(),
            static_cast<int64_t>(sourceBytes),
            static_cast<int64_t>(sourceModifiedMillis),
            0u, &header, &layers, nullptr)) {
        return nullptr;
    }
    std::vector<jint> values(5u + layers.size() * 5u);
    values[0] = static_cast<jint>(header.formatVersion);
    values[1] = static_cast<jint>(header.width);
    values[2] = static_cast<jint>(header.height);
    values[3] = static_cast<jint>(layers.size());
    values[4] = static_cast<jint>(header.overviewBytes);
    for (size_t i = 0; i < layers.size(); ++i) {
        const size_t offset = 5u + i * 5u;
        values[offset] = static_cast<jint>(layers[i].sampleSize);
        values[offset + 1u] = static_cast<jint>(layers[i].width);
        values[offset + 2u] = static_cast<jint>(layers[i].height);
        values[offset + 3u] = static_cast<jint>(layers[i].bytes);
        values[offset + 4u] = static_cast<jint>(layers[i].tileSize);
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetIntArrayRegion(
            result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_readPyramidLayer(
    JNIEnv* env,
    jobject,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis,
    jint sampleSize
) {
    UtfChars indexPath(env, indexPathValue);
    if (indexPath.get() == nullptr || sampleSize <= 0) return nullptr;
    Header header{};
    std::vector<PyramidLayer> layers;
    std::vector<uint8_t> encoded;
    if (!readPyramid(
            indexPath.get(),
            static_cast<int64_t>(sourceBytes),
            static_cast<int64_t>(sourceModifiedMillis),
            static_cast<uint32_t>(sampleSize),
            &header, &layers, &encoded)) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(encoded.size()));
    if (result != nullptr && !encoded.empty()) {
        env->SetByteArrayRegion(
            result, 0, static_cast<jsize>(encoded.size()),
            reinterpret_cast<const jbyte*>(encoded.data()));
    }
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_openPyramidTiles(
    JNIEnv* env,
    jobject,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    UtfChars indexPath(env, indexPathValue);
    if (indexPath.get() == nullptr) return 0;
    std::unique_ptr<FILE, decltype(&fclose)> file(
        fopen(indexPath.get(), "rb"), fclose);
    Header header{};
    auto handle = std::make_unique<PyramidHandle>();
    long dataOffset = 0;
    if (!file || !readHeader(file.get(), &header) ||
        header.formatVersion != kFormatVersion ||
        header.sourceBytes != static_cast<int64_t>(sourceBytes) ||
        header.sourceModifiedMillis != static_cast<int64_t>(sourceModifiedMillis) ||
        !readPyramidDirectory(
            file.get(), &header, &handle->layers, &dataOffset)) {
        return 0;
    }
    handle->file = file.release();
    handle->sourceWidth = header.width;
    handle->sourceHeight = header.height;
    return reinterpret_cast<jlong>(handle.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_decodePyramidTile(
    JNIEnv* env,
    jobject,
    jlong handleValue,
    jint sampleSize,
    jint tileX,
    jint tileY,
    jobject bitmap
) {
    auto* handle = reinterpret_cast<PyramidHandle*>(handleValue);
    const PyramidTile* tile = findPyramidTile(handle, sampleSize, tileX, tileY);
    if (tile == nullptr || bitmap == nullptr || handle->file == nullptr) return JNI_FALSE;

    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        bitmapInfo.width != tile->width || bitmapInfo.height != tile->height) {
        return JNI_FALSE;
    }

    const int descriptor = fileno(handle->file);
    const long pageSizeValue = sysconf(_SC_PAGESIZE);
    if (descriptor < 0 || pageSizeValue <= 0) return JNI_FALSE;
    const uint64_t pageSize = static_cast<uint64_t>(pageSizeValue);
    const uint64_t mapOffset = (tile->dataOffset / pageSize) * pageSize;
    const uint64_t delta = tile->dataOffset - mapOffset;
    const uint64_t mapBytes64 = delta + tile->bytes;
    if (mapOffset > static_cast<uint64_t>(INT64_MAX) || mapBytes64 == 0 ||
        mapBytes64 > static_cast<uint64_t>(SIZE_MAX)) {
        return JNI_FALSE;
    }
    const size_t mapBytes = static_cast<size_t>(mapBytes64);
    void* mapping = mmap(
        nullptr,
        mapBytes,
        PROT_READ,
        MAP_PRIVATE,
        descriptor,
        static_cast<off_t>(mapOffset));
    if (mapping == MAP_FAILED) return JNI_FALSE;
    auto* encoded = static_cast<unsigned char*>(mapping) + delta;

    jpeg_decompress_struct info{};
    JpegError error{};
    info.err = jpeg_std_error(&error.base);
    error.base.error_exit = errorExit;
    bool created = false;
    void* pixels = nullptr;
    if (setjmp(error.jump)) {
        if (pixels != nullptr) AndroidBitmap_unlockPixels(env, bitmap);
        if (created) jpeg_destroy_decompress(&info);
        munmap(mapping, mapBytes);
        __android_log_print(
            ANDROID_LOG_ERROR,
            "IndexedJpeg",
            "pyramid tile decode error: %s",
            error.message);
        return JNI_FALSE;
    }

    jpeg_create_decompress(&info);
    created = true;
    jpeg_mem_src(&info, encoded, tile->bytes);
    if (jpeg_read_header(&info, TRUE) != JPEG_HEADER_OK || info.arith_code ||
        info.image_width != tile->width || info.image_height != tile->height) {
        jpeg_destroy_decompress(&info);
        munmap(mapping, mapBytes);
        return JNI_FALSE;
    }
    info.out_color_space = JCS_EXT_RGBA;
    if (!jpeg_start_decompress(&info) || info.output_components != 4 ||
        info.output_width != bitmapInfo.width || info.output_height != bitmapInfo.height ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        jpeg_destroy_decompress(&info);
        munmap(mapping, mapBytes);
        return JNI_FALSE;
    }

    while (info.output_scanline < info.output_height) {
        auto* destination = static_cast<uint8_t*>(pixels) +
                            static_cast<size_t>(info.output_scanline) * bitmapInfo.stride;
        JSAMPROW row = destination;
        if (jpeg_read_scanlines(&info, &row, 1) != 1) break;
    }
    const bool complete = info.output_scanline == info.output_height;
    if (complete) jpeg_finish_decompress(&info);
    AndroidBitmap_unlockPixels(env, bitmap);
    pixels = nullptr;
    jpeg_destroy_decompress(&info);
    munmap(mapping, mapBytes);
    return complete ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_closePyramidTiles(
    JNIEnv*, jobject, jlong handleValue
) {
    delete reinterpret_cast<PyramidHandle*>(handleValue);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_open(
    JNIEnv* env,
    jobject,
    jstring sourcePathValue,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    UtfChars sourcePath(env, sourcePathValue);
    UtfChars indexPath(env, indexPathValue);
    if (sourcePath.get() == nullptr || indexPath.get() == nullptr) return 0;
    auto handle = std::make_unique<IndexHandle>();
    Header header{};
    if (!readIndex(
            indexPath.get(), static_cast<int64_t>(sourceBytes),
            static_cast<int64_t>(sourceModifiedMillis), &header, &handle->index, false)) {
        return 0;
    }
    handle->sourcePath = sourcePath.get();
    handle->width = header.width;
    handle->height = header.height;
    handle->sourceBytes = header.sourceBytes;
    handle->sourceModifiedMillis = header.sourceModifiedMillis;
    return reinterpret_cast<jlong>(handle.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_decode(
    JNIEnv* env,
    jobject,
    jlong handleValue,
    jint left,
    jint top,
    jint right,
    jint bottom,
    jint sampleSize,
    jobject bitmap
) {
    auto* handle = reinterpret_cast<IndexHandle*>(handleValue);
    if (handle == nullptr || bitmap == nullptr || !isPowerOfTwo(sampleSize) ||
        left < 0 || top < 0 || right <= left || bottom <= top ||
        static_cast<uint32_t>(right) > handle->width ||
        static_cast<uint32_t>(bottom) > handle->height) {
        logDecodeFailure("arguments");
        return JNI_FALSE;
    }

    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        logDecodeFailure("bitmap-info");
        return JNI_FALSE;
    }
    const uint32_t expectedWidth =
        static_cast<uint32_t>((right - left + sampleSize - 1) / sampleSize);
    const uint32_t expectedHeight =
        static_cast<uint32_t>((bottom - top + sampleSize - 1) / sampleSize);
    if (bitmapInfo.width != expectedWidth || bitmapInfo.height != expectedHeight) {
        logDecodeFailure("bitmap-size");
        return JNI_FALSE;
    }

    std::unique_ptr<FILE, decltype(&fclose)> input(fopen(handle->sourcePath.c_str(), "rb"), fclose);
    if (!input) {
        logDecodeFailure("source-open");
        return JNI_FALSE;
    }
    jpeg_decompress_struct info{};
    JpegError error{};
    info.err = jpeg_std_error(&error.base);
    error.base.error_exit = errorExit;
    bool created = false;
    void* pixels = nullptr;

    if (setjmp(error.jump)) {
        if (pixels != nullptr) AndroidBitmap_unlockPixels(env, bitmap);
        if (created) jpeg_destroy_decompress(&info);
        __android_log_print(ANDROID_LOG_ERROR, "IndexedJpeg", "jpeg error: %s", error.message);
        return JNI_FALSE;
    }

    jpeg_create_decompress(&info);
    created = true;
    jpeg_stdio_src(&info, input.get());
    if (jpeg_read_header(&info, TRUE) != JPEG_HEADER_OK ||
        info.image_width != handle->width || info.image_height != handle->height ||
        info.arith_code) {
        jpeg_destroy_decompress(&info);
        logDecodeFailure("source-header");
        return JNI_FALSE;
    }

    const int nativeSample = std::min<int>(sampleSize, 8);
    const int postSample = sampleSize / nativeSample;
    info.scale_num = 1;
    info.scale_denom = nativeSample;
    info.out_color_space = JCS_EXT_RGBA;
    // The indexed progressive controller intentionally retains one iMCU row,
    // while libjpeg's optional block smoothing requests neighboring rows.
    // Fancy chroma upsampling also needs adjacent rows and breaks the tile
    // controller's one-row seek/restore pipeline. Android's original indexed
    // tile caller disables both optimizations for this reason.
    info.do_fancy_upsampling = FALSE;
    info.do_block_smoothing = FALSE;
    if (!jpeg_start_tile_decompress(&info)) {
        jpeg_destroy_decompress(&info);
        logDecodeFailure("tile-start");
        return JNI_FALSE;
    }

    int alignedLeft = left;
    int alignedTop = top;
    int alignedWidth = right - left;
    int alignedHeight = bottom - top;
    jpeg_init_read_tile_scanline(
        &info, &handle->index, &alignedLeft, &alignedTop, &alignedWidth, &alignedHeight);
    if (alignedWidth <= 0 || alignedHeight <= 0 || info.output_components != 4) {
        jpeg_destroy_decompress(&info);
        __android_log_print(
            ANDROID_LOG_ERROR, "IndexedJpeg",
            "bad tile dimensions width=%d height=%d components=%d",
            alignedWidth, alignedHeight, info.output_components);
        return JNI_FALSE;
    }

    const int firstX = (left - alignedLeft) / nativeSample;
    const int firstY = (top - alignedTop) / nativeSample;
    if (firstX < 0 || firstY < 0) {
        jpeg_destroy_decompress(&info);
        logDecodeFailure("crop-origin");
        return JNI_FALSE;
    }

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        jpeg_destroy_decompress(&info);
        logDecodeFailure("bitmap-lock");
        return JNI_FALSE;
    }

    std::vector<JSAMPLE> row(static_cast<size_t>(alignedWidth) * 4u);
    JSAMPROW rowPointer = row.data();
    uint32_t destinationY = 0;
    for (int sourceY = 0; sourceY < alignedHeight && destinationY < bitmapInfo.height; ++sourceY) {
        if (jpeg_read_tile_scanline(&info, &handle->index, &rowPointer) != 1) break;
        if (sourceY < firstY || (sourceY - firstY) % postSample != 0) continue;
        auto* destination = reinterpret_cast<uint8_t*>(pixels) +
                            static_cast<size_t>(destinationY) * bitmapInfo.stride;
        for (uint32_t x = 0; x < bitmapInfo.width; ++x) {
            const int sourceX = std::min(firstX + static_cast<int>(x) * postSample, alignedWidth - 1);
            std::memcpy(destination + x * 4u, row.data() + sourceX * 4u, 4u);
        }
        ++destinationY;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    pixels = nullptr;
    jpeg_destroy_decompress(&info);
    if (destinationY != bitmapInfo.height) {
        __android_log_print(
            ANDROID_LOG_ERROR, "IndexedJpeg",
            "short tile output rows=%u expected=%u alignedHeight=%d firstY=%d post=%d",
            destinationY, bitmapInfo.height, alignedHeight, firstY, postSample);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_close(
    JNIEnv*, jobject, jlong handleValue
) {
    auto* handle = reinterpret_cast<IndexHandle*>(handleValue);
    if (handle == nullptr) return;
    freeIndex(&handle->index);
    delete handle;
}
