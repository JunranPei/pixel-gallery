#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include <sys/stat.h>
#include <unistd.h>

namespace {

constexpr uint32_t kBiRgb = 0;
constexpr uint64_t kMinimumBmpBytes = 54;
constexpr uint64_t kMaxRowReadBytes = 64ULL * 1024ULL * 1024ULL;

class JString final {
public:
    JString(JNIEnv* env, jstring value) : env_(env), value_(value) {
        chars_ = value == nullptr ? nullptr : env_->GetStringUTFChars(value, nullptr);
    }
    ~JString() {
        if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
    }
    const char* c_str() const { return chars_; }
    bool valid() const { return chars_ != nullptr; }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

void throwIOException(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

void logError(const char* stage, const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, "IndexedBmp", "%s: %s", stage, message.c_str());
}

uint16_t getU16(const uint8_t* bytes, size_t offset) {
    return static_cast<uint16_t>(bytes[offset]) |
           static_cast<uint16_t>(static_cast<uint16_t>(bytes[offset + 1]) << 8);
}

uint32_t getU32(const uint8_t* bytes, size_t offset) {
    return static_cast<uint32_t>(bytes[offset]) |
           (static_cast<uint32_t>(bytes[offset + 1]) << 8) |
           (static_cast<uint32_t>(bytes[offset + 2]) << 16) |
           (static_cast<uint32_t>(bytes[offset + 3]) << 24);
}

bool preadAll(int fd, void* destination, size_t bytes, uint64_t offset) {
    auto* out = static_cast<uint8_t*>(destination);
    size_t completed = 0;
    while (completed < bytes) {
        const ssize_t count = pread(
            fd,
            out + completed,
            bytes - completed,
            static_cast<off_t>(offset + completed)
        );
        if (count == 0) return false;
        if (count < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        completed += static_cast<size_t>(count);
    }
    return true;
}

struct BmpInfo {
    uint32_t width = 0;
    uint32_t height = 0;
    uint64_t pixelOffset = 0;
    uint64_t rowStride = 0;
    uint16_t bitsPerPixel = 0;
    bool topDown = false;
};

BmpInfo parseBmp(int fd) {
    struct stat fileStat{};
    if (fstat(fd, &fileStat) != 0 || fileStat.st_size < static_cast<off_t>(kMinimumBmpBytes)) {
        throw std::runtime_error("BMP file is too small");
    }
    std::array<uint8_t, kMinimumBmpBytes> header{};
    if (!preadAll(fd, header.data(), header.size(), 0)) {
        throw std::runtime_error("Unable to read BMP headers");
    }
    if (header[0] != 'B' || header[1] != 'M') throw std::runtime_error("Invalid BMP signature");
    const uint64_t pixelOffset = getU32(header.data(), 10);
    const uint32_t dibBytes = getU32(header.data(), 14);
    if (dibBytes < 40) throw std::runtime_error("Unsupported pre-BITMAPINFOHEADER BMP");
    if (pixelOffset < 14ULL + dibBytes) throw std::runtime_error("BMP pixel array overlaps its DIB header");

    const int32_t signedWidth = static_cast<int32_t>(getU32(header.data(), 18));
    const int32_t signedHeight = static_cast<int32_t>(getU32(header.data(), 22));
    const uint16_t planes = getU16(header.data(), 26);
    const uint16_t bitsPerPixel = getU16(header.data(), 28);
    const uint32_t compression = getU32(header.data(), 30);
    if (signedWidth <= 0 || signedHeight == 0 || signedHeight == std::numeric_limits<int32_t>::min()) {
        throw std::runtime_error("Invalid BMP dimensions");
    }
    if (planes != 1) throw std::runtime_error("Unsupported BMP plane count");
    if (bitsPerPixel != 24 && bitsPerPixel != 32) {
        throw std::runtime_error("Only 24-bit and 32-bit BMP are supported");
    }
    if (compression != kBiRgb) throw std::runtime_error("Only uncompressed BI_RGB BMP is supported");

    const uint32_t width = static_cast<uint32_t>(signedWidth);
    const uint32_t height = static_cast<uint32_t>(signedHeight < 0 ? -signedHeight : signedHeight);
    const uint64_t rowBits = static_cast<uint64_t>(width) * bitsPerPixel;
    const uint64_t rowStride = ((rowBits + 31ULL) / 32ULL) * 4ULL;
    if (rowStride == 0 || rowStride > static_cast<uint64_t>(std::numeric_limits<off_t>::max())) {
        throw std::runtime_error("BMP row stride overflows this ABI");
    }
    if (height > (std::numeric_limits<uint64_t>::max() - pixelOffset) / rowStride) {
        throw std::runtime_error("BMP pixel array size overflows");
    }
    const uint64_t requiredBytes = pixelOffset + rowStride * height;
    if (requiredBytes > static_cast<uint64_t>(fileStat.st_size)) {
        throw std::runtime_error("BMP pixel array is truncated");
    }
    return BmpInfo{
        width,
        height,
        pixelOffset,
        rowStride,
        bitsPerPixel,
        signedHeight < 0,
    };
}

uint32_t ceilDiv(uint32_t value, uint32_t divisor) {
    return value / divisor + (value % divisor == 0 ? 0 : 1);
}

struct Decoder {
    int fd = -1;
    BmpInfo info;
    std::mutex mutex;
    ~Decoder() {
        if (fd >= 0) ::close(fd);
    }
};

bool infoMatches(
    const BmpInfo& info,
    jint width,
    jint height,
    jlong pixelOffset,
    jlong rowStride,
    jint bitsPerPixel,
    jboolean topDown
) {
    return width > 0 && height > 0 && pixelOffset >= 0 && rowStride > 0 &&
           info.width == static_cast<uint32_t>(width) &&
           info.height == static_cast<uint32_t>(height) &&
           info.pixelOffset == static_cast<uint64_t>(pixelOffset) &&
           info.rowStride == static_cast<uint64_t>(rowStride) &&
           info.bitsPerPixel == static_cast<uint16_t>(bitsPerPixel) &&
           info.topDown == (topDown == JNI_TRUE);
}

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_indexedbmp_IndexedBmpNative_probe(JNIEnv* env, jobject, jstring pathValue) {
    JString path(env, pathValue);
    if (!path.valid()) return nullptr;
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        throwIOException(env, "Unable to open BMP source");
        return nullptr;
    }
    try {
        const BmpInfo info = parseBmp(fd);
        ::close(fd);
        const std::array<jlong, 6> values = {
            static_cast<jlong>(info.width),
            static_cast<jlong>(info.height),
            static_cast<jlong>(info.pixelOffset),
            static_cast<jlong>(info.rowStride),
            static_cast<jlong>(info.bitsPerPixel),
            static_cast<jlong>(info.topDown ? 1 : 0),
        };
        jlongArray result = env->NewLongArray(values.size());
        if (result != nullptr) env->SetLongArrayRegion(result, 0, values.size(), values.data());
        return result;
    } catch (const std::exception& error) {
        ::close(fd);
        logError("probe", error.what());
        throwIOException(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_indexedbmp_IndexedBmpNative_open(
    JNIEnv* env,
    jobject,
    jstring pathValue,
    jint width,
    jint height,
    jlong pixelOffset,
    jlong rowStride,
    jint bitsPerPixel,
    jboolean topDown
) {
    JString path(env, pathValue);
    if (!path.valid()) return 0;
    std::unique_ptr<Decoder> decoder(new Decoder());
    decoder->fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (decoder->fd < 0) return 0;
    try {
        decoder->info = parseBmp(decoder->fd);
        if (!infoMatches(decoder->info, width, height, pixelOffset, rowStride, bitsPerPixel, topDown)) {
            return 0;
        }
        return reinterpret_cast<jlong>(decoder.release());
    } catch (const std::exception& error) {
        logError("open", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_indexedbmp_IndexedBmpNative_decode(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint left,
    jint top,
    jint right,
    jint bottom,
    jint sampleSize,
    jobject bitmap
) {
    auto* decoder = reinterpret_cast<Decoder*>(handle);
    if (decoder == nullptr || bitmap == nullptr || sampleSize <= 0 ||
        left < 0 || top < 0 || right <= left || bottom <= top ||
        static_cast<uint32_t>(right) > decoder->info.width ||
        static_cast<uint32_t>(bottom) > decoder->info.height) {
        return JNI_FALSE;
    }
    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    const uint32_t outputWidth = ceilDiv(static_cast<uint32_t>(right - left), sampleSize);
    const uint32_t outputHeight = ceilDiv(static_cast<uint32_t>(bottom - top), sampleSize);
    if (bitmapInfo.width != outputWidth || bitmapInfo.height != outputHeight) return JNI_FALSE;

    const uint32_t pixelBytes = decoder->info.bitsPerPixel / 8;
    const uint32_t lastSourceX = static_cast<uint32_t>(left) + (outputWidth - 1) * sampleSize;
    const uint64_t spanBytes64 =
        (static_cast<uint64_t>(lastSourceX) - static_cast<uint32_t>(left) + 1ULL) * pixelBytes;
    if (spanBytes64 == 0 || spanBytes64 > kMaxRowReadBytes ||
        spanBytes64 > std::numeric_limits<size_t>::max()) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> guard(decoder->mutex);
    void* pixelsValue = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixelsValue) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    bool success = true;
    try {
        std::vector<uint8_t> sourceRow(static_cast<size_t>(spanBytes64));
        auto* output = static_cast<uint8_t*>(pixelsValue);
        for (uint32_t y = 0; y < outputHeight; ++y) {
            const uint32_t sourceY = static_cast<uint32_t>(top) + y * sampleSize;
            const uint32_t fileY = decoder->info.topDown
                ? sourceY
                : decoder->info.height - 1U - sourceY;
            const uint64_t offset = decoder->info.pixelOffset +
                static_cast<uint64_t>(fileY) * decoder->info.rowStride +
                static_cast<uint64_t>(left) * pixelBytes;
            if (!preadAll(decoder->fd, sourceRow.data(), sourceRow.size(), offset)) {
                throw std::runtime_error("Unable to read BMP scan line");
            }
            uint8_t* outputRow = output + static_cast<size_t>(y) * bitmapInfo.stride;
            for (uint32_t x = 0; x < outputWidth; ++x) {
                const uint32_t sourceX = x * sampleSize;
                const uint8_t* sourcePixel = sourceRow.data() + static_cast<size_t>(sourceX) * pixelBytes;
                uint8_t* destination = outputRow + static_cast<size_t>(x) * 4;
                destination[0] = sourcePixel[2];
                destination[1] = sourcePixel[1];
                destination[2] = sourcePixel[0];
                destination[3] = 255;
            }
        }
    } catch (const std::exception& error) {
        logError("decode", error.what());
        success = false;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_indexedbmp_IndexedBmpNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Decoder*>(handle);
}
