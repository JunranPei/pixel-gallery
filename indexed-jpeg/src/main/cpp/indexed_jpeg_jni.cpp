#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <csetjmp>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

extern "C" {
#include "jpeglib.h"
}

namespace {

constexpr uint8_t kMagic[8] = {'I', 'J', 'X', 'M', 'C', 'U', '0', '1'};
constexpr uint32_t kFormatVersion = 1;
constexpr uint32_t kEndMarker = 0x31444E45;  // END1
constexpr uint32_t kMaxScans = 1024;
constexpr uint32_t kMaxRows = 1u << 20;
constexpr uint32_t kMaxRecordsPerRow = 1u << 20;

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
    int64_t sourceBytes = 0;
    int64_t sourceModifiedMillis = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t scanCount = 0;
    uint32_t totalRows = 0;
    uint32_t sampleSize = 0;
};

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
    return writeU32(file, value.bitstream_offset) &&
           writeU16(file, static_cast<uint16_t>(value.prev_dc[0])) &&
           writeU16(file, static_cast<uint16_t>(value.prev_dc[1])) &&
           writeU16(file, static_cast<uint16_t>(value.prev_dc[2])) &&
           writeU16(file, value.EOBRUN) &&
           writeU32(file, static_cast<uint32_t>(value.get_buffer)) &&
           writeU16(file, value.restarts_to_go) &&
           writeU8(file, value.next_restart_num);
}

bool readOffset(FILE* file, huffman_offset_data* value) {
    uint16_t dc0, dc1, dc2;
    uint32_t buffer;
    return readU32(file, &value->bitstream_offset) &&
           readU16(file, &dc0) && (value->prev_dc[0] = static_cast<int16_t>(dc0), true) &&
           readU16(file, &dc1) && (value->prev_dc[1] = static_cast<int16_t>(dc1), true) &&
           readU16(file, &dc2) && (value->prev_dc[2] = static_cast<int16_t>(dc2), true) &&
           readU16(file, &value->EOBRUN) &&
           readU32(file, &buffer) && (value->get_buffer = static_cast<INT32>(buffer), true) &&
           readU16(file, &value->restarts_to_go) &&
           readU8(file, &value->next_restart_num);
}

void freeIndex(huffman_index* index) {
    if (index == nullptr || index->scan == nullptr) return;
    for (int scan = 0; scan < index->scan_count; ++scan) {
        if (index->scan[scan].offset != nullptr) {
            for (int row = 0; row < index->total_iMCU_rows; ++row) {
                free(index->scan[scan].offset[row]);
            }
            free(index->scan[scan].offset);
        }
    }
    free(index->scan);
    std::memset(index, 0, sizeof(*index));
}

