#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

struct tiff;
using TIFF = tiff;
using tmsize_t = std::ptrdiff_t;
using toff_t = uint64_t;
using ttile_t = uint32_t;
using tstrip_t = uint32_t;

constexpr uint32_t TIFFTAG_SUBFILETYPE = 254;
constexpr uint32_t TIFFTAG_IMAGEWIDTH = 256;
constexpr uint32_t TIFFTAG_IMAGELENGTH = 257;
constexpr uint32_t TIFFTAG_BITSPERSAMPLE = 258;
constexpr uint32_t TIFFTAG_COMPRESSION = 259;
constexpr uint32_t TIFFTAG_PHOTOMETRIC = 262;
constexpr uint32_t TIFFTAG_ORIENTATION = 274;
constexpr uint32_t TIFFTAG_SAMPLESPERPIXEL = 277;
constexpr uint32_t TIFFTAG_ROWSPERSTRIP = 278;
constexpr uint32_t TIFFTAG_PLANARCONFIG = 284;
constexpr uint32_t TIFFTAG_TILEWIDTH = 322;
constexpr uint32_t TIFFTAG_TILELENGTH = 323;
constexpr uint32_t TIFFTAG_SUBIFD = 330;
constexpr uint32_t TIFFTAG_EXTRASAMPLES = 338;
constexpr uint32_t TIFFTAG_SAMPLEFORMAT = 339;
constexpr uint32_t TIFFTAG_JPEGCOLORMODE = 65538;

constexpr uint16_t PHOTOMETRIC_MINISWHITE = 0;
constexpr uint16_t PHOTOMETRIC_MINISBLACK = 1;
constexpr uint16_t PHOTOMETRIC_RGB = 2;
constexpr uint16_t ORIENTATION_TOPLEFT = 1;
constexpr uint16_t PLANARCONFIG_CONTIG = 1;
constexpr uint16_t SAMPLEFORMAT_UINT = 1;
constexpr uint16_t EXTRASAMPLE_ASSOCALPHA = 1;
constexpr uint16_t EXTRASAMPLE_UNASSALPHA = 2;
constexpr uint16_t COMPRESSION_JPEG = 7;
constexpr uint32_t FILETYPE_REDUCEDIMAGE = 1;
constexpr uint32_t MODE_TILES = 1;
constexpr uint32_t MODE_STRIPS = 2;
constexpr tmsize_t READ_WHOLE_BLOCK = static_cast<tmsize_t>(-1);
constexpr uint64_t MAX_DECODED_BLOCK_BYTES = 32ULL * 1024ULL * 1024ULL;
constexpr size_t MAX_LEVELS = 64;

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

void logError(const char* stage, const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, "IndexedTiff", "%s: %s", stage, message.c_str());
}

void throwIOException(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

template <typename T>
T requiredSymbol(void* library, const char* name) {
    void* symbol = dlsym(library, name);
    if (symbol == nullptr) throw std::runtime_error(std::string("libtiff lacks ") + name);
    return reinterpret_cast<T>(symbol);
}

struct TiffApi {
    using OpenFn = TIFF* (*)(const char*, const char*);
    using CloseFn = void (*)(TIFF*);
    using GetFieldFn = int (*)(TIFF*, uint32_t, ...);
    using SetFieldFn = int (*)(TIFF*, uint32_t, ...);
    using IsTiledFn = int (*)(TIFF*);
    using TileSizeFn = tmsize_t (*)(TIFF*);
    using StripSizeFn = tmsize_t (*)(TIFF*);
    using NumberOfTilesFn = ttile_t (*)(TIFF*);
    using NumberOfStripsFn = tstrip_t (*)(TIFF*);
    using ComputeTileFn = ttile_t (*)(TIFF*, uint32_t, uint32_t, uint32_t, uint16_t);
    using ComputeStripFn = tstrip_t (*)(TIFF*, uint32_t, uint16_t);
    using ReadEncodedTileFn = tmsize_t (*)(TIFF*, ttile_t, void*, tmsize_t);
    using ReadEncodedStripFn = tmsize_t (*)(TIFF*, tstrip_t, void*, tmsize_t);
    using CurrentDirOffsetFn = toff_t (*)(TIFF*);
    using SetSubDirectoryFn = int (*)(TIFF*, toff_t);
    using ReadDirectoryFn = int (*)(TIFF*);

    void* library = nullptr;
    OpenFn open = nullptr;
    CloseFn close = nullptr;
    GetFieldFn getField = nullptr;
    GetFieldFn getFieldDefaulted = nullptr;
    SetFieldFn setField = nullptr;
    IsTiledFn isTiled = nullptr;
    TileSizeFn tileSize = nullptr;
    StripSizeFn stripSize = nullptr;
    NumberOfTilesFn numberOfTiles = nullptr;
    NumberOfStripsFn numberOfStrips = nullptr;
    ComputeTileFn computeTile = nullptr;
    ComputeStripFn computeStrip = nullptr;
    ReadEncodedTileFn readEncodedTile = nullptr;
    ReadEncodedStripFn readEncodedStrip = nullptr;
    CurrentDirOffsetFn currentDirOffset = nullptr;
    SetSubDirectoryFn setSubDirectory = nullptr;
    ReadDirectoryFn readDirectory = nullptr;

    TiffApi() {
        library = dlopen("libtiff.so", RTLD_NOW | RTLD_LOCAL);
        if (library == nullptr) {
            throw std::runtime_error(std::string("Unable to load libtiff: ") + dlerror());
        }
        open = requiredSymbol<OpenFn>(library, "TIFFOpen");
        close = requiredSymbol<CloseFn>(library, "TIFFClose");
        getField = requiredSymbol<GetFieldFn>(library, "TIFFGetField");
        getFieldDefaulted = requiredSymbol<GetFieldFn>(library, "TIFFGetFieldDefaulted");
        setField = requiredSymbol<SetFieldFn>(library, "TIFFSetField");
        isTiled = requiredSymbol<IsTiledFn>(library, "TIFFIsTiled");
        tileSize = requiredSymbol<TileSizeFn>(library, "TIFFTileSize");
        stripSize = requiredSymbol<StripSizeFn>(library, "TIFFStripSize");
        numberOfTiles = requiredSymbol<NumberOfTilesFn>(library, "TIFFNumberOfTiles");
        numberOfStrips = requiredSymbol<NumberOfStripsFn>(library, "TIFFNumberOfStrips");
        computeTile = requiredSymbol<ComputeTileFn>(library, "TIFFComputeTile");
        computeStrip = requiredSymbol<ComputeStripFn>(library, "TIFFComputeStrip");
        readEncodedTile = requiredSymbol<ReadEncodedTileFn>(library, "TIFFReadEncodedTile");
        readEncodedStrip = requiredSymbol<ReadEncodedStripFn>(library, "TIFFReadEncodedStrip");
        currentDirOffset = requiredSymbol<CurrentDirOffsetFn>(library, "TIFFCurrentDirOffset");
        setSubDirectory = requiredSymbol<SetSubDirectoryFn>(library, "TIFFSetSubDirectory");
        readDirectory = requiredSymbol<ReadDirectoryFn>(library, "TIFFReadDirectory");
    }

    ~TiffApi() {
        if (library != nullptr) dlclose(library);
    }
};

TiffApi& api() {
    static TiffApi instance;
    return instance;
}

struct TiffCloser {
    void operator()(TIFF* value) const {
        if (value != nullptr) api().close(value);
    }
};
using UniqueTiff = std::unique_ptr<TIFF, TiffCloser>;

struct Level {
    toff_t directoryOffset = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t sample = 1;
    uint32_t mode = 0;
    uint32_t blockWidth = 0;
    uint32_t blockHeight = 0;
    tmsize_t blockBytes = 0;
    uint16_t samplesPerPixel = 0;
    uint16_t photometric = 0;
    uint16_t alphaMode = 0;
    uint16_t compression = 0;
};

struct Decoder {
    UniqueTiff tiff;
    std::vector<Level> levels;
    toff_t activeDirectoryOffset = 0;
    std::mutex mutex;
};

uint32_t ceilDiv(uint32_t value, uint32_t divisor) {
    return value / divisor + (value % divisor == 0 ? 0 : 1);
}

bool isPowerOfTwo(uint32_t value) {
    return value != 0 && (value & (value - 1)) == 0;
}

uint32_t inferSample(uint32_t baseWidth, uint32_t baseHeight, uint32_t width, uint32_t height) {
    if (width == 0 || height == 0 || width > baseWidth || height > baseHeight) return 0;
    const uint32_t candidate = std::max(
        1U,
        static_cast<uint32_t>((static_cast<uint64_t>(baseWidth) + width / 2U) / width)
    );
    if (!isPowerOfTwo(candidate)) return 0;
    const uint32_t expectedWidth = ceilDiv(baseWidth, candidate);
    const uint32_t expectedHeight = ceilDiv(baseHeight, candidate);
    const auto near = [](uint32_t left, uint32_t right) {
        return left > right ? left - right <= 1U : right - left <= 1U;
    };
    return near(width, expectedWidth) && near(height, expectedHeight) ? candidate : 0;
}

Level readLevel(TIFF* tiff, uint32_t baseWidth, uint32_t baseHeight) {
    TiffApi& lib = api();
    Level level;
    level.directoryOffset = lib.currentDirOffset(tiff);
    uint16_t bitsPerSample = 0;
    uint16_t orientation = 0;
    uint16_t planarConfig = 0;
    uint16_t sampleFormat = 0;
    if (!lib.getField(tiff, TIFFTAG_IMAGEWIDTH, &level.width) ||
        !lib.getField(tiff, TIFFTAG_IMAGELENGTH, &level.height) ||
        !lib.getFieldDefaulted(tiff, TIFFTAG_BITSPERSAMPLE, &bitsPerSample) ||
        !lib.getFieldDefaulted(tiff, TIFFTAG_SAMPLESPERPIXEL, &level.samplesPerPixel) ||
        !lib.getFieldDefaulted(tiff, TIFFTAG_PHOTOMETRIC, &level.photometric) ||
        !lib.getFieldDefaulted(tiff, TIFFTAG_ORIENTATION, &orientation) ||
        !lib.getFieldDefaulted(tiff, TIFFTAG_PLANARCONFIG, &planarConfig) ||
        !lib.getFieldDefaulted(tiff, TIFFTAG_SAMPLEFORMAT, &sampleFormat) ||
        !lib.getFieldDefaulted(tiff, TIFFTAG_COMPRESSION, &level.compression)) {
        throw std::runtime_error("TIFF is missing required image metadata");
    }
    if (level.width == 0 || level.height == 0 ||
        level.width > static_cast<uint32_t>(std::numeric_limits<jint>::max()) ||
        level.height > static_cast<uint32_t>(std::numeric_limits<jint>::max())) {
        throw std::runtime_error("TIFF dimensions are unsupported");
    }
    level.sample = inferSample(baseWidth, baseHeight, level.width, level.height);
    if (level.sample == 0) throw std::runtime_error("TIFF overview dimensions are not a power-of-two pyramid");
    if (bitsPerSample != 8 || sampleFormat != SAMPLEFORMAT_UINT) {
        throw std::runtime_error("Only unsigned 8-bit TIFF samples are currently supported");
    }
    if (orientation != ORIENTATION_TOPLEFT) {
        throw std::runtime_error("TIFF orientation must be top-left for indexed decoding");
    }
    if (planarConfig != PLANARCONFIG_CONTIG) {
        throw std::runtime_error("Planar TIFF samples are not currently supported");
    }
    if (level.photometric == PHOTOMETRIC_MINISBLACK || level.photometric == PHOTOMETRIC_MINISWHITE) {
        if (level.samplesPerPixel != 1) {
            throw std::runtime_error("Only single-channel grayscale TIFF is currently supported");
        }
    } else if (level.photometric == PHOTOMETRIC_RGB) {
        if (level.samplesPerPixel == 4) {
            uint16_t extraCount = 0;
            uint16_t* extraTypes = nullptr;
            if (!lib.getField(tiff, TIFFTAG_EXTRASAMPLES, &extraCount, &extraTypes) ||
                extraCount != 1 || extraTypes == nullptr ||
                (extraTypes[0] != EXTRASAMPLE_ASSOCALPHA &&
                    extraTypes[0] != EXTRASAMPLE_UNASSALPHA)) {
                throw std::runtime_error("Four-channel TIFF requires one declared alpha sample");
            }
            level.alphaMode = extraTypes[0];
        } else if (level.samplesPerPixel != 3) {
            throw std::runtime_error("Only RGB or RGBA TIFF is currently supported");
        }
    } else {
        throw std::runtime_error("Indexed TIFF currently supports grayscale and RGB photometric data only");
    }

    if (lib.isTiled(tiff)) {
        level.mode = MODE_TILES;
        if (!lib.getField(tiff, TIFFTAG_TILEWIDTH, &level.blockWidth) ||
            !lib.getField(tiff, TIFFTAG_TILELENGTH, &level.blockHeight) ||
            level.blockWidth == 0 || level.blockHeight == 0 || lib.numberOfTiles(tiff) == 0) {
            throw std::runtime_error("TIFF tile directory is invalid");
        }
        level.blockBytes = lib.tileSize(tiff);
    } else {
        level.mode = MODE_STRIPS;
        uint32_t rowsPerStrip = 0;
        if (!lib.getFieldDefaulted(tiff, TIFFTAG_ROWSPERSTRIP, &rowsPerStrip) ||
            rowsPerStrip == 0 || lib.numberOfStrips(tiff) == 0) {
            throw std::runtime_error("TIFF strip directory is invalid");
        }
        level.blockWidth = level.width;
        level.blockHeight = std::min(rowsPerStrip, level.height);
        level.blockBytes = lib.stripSize(tiff);
    }
    if (level.blockBytes <= 0 || static_cast<uint64_t>(level.blockBytes) > MAX_DECODED_BLOCK_BYTES) {
        throw std::runtime_error("TIFF blocks are too large for bounded random-access decoding");
    }
    return level;
}

std::vector<Level> inspectLevels(TIFF* tiff) {
    TiffApi& lib = api();
    const toff_t baseOffset = lib.currentDirOffset(tiff);
    uint32_t baseWidth = 0;
    uint32_t baseHeight = 0;
    if (!lib.getField(tiff, TIFFTAG_IMAGEWIDTH, &baseWidth) ||
        !lib.getField(tiff, TIFFTAG_IMAGELENGTH, &baseHeight)) {
        throw std::runtime_error("TIFF is missing base dimensions");
    }
    std::vector<Level> levels;
    levels.push_back(readLevel(tiff, baseWidth, baseHeight));
    std::unordered_set<toff_t> visited{baseOffset};

    uint16_t subIfdCount = 0;
    toff_t* subIfdOffsets = nullptr;
    std::vector<toff_t> copiedSubIfds;
    if (lib.getField(tiff, TIFFTAG_SUBIFD, &subIfdCount, &subIfdOffsets) && subIfdOffsets != nullptr) {
        copiedSubIfds.assign(subIfdOffsets, subIfdOffsets + std::min<size_t>(subIfdCount, MAX_LEVELS));
    }
    for (toff_t offset : copiedSubIfds) {
        if (offset == 0 || !visited.insert(offset).second || !lib.setSubDirectory(tiff, offset)) continue;
        try {
            levels.push_back(readLevel(tiff, baseWidth, baseHeight));
        } catch (const std::exception& error) {
            logError("ignore-subifd", error.what());
        }
    }

    if (!lib.setSubDirectory(tiff, baseOffset)) {
        throw std::runtime_error("Unable to restore the TIFF base directory");
    }
    while (levels.size() < MAX_LEVELS && lib.readDirectory(tiff)) {
        const toff_t offset = lib.currentDirOffset(tiff);
        if (offset == 0 || !visited.insert(offset).second) continue;
        uint32_t subfileType = 0;
        lib.getFieldDefaulted(tiff, TIFFTAG_SUBFILETYPE, &subfileType);
        if ((subfileType & FILETYPE_REDUCEDIMAGE) == 0) continue;
        try {
            levels.push_back(readLevel(tiff, baseWidth, baseHeight));
        } catch (const std::exception& error) {
            logError("ignore-overview", error.what());
        }
    }

    std::sort(levels.begin(), levels.end(), [](const Level& left, const Level& right) {
        return left.sample < right.sample;
    });
    levels.erase(
        std::unique(levels.begin(), levels.end(), [](const Level& left, const Level& right) {
            return left.sample == right.sample;
        }),
        levels.end()
    );
    if (!lib.setSubDirectory(tiff, baseOffset)) {
        throw std::runtime_error("Unable to select the TIFF base directory");
    }
    return levels;
}

std::unique_ptr<Decoder> openDecoder(const std::string& sourcePath) {
    TiffApi& lib = api();
    UniqueTiff tiff(lib.open(sourcePath.c_str(), "r"));
    if (!tiff) throw std::runtime_error("Unable to open the TIFF source");
    std::vector<Level> levels = inspectLevels(tiff.get());
    if (levels.empty()) throw std::runtime_error("TIFF has no compatible image directory");
    std::unique_ptr<Decoder> decoder(new Decoder());
    decoder->activeDirectoryOffset = levels.front().directoryOffset;
    decoder->tiff = std::move(tiff);
    decoder->levels = std::move(levels);
    return decoder;
}

void selectLevel(Decoder& decoder, const Level& level) {
    if (decoder.activeDirectoryOffset != level.directoryOffset) {
        if (!api().setSubDirectory(decoder.tiff.get(), level.directoryOffset)) {
            throw std::runtime_error("Unable to select the TIFF overview directory");
        }
        decoder.activeDirectoryOffset = level.directoryOffset;
    }
    if (level.compression == COMPRESSION_JPEG) {
        api().setField(decoder.tiff.get(), TIFFTAG_JPEGCOLORMODE, 1);
    }
}

struct Block {
    std::vector<uint8_t> bytes;
    tmsize_t decodedBytes = 0;
};

const Block& loadBlock(
    Decoder& decoder,
    const Level& level,
    uint32_t levelX,
    uint32_t levelY,
    std::unordered_map<uint32_t, Block>& cache
) {
    TiffApi& lib = api();
    const uint32_t index = level.mode == MODE_TILES
        ? lib.computeTile(decoder.tiff.get(), levelX, levelY, 0, 0)
        : lib.computeStrip(decoder.tiff.get(), levelY, 0);
    auto existing = cache.find(index);
    if (existing != cache.end()) return existing->second;
    Block block;
    block.bytes.resize(static_cast<size_t>(level.blockBytes));
    block.decodedBytes = level.mode == MODE_TILES
        ? lib.readEncodedTile(decoder.tiff.get(), index, block.bytes.data(), READ_WHOLE_BLOCK)
        : lib.readEncodedStrip(decoder.tiff.get(), index, block.bytes.data(), READ_WHOLE_BLOCK);
    if (block.decodedBytes <= 0 || block.decodedBytes > level.blockBytes) {
        throw std::runtime_error("Unable to decode a TIFF tile or strip");
    }
    return cache.emplace(index, std::move(block)).first->second;
}

bool decode(
    Decoder& decoder,
    int32_t left,
    int32_t top,
    int32_t right,
    int32_t bottom,
    int32_t sampleSize,
    AndroidBitmapInfo bitmapInfo,
    uint8_t* pixels
) {
    const Level& base = decoder.levels.front();
    if (sampleSize <= 0 || left < 0 || top < 0 || right <= left || bottom <= top ||
        static_cast<uint32_t>(right) > base.width || static_cast<uint32_t>(bottom) > base.height) {
        return false;
    }
    const uint32_t outputWidth = ceilDiv(static_cast<uint32_t>(right - left), sampleSize);
    const uint32_t outputHeight = ceilDiv(static_cast<uint32_t>(bottom - top), sampleSize);
    if (bitmapInfo.width != outputWidth || bitmapInfo.height != outputHeight) return false;

    const Level* selected = &base;
    for (const Level& level : decoder.levels) {
        if (level.sample > static_cast<uint32_t>(sampleSize)) break;
        selected = &level;
    }
    selectLevel(decoder, *selected);
    std::unordered_map<uint32_t, Block> cache;
    cache.reserve(16);

    for (uint32_t outputY = 0; outputY < outputHeight; ++outputY) {
        uint8_t* outputRow = pixels + static_cast<size_t>(outputY) * bitmapInfo.stride;
        const uint32_t sourceY = static_cast<uint32_t>(top) + outputY * sampleSize;
        const uint32_t levelY = std::min(sourceY / selected->sample, selected->height - 1);
        for (uint32_t outputX = 0; outputX < outputWidth; ++outputX) {
            const uint32_t sourceX = static_cast<uint32_t>(left) + outputX * sampleSize;
            const uint32_t levelX = std::min(sourceX / selected->sample, selected->width - 1);
            const Block& block = loadBlock(decoder, *selected, levelX, levelY, cache);
            const uint32_t localX = selected->mode == MODE_TILES
                ? levelX % selected->blockWidth
                : levelX;
            const uint32_t localY = levelY % selected->blockHeight;
            const uint64_t stride = static_cast<uint64_t>(selected->blockWidth) * selected->samplesPerPixel;
            const uint64_t offset = static_cast<uint64_t>(localY) * stride +
                static_cast<uint64_t>(localX) * selected->samplesPerPixel;
            if (offset + selected->samplesPerPixel > static_cast<uint64_t>(block.decodedBytes)) {
                throw std::runtime_error("Decoded TIFF block is shorter than its directory metadata");
            }
            const uint8_t* source = block.bytes.data() + offset;
            uint8_t* output = outputRow + static_cast<size_t>(outputX) * 4;
            if (selected->photometric == PHOTOMETRIC_MINISBLACK ||
                selected->photometric == PHOTOMETRIC_MINISWHITE) {
                const uint8_t gray = selected->photometric == PHOTOMETRIC_MINISWHITE
                    ? static_cast<uint8_t>(255 - source[0])
                    : source[0];
                output[0] = gray;
                output[1] = gray;
                output[2] = gray;
                output[3] = 255;
            } else {
                uint8_t red = source[0];
                uint8_t green = source[1];
                uint8_t blue = source[2];
                const uint8_t alpha = selected->samplesPerPixel == 4 ? source[3] : 255;
                if (selected->alphaMode == EXTRASAMPLE_UNASSALPHA) {
                    red = static_cast<uint8_t>((static_cast<uint32_t>(red) * alpha + 127) / 255);
                    green = static_cast<uint8_t>((static_cast<uint32_t>(green) * alpha + 127) / 255);
                    blue = static_cast<uint8_t>((static_cast<uint32_t>(blue) * alpha + 127) / 255);
                }
                output[0] = red;
                output[1] = green;
                output[2] = blue;
                output[3] = alpha;
            }
        }
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_indexedtiff_IndexedTiffNative_probe(JNIEnv* env, jobject, jstring sourcePathValue) {
    JString sourcePath(env, sourcePathValue);
    if (!sourcePath.valid()) return nullptr;
    try {
        std::unique_ptr<Decoder> decoder = openDecoder(sourcePath.c_str());
        const Level& base = decoder->levels.front();
        const jint values[] = {
            static_cast<jint>(base.width),
            static_cast<jint>(base.height),
            static_cast<jint>(decoder->levels.size()),
            static_cast<jint>(base.mode),
            static_cast<jint>(base.blockWidth),
            static_cast<jint>(base.blockHeight),
            static_cast<jint>(base.compression),
        };
        jintArray result = env->NewIntArray(7);
        if (result != nullptr) env->SetIntArrayRegion(result, 0, 7, values);
        return result;
    } catch (const std::exception& error) {
        logError("probe", error.what());
        throwIOException(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_indexedtiff_IndexedTiffNative_open(JNIEnv* env, jobject, jstring sourcePathValue) {
    JString sourcePath(env, sourcePathValue);
    if (!sourcePath.valid()) return 0;
    try {
        return reinterpret_cast<jlong>(openDecoder(sourcePath.c_str()).release());
    } catch (const std::exception& error) {
        logError("open", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_indexedtiff_IndexedTiffNative_decode(
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
    if (decoder == nullptr || bitmap == nullptr) return JNI_FALSE;
    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> guard(decoder->mutex);
    void* pixelsValue = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixelsValue) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    bool success = false;
    try {
        success = decode(
            *decoder,
            left,
            top,
            right,
            bottom,
            sampleSize,
            bitmapInfo,
            static_cast<uint8_t*>(pixelsValue)
        );
    } catch (const std::exception& error) {
        logError("decode", error.what());
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_indexedtiff_IndexedTiffNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Decoder*>(handle);
}