bool writeIndex(
    const char* path,
    const huffman_index& index,
    uint32_t width,
    uint32_t height,
    int64_t sourceBytes,
    int64_t sourceModifiedMillis
) {
    std::unique_ptr<FILE, decltype(&fclose)> file(fopen(path, "wb"), fclose);
    if (!file) return false;
    if (!writeBytes(file.get(), kMagic, sizeof(kMagic)) ||
        !writeU32(file.get(), kFormatVersion) ||
        !writeU64(file.get(), static_cast<uint64_t>(sourceBytes)) ||
        !writeU64(file.get(), static_cast<uint64_t>(sourceModifiedMillis)) ||
        !writeU32(file.get(), width) || !writeU32(file.get(), height) ||
        !writeU32(file.get(), static_cast<uint32_t>(index.scan_count)) ||
        !writeU32(file.get(), static_cast<uint32_t>(index.total_iMCU_rows)) ||
        !writeU32(file.get(), static_cast<uint32_t>(index.MCU_sample_size))) {
        return false;
    }
    for (int scanNo = 0; scanNo < index.scan_count; ++scanNo) {
        const auto& scan = index.scan[scanNo];
        const uint64_t records64 = static_cast<uint64_t>(scan.MCU_rows_per_iMCU_row) *
                                   static_cast<uint64_t>(scan.MCUs_per_row);
        if (records64 == 0 || records64 > kMaxRecordsPerRow) return false;
        const uint32_t records = static_cast<uint32_t>(records64);
        if (!writeU32(file.get(), scan.bitstream_offset) ||
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
    return writeU32(file.get(), kEndMarker) && fflush(file.get()) == 0;
}

bool readHeader(FILE* file, Header* header) {
    uint8_t magic[sizeof(kMagic)];
    uint32_t version;
    uint64_t sourceBytes, sourceModified;
    if (!readBytes(file, magic, sizeof(magic)) ||
        std::memcmp(magic, kMagic, sizeof(kMagic)) != 0 ||
        !readU32(file, &version) || version != kFormatVersion ||
        !readU64(file, &sourceBytes) || !readU64(file, &sourceModified) ||
        !readU32(file, &header->width) || !readU32(file, &header->height) ||
        !readU32(file, &header->scanCount) || !readU32(file, &header->totalRows) ||
        !readU32(file, &header->sampleSize)) {
        return false;
    }
    header->sourceBytes = static_cast<int64_t>(sourceBytes);
    header->sourceModifiedMillis = static_cast<int64_t>(sourceModified);
    return header->width > 0 && header->height > 0 &&
           header->scanCount > 0 && header->scanCount <= kMaxScans &&
           header->totalRows > 0 && header->totalRows <= kMaxRows &&
           header->sampleSize > 0;
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
        if (!readU32(file.get(), &scan.bitstream_offset) ||
            !readU32(file.get(), &comps) || !readU32(file.get(), &mcus) ||
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
    if (!readU32(file.get(), &marker) || marker != kEndMarker || fgetc(file.get()) != EOF) {
        freeIndex(index);
        return false;
    }
    return true;
}

bool isPowerOfTwo(int value) {
    return value > 0 && (value & (value - 1)) == 0;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_indexedjpeg_IndexedJpegNative_buildIndex(
    JNIEnv* env,
    jobject,
    jstring sourcePathValue,
    jstring destinationPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
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
    const bool written = writeIndex(
        destinationPath.get(), index, width, height,
        static_cast<int64_t>(sourceBytes),
        static_cast<int64_t>(sourceModifiedMillis));
    freeIndex(&index);
    jpeg_destroy_decompress(&info);
    if (!written) {
        remove(destinationPath.get());
        throwIOException(env, "Unable to write the completed JPEG index");
        return nullptr;
    }

    jint values[3] = {
        static_cast<jint>(width), static_cast<jint>(height), static_cast<jint>(scanCount)};
    jintArray result = env->NewIntArray(3);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, values);
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
    huffman_index unused{};
    return readIndex(
        indexPath.get(), static_cast<int64_t>(sourceBytes),
        static_cast<int64_t>(sourceModifiedMillis), &header, &unused, true)
        ? JNI_TRUE : JNI_FALSE;
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
        return JNI_FALSE;
    }

    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    const uint32_t expectedWidth =
        static_cast<uint32_t>((right - left + sampleSize - 1) / sampleSize);
    const uint32_t expectedHeight =
        static_cast<uint32_t>((bottom - top + sampleSize - 1) / sampleSize);
    if (bitmapInfo.width != expectedWidth || bitmapInfo.height != expectedHeight) return JNI_FALSE;

    std::unique_ptr<FILE, decltype(&fclose)> input(fopen(handle->sourcePath.c_str(), "rb"), fclose);
    if (!input) return JNI_FALSE;
    jpeg_decompress_struct info{};
    JpegError error{};
    info.err = jpeg_std_error(&error.base);
    error.base.error_exit = errorExit;
    bool created = false;
    void* pixels = nullptr;

    if (setjmp(error.jump)) {
        if (pixels != nullptr) AndroidBitmap_unlockPixels(env, bitmap);
        if (created) jpeg_destroy_decompress(&info);
        return JNI_FALSE;
    }

    jpeg_create_decompress(&info);
    created = true;
    jpeg_stdio_src(&info, input.get());
    if (jpeg_read_header(&info, TRUE) != JPEG_HEADER_OK ||
        info.image_width != handle->width || info.image_height != handle->height ||
        info.arith_code) {
        jpeg_destroy_decompress(&info);
        return JNI_FALSE;
    }

    const int nativeSample = std::min<int>(sampleSize, 8);
    const int postSample = sampleSize / nativeSample;
    info.scale_num = 1;
    info.scale_denom = nativeSample;
    info.out_color_space = JCS_EXT_RGBA;
    if (!jpeg_start_tile_decompress(&info)) {
        jpeg_destroy_decompress(&info);
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
        return JNI_FALSE;
    }

    const int firstX = (left - alignedLeft) / nativeSample;
    const int firstY = (top - alignedTop) / nativeSample;
    if (firstX < 0 || firstY < 0) {
        jpeg_destroy_decompress(&info);
        return JNI_FALSE;
    }

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        jpeg_destroy_decompress(&info);
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
    return destinationY == bitmapInfo.height ? JNI_TRUE : JNI_FALSE;
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
