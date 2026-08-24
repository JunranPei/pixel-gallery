#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>

namespace {

constexpr std::array<uint8_t, 8> kMagic = {'I', 'H', 'E', 'I', 'F', 'I', 'D', 'X'};
constexpr uint32_t kVersion = 1;
constexpr uint32_t kHeaderBytes = 80;
constexpr uint32_t kEntryBytes = 48;
constexpr uint32_t kTileSize = 512;
constexpr uint64_t kMaxEntries = 10'000'000;

struct Header {
    uint64_t sourceBytes = 0;
    int64_t sourceModifiedMillis = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t tileSize = 0;
    uint32_t levelCount = 0;
    uint64_t entryCount = 0;
    uint64_t directoryOffset = 0;
    uint64_t payloadOffset = 0;
    uint64_t totalBytes = 0;
};

struct Entry {
    uint32_t sample = 0;
    uint32_t x = 0;
    uint32_t y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint64_t offset = 0;
    uint32_t compressedBytes = 0;
    uint32_t rawBytes = 0;
    uint32_t crc = 0;
};

struct Level {
    uint32_t sample = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t columns = 0;
    uint32_t rows = 0;
    size_t firstEntry = 0;
};

struct Decoder {
    int fd = -1;
    Header header;
    std::vector<Entry> entries;
    std::vector<Level> levels;
    std::mutex mutex;

    ~Decoder() {
        if (fd >= 0) ::close(fd);
    }
};

class JString final {
public:
    JString(JNIEnv* env, jstring value) : env_(env), value_(value) {
        chars_ = value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
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
    __android_log_print(ANDROID_LOG_ERROR, "IndexedHeif", "%s: %s", stage, message.c_str());
}

uint32_t ceilDiv(uint32_t value, uint32_t divisor) {
    return value / divisor + (value % divisor == 0 ? 0 : 1);
}

bool mulOverflow(uint64_t a, uint64_t b, uint64_t* result) {
    if (a != 0 && b > std::numeric_limits<uint64_t>::max() / a) return true;
    *result = a * b;
    return false;
}

void putU32(std::vector<uint8_t>& out, size_t offset, uint32_t value) {
    out[offset] = static_cast<uint8_t>(value);
    out[offset + 1] = static_cast<uint8_t>(value >> 8);
    out[offset + 2] = static_cast<uint8_t>(value >> 16);
    out[offset + 3] = static_cast<uint8_t>(value >> 24);
}

void putU64(std::vector<uint8_t>& out, size_t offset, uint64_t value) {
    for (size_t i = 0; i < 8; ++i) out[offset + i] = static_cast<uint8_t>(value >> (i * 8));
}

uint32_t getU32(const uint8_t* in, size_t offset) {
    return static_cast<uint32_t>(in[offset]) |
           (static_cast<uint32_t>(in[offset + 1]) << 8) |
           (static_cast<uint32_t>(in[offset + 2]) << 16) |
           (static_cast<uint32_t>(in[offset + 3]) << 24);
}

uint64_t getU64(const uint8_t* in, size_t offset) {
    uint64_t value = 0;
    for (size_t i = 0; i < 8; ++i) value |= static_cast<uint64_t>(in[offset + i]) << (i * 8);
    return value;
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

bool pwriteAll(int fd, const void* source, size_t bytes, uint64_t offset) {
    const auto* input = static_cast<const uint8_t*>(source);
    size_t completed = 0;
    while (completed < bytes) {
        const ssize_t count = pwrite(
            fd,
            input + completed,
            bytes - completed,
            static_cast<off_t>(offset + completed)
        );
        if (count < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        completed += static_cast<size_t>(count);
    }
    return true;
}

bool fwriteAll(FILE* file, const void* source, size_t bytes) {
    return bytes == 0 || fwrite(source, 1, bytes, file) == bytes;
}

std::vector<Level> makeLevels(uint32_t width, uint32_t height) {
    if (width == 0 || height == 0) throw std::runtime_error("HEIF/AVIF dimensions are empty");
    std::vector<Level> levels;
    uint32_t sample = 1;
    size_t firstEntry = 0;
    while (true) {
        Level level;
        level.sample = sample;
        level.width = width;
        level.height = height;
        level.columns = ceilDiv(width, kTileSize);
        level.rows = ceilDiv(height, kTileSize);
        level.firstEntry = firstEntry;
        const uint64_t count = static_cast<uint64_t>(level.columns) * level.rows;
        if (count > kMaxEntries || firstEntry > kMaxEntries - count) {
            throw std::runtime_error("HEIF/AVIF tile directory is too large");
        }
        firstEntry += static_cast<size_t>(count);
        levels.push_back(level);
        if (width == 1 && height == 1) break;
        width = ceilDiv(width, 2);
        height = ceilDiv(height, 2);
        if (sample > std::numeric_limits<uint32_t>::max() / 2) {
            throw std::runtime_error("HEIF/AVIF pyramid has too many levels");
        }
        sample *= 2;
    }
    return levels;
}

std::vector<Entry> makeEntries(const std::vector<Level>& levels) {
    const Level& last = levels.back();
    const size_t total = last.firstEntry + static_cast<size_t>(last.columns) * last.rows;
    std::vector<Entry> entries(total);
    for (const Level& level : levels) {
        for (uint32_t tileY = 0; tileY < level.rows; ++tileY) {
            for (uint32_t tileX = 0; tileX < level.columns; ++tileX) {
                Entry& entry = entries[level.firstEntry + static_cast<size_t>(tileY) * level.columns + tileX];
                entry.sample = level.sample;
                entry.x = tileX * kTileSize;
                entry.y = tileY * kTileSize;
                entry.width = std::min(kTileSize, level.width - entry.x);
                entry.height = std::min(kTileSize, level.height - entry.y);
                const uint64_t raw = static_cast<uint64_t>(entry.width) * entry.height * 4;
                entry.rawBytes = static_cast<uint32_t>(raw);
            }
        }
    }
    return entries;
}

void premultiplyRow(uint8_t* row, uint32_t width) {
    for (uint32_t x = 0; x < width; ++x) {
        uint8_t* pixel = row + static_cast<size_t>(x) * 4;
        const uint32_t alpha = pixel[3];
        pixel[0] = static_cast<uint8_t>((pixel[0] * alpha + 127) / 255);
        pixel[1] = static_cast<uint8_t>((pixel[1] * alpha + 127) / 255);
        pixel[2] = static_cast<uint8_t>((pixel[2] * alpha + 127) / 255);
    }
}

void writeTile(FILE* output, Entry& entry, const std::vector<uint8_t>& raw) {
    if (raw.size() != entry.rawBytes) throw std::runtime_error("HEIF/AVIF tile size mismatch");
    uLongf compressedSize = compressBound(static_cast<uLong>(raw.size()));
    std::vector<uint8_t> compressed(compressedSize);
    const int result = compress2(
        compressed.data(),
        &compressedSize,
        raw.data(),
        static_cast<uLong>(raw.size()),
        Z_BEST_SPEED
    );
    if (result != Z_OK) throw std::runtime_error("Unable to compress HEIF/AVIF index tile");
    const off_t offset = ftello(output);
    if (offset < 0 || static_cast<uint64_t>(offset) > std::numeric_limits<uint64_t>::max() - compressedSize) {
        throw std::runtime_error("HEIF/AVIF index offset overflow");
    }
    if (!fwriteAll(output, compressed.data(), static_cast<size_t>(compressedSize))) {
        throw std::runtime_error("Unable to write HEIF/AVIF index tile");
    }
    entry.offset = static_cast<uint64_t>(offset);
    entry.compressedBytes = static_cast<uint32_t>(compressedSize);
    entry.crc = crc32(0, raw.data(), static_cast<uInt>(raw.size()));
}

void writeBand(
    FILE* output,
    const Level& level,
    std::vector<Entry>& entries,
    const uint8_t* band,
    uint32_t bandTop,
    uint32_t bandHeight
) {
    const size_t sourceStride = static_cast<size_t>(level.width) * 4;
    const uint32_t tileY = bandTop / kTileSize;
    for (uint32_t tileX = 0; tileX < level.columns; ++tileX) {
        Entry& entry = entries[level.firstEntry + static_cast<size_t>(tileY) * level.columns + tileX];
        if (entry.height != bandHeight) throw std::runtime_error("HEIF/AVIF band height mismatch");
        std::vector<uint8_t> tile(entry.rawBytes);
        const size_t tileStride = static_cast<size_t>(entry.width) * 4;
        for (uint32_t row = 0; row < bandHeight; ++row) {
            memcpy(
                tile.data() + static_cast<size_t>(row) * tileStride,
                band + static_cast<size_t>(row) * sourceStride + static_cast<size_t>(entry.x) * 4,
                tileStride
            );
        }
        writeTile(output, entry, tile);
    }
}

std::vector<uint8_t> loadTileRaw(int fd, const Entry& entry) {
    std::vector<uint8_t> compressed(entry.compressedBytes);
    if (!preadAll(fd, compressed.data(), compressed.size(), entry.offset)) {
        throw std::runtime_error("Unable to read HEIF/AVIF index tile");
    }
    std::vector<uint8_t> raw(entry.rawBytes);
    uLongf rawSize = raw.size();
    const int result = uncompress(raw.data(), &rawSize, compressed.data(), compressed.size());
    if (result != Z_OK || rawSize != raw.size()) {
        throw std::runtime_error("Unable to inflate HEIF/AVIF index tile");
    }
    const uint32_t actualCrc = crc32(0, raw.data(), static_cast<uInt>(raw.size()));
    if (actualCrc != entry.crc) throw std::runtime_error("HEIF/AVIF index tile checksum mismatch");
    return raw;
}

const uint8_t* pixelAt(
    const Level& level,
    const std::vector<Entry>& entries,
    int fd,
    std::unordered_map<size_t, std::vector<uint8_t>>& cache,
    uint32_t x,
    uint32_t y
) {
    if (x >= level.width || y >= level.height) return nullptr;
    const uint32_t tileX = x / kTileSize;
    const uint32_t tileY = y / kTileSize;
    const size_t index = level.firstEntry + static_cast<size_t>(tileY) * level.columns + tileX;
    auto found = cache.find(index);
    if (found == cache.end()) {
        found = cache.emplace(index, loadTileRaw(fd, entries[index])).first;
    }
    const Entry& entry = entries[index];
    const uint32_t localX = x - entry.x;
    const uint32_t localY = y - entry.y;
    return found->second.data() + (static_cast<size_t>(localY) * entry.width + localX) * 4;
}

void generateLowerLevels(FILE* output, const std::vector<Level>& levels, std::vector<Entry>& entries) {
    const int fd = fileno(output);
    for (size_t levelIndex = 1; levelIndex < levels.size(); ++levelIndex) {
        if (fflush(output) != 0) throw std::runtime_error("Unable to flush HEIF/AVIF index level");
        const Level& source = levels[levelIndex - 1];
        const Level& destination = levels[levelIndex];
        for (uint32_t tileY = 0; tileY < destination.rows; ++tileY) {
            for (uint32_t tileX = 0; tileX < destination.columns; ++tileX) {
                Entry& outputEntry = entries[
                    destination.firstEntry + static_cast<size_t>(tileY) * destination.columns + tileX
                ];
                std::vector<uint8_t> outputTile(outputEntry.rawBytes);
                std::unordered_map<size_t, std::vector<uint8_t>> cache;
                cache.reserve(4);
                for (uint32_t y = 0; y < outputEntry.height; ++y) {
                    for (uint32_t x = 0; x < outputEntry.width; ++x) {
                        const uint32_t sourceX = (outputEntry.x + x) * 2;
                        const uint32_t sourceY = (outputEntry.y + y) * 2;
                        uint32_t sums[4] = {0, 0, 0, 0};
                        uint32_t count = 0;
                        for (uint32_t dy = 0; dy < 2; ++dy) {
                            for (uint32_t dx = 0; dx < 2; ++dx) {
                                const uint8_t* pixel = pixelAt(
                                    source,
                                    entries,
                                    fd,
                                    cache,
                                    sourceX + dx,
                                    sourceY + dy
                                );
                                if (pixel == nullptr) continue;
                                for (size_t channel = 0; channel < 4; ++channel) sums[channel] += pixel[channel];
                                ++count;
                            }
                        }
                        if (count == 0) throw std::runtime_error("HEIF/AVIF pyramid source pixel is missing");
                        uint8_t* destinationPixel = outputTile.data() +
                            (static_cast<size_t>(y) * outputEntry.width + x) * 4;
                        for (size_t channel = 0; channel < 4; ++channel) {
                            destinationPixel[channel] = static_cast<uint8_t>((sums[channel] + count / 2) / count);
                        }
                    }
                }
                writeTile(output, outputEntry, outputTile);
            }
        }
    }
}

void writeHeaderAndDirectory(
    FILE* output,
    const Header& header,
    const std::vector<Entry>& entries
) {
    std::vector<uint8_t> bytes(static_cast<size_t>(header.payloadOffset), 0);
    memcpy(bytes.data(), kMagic.data(), kMagic.size());
    putU32(bytes, 8, kVersion);
    putU32(bytes, 12, kHeaderBytes);
    putU64(bytes, 16, header.sourceBytes);
    putU64(bytes, 24, static_cast<uint64_t>(header.sourceModifiedMillis));
    putU32(bytes, 32, header.width);
    putU32(bytes, 36, header.height);
    putU32(bytes, 40, header.tileSize);
    putU32(bytes, 44, header.levelCount);
    putU64(bytes, 48, header.entryCount);
    putU64(bytes, 56, header.directoryOffset);
    putU64(bytes, 64, header.payloadOffset);
    putU64(bytes, 72, header.totalBytes);

    for (size_t i = 0; i < entries.size(); ++i) {
        const Entry& entry = entries[i];
        const size_t offset = kHeaderBytes + i * kEntryBytes;
        putU32(bytes, offset, entry.sample);
        putU32(bytes, offset + 4, entry.x);
        putU32(bytes, offset + 8, entry.y);
        putU32(bytes, offset + 12, entry.width);
        putU32(bytes, offset + 16, entry.height);
        putU64(bytes, offset + 24, entry.offset);
        putU32(bytes, offset + 32, entry.compressedBytes);
        putU32(bytes, offset + 36, entry.rawBytes);
        putU32(bytes, offset + 40, entry.crc);
    }
    if (fseeko(output, 0, SEEK_SET) != 0 || !fwriteAll(output, bytes.data(), bytes.size())) {
        throw std::runtime_error("Unable to publish HEIF/AVIF index directory");
    }
    if (fflush(output) != 0 || fsync(fileno(output)) != 0) {
        throw std::runtime_error("Unable to sync HEIF/AVIF index");
    }
}

Header parseHeader(const uint8_t* bytes) {
    Header header;
    header.sourceBytes = getU64(bytes, 16);
    header.sourceModifiedMillis = static_cast<int64_t>(getU64(bytes, 24));
    header.width = getU32(bytes, 32);
    header.height = getU32(bytes, 36);
    header.tileSize = getU32(bytes, 40);
    header.levelCount = getU32(bytes, 44);
    header.entryCount = getU64(bytes, 48);
    header.directoryOffset = getU64(bytes, 56);
    header.payloadOffset = getU64(bytes, 64);
    header.totalBytes = getU64(bytes, 72);
    return header;
}

bool readIndex(
    int fd,
    uint64_t expectedSourceBytes,
    int64_t expectedSourceModifiedMillis,
    Header* headerOut,
    std::vector<Entry>* entriesOut,
    std::vector<Level>* levelsOut
) {
    struct stat fileStat{};
    if (fstat(fd, &fileStat) != 0 || fileStat.st_size < kHeaderBytes) return false;
    std::array<uint8_t, kHeaderBytes> headerBytes{};
    if (!preadAll(fd, headerBytes.data(), headerBytes.size(), 0)) return false;
    if (!std::equal(kMagic.begin(), kMagic.end(), headerBytes.begin()) ||
        getU32(headerBytes.data(), 8) != kVersion ||
        getU32(headerBytes.data(), 12) != kHeaderBytes) {
        return false;
    }
    Header header = parseHeader(headerBytes.data());
    if (header.sourceBytes != expectedSourceBytes ||
        header.sourceModifiedMillis != expectedSourceModifiedMillis ||
        header.tileSize != kTileSize || header.levelCount == 0 ||
        header.entryCount == 0 || header.entryCount > kMaxEntries ||
        header.directoryOffset != kHeaderBytes ||
        header.entryCount > (std::numeric_limits<uint64_t>::max() - kHeaderBytes) / kEntryBytes ||
        header.payloadOffset != kHeaderBytes + header.entryCount * kEntryBytes ||
        header.totalBytes != static_cast<uint64_t>(fileStat.st_size) ||
        header.payloadOffset > header.totalBytes) {
        return false;
    }

    std::vector<Level> levels;
    try {
        levels = makeLevels(header.width, header.height);
    } catch (...) {
        return false;
    }
    if (levels.size() != header.levelCount) return false;
    const Level& last = levels.back();
    const uint64_t expectedEntries = last.firstEntry + static_cast<uint64_t>(last.columns) * last.rows;
    if (expectedEntries != header.entryCount) return false;

    std::vector<uint8_t> directory(static_cast<size_t>(header.entryCount * kEntryBytes));
    if (!preadAll(fd, directory.data(), directory.size(), header.directoryOffset)) return false;
    std::vector<Entry> entries(static_cast<size_t>(header.entryCount));
    uint64_t nextPayloadOffset = header.payloadOffset;
    for (size_t i = 0; i < entries.size(); ++i) {
        const size_t offset = i * kEntryBytes;
        Entry& entry = entries[i];
        entry.sample = getU32(directory.data(), offset);
        entry.x = getU32(directory.data(), offset + 4);
        entry.y = getU32(directory.data(), offset + 8);
        entry.width = getU32(directory.data(), offset + 12);
        entry.height = getU32(directory.data(), offset + 16);
        entry.offset = getU64(directory.data(), offset + 24);
        entry.compressedBytes = getU32(directory.data(), offset + 32);
        entry.rawBytes = getU32(directory.data(), offset + 36);
        entry.crc = getU32(directory.data(), offset + 40);
        if (entry.offset != nextPayloadOffset || entry.compressedBytes == 0 ||
            nextPayloadOffset > header.totalBytes - entry.compressedBytes) {
            return false;
        }
        nextPayloadOffset += entry.compressedBytes;
    }
    if (nextPayloadOffset != header.totalBytes) return false;

    const std::vector<Entry> expectedMetadata = makeEntries(levels);
    for (size_t i = 0; i < entries.size(); ++i) {
        const Entry& actual = entries[i];
        const Entry& expected = expectedMetadata[i];
        if (actual.sample != expected.sample || actual.x != expected.x || actual.y != expected.y ||
            actual.width != expected.width || actual.height != expected.height ||
            actual.rawBytes != expected.rawBytes) {
            return false;
        }
    }
    *headerOut = header;
    *entriesOut = std::move(entries);
    *levelsOut = std::move(levels);
    return true;
}

struct Builder {
    FILE* output = nullptr;
    Header header;
    std::vector<Level> levels;
    std::vector<Entry> entries;
    size_t nextBaseEntry = 0;
    size_t baseEntryCount = 0;
    bool finished = false;

    Builder(
        const std::string& destinationPath,
        uint64_t sourceBytes,
        int64_t sourceModifiedMillis,
        uint32_t width,
        uint32_t height
    ) {
        levels = makeLevels(width, height);
        entries = makeEntries(levels);
        baseEntryCount = static_cast<size_t>(levels.front().columns) * levels.front().rows;
        header.sourceBytes = sourceBytes;
        header.sourceModifiedMillis = sourceModifiedMillis;
        header.width = width;
        header.height = height;
        header.tileSize = kTileSize;
        header.levelCount = static_cast<uint32_t>(levels.size());
        header.entryCount = entries.size();
        header.directoryOffset = kHeaderBytes;
        header.payloadOffset = kHeaderBytes + static_cast<uint64_t>(entries.size()) * kEntryBytes;
        output = fopen(destinationPath.c_str(), "w+b");
        if (output == nullptr) throw std::runtime_error("Unable to create the HEIF/AVIF index");
        if (fseeko(output, static_cast<off_t>(header.payloadOffset), SEEK_SET) != 0) {
            fclose(output);
            output = nullptr;
            throw std::runtime_error("Unable to reserve the HEIF/AVIF index directory");
        }
    }

    ~Builder() {
        if (output != nullptr) fclose(output);
    }

    void append(JNIEnv* env, uint32_t left, uint32_t top, jobject bitmap) {
        if (finished || nextBaseEntry >= baseEntryCount || bitmap == nullptr) {
            throw std::runtime_error("Unexpected HEIF/AVIF base tile");
        }
        Entry& entry = entries[nextBaseEntry];
        if (entry.x != left || entry.y != top) {
            throw std::runtime_error("HEIF/AVIF base tiles are out of order");
        }
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            info.width != entry.width || info.height != entry.height) {
            throw std::runtime_error("HEIF/AVIF base tile bitmap is incompatible");
        }
        void* pixelsValue = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixelsValue) != ANDROID_BITMAP_RESULT_SUCCESS) {
            throw std::runtime_error("Unable to lock HEIF/AVIF base tile pixels");
        }
        try {
            std::vector<uint8_t> raw(entry.rawBytes);
            const auto* pixels = static_cast<const uint8_t*>(pixelsValue);
            const size_t rowBytes = static_cast<size_t>(entry.width) * 4;
            for (uint32_t row = 0; row < entry.height; ++row) {
                memcpy(raw.data() + static_cast<size_t>(row) * rowBytes,
                       pixels + static_cast<size_t>(row) * info.stride,
                       rowBytes);
            }
            writeTile(output, entry, raw);
            ++nextBaseEntry;
        } catch (...) {
            AndroidBitmap_unlockPixels(env, bitmap);
            throw;
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }

    std::array<jint, 4> finish() {
        if (finished) throw std::runtime_error("HEIF/AVIF index is already finished");
        if (nextBaseEntry != baseEntryCount) {
            throw std::runtime_error("HEIF/AVIF base level is incomplete");
        }
        generateLowerLevels(output, levels, entries);
        const off_t end = ftello(output);
        if (end < 0) throw std::runtime_error("Unable to determine HEIF/AVIF index size");
        header.totalBytes = static_cast<uint64_t>(end);
        writeHeaderAndDirectory(output, header, entries);
        finished = true;
        return {
            static_cast<jint>(header.width),
            static_cast<jint>(header.height),
            static_cast<jint>(header.levelCount),
            static_cast<jint>(header.entryCount),
        };
    }
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_indexedheif_IndexedHeifNative_beginBuild(
    JNIEnv* env,
    jobject,
    jstring destinationPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis,
    jint width,
    jint height
) {
    JString destinationPath(env, destinationPathValue);
    if (!destinationPath.valid() || sourceBytes <= 0 || width <= 0 || height <= 0) return 0;
    try {
        return reinterpret_cast<jlong>(new Builder(
            destinationPath.c_str(),
            static_cast<uint64_t>(sourceBytes),
            static_cast<int64_t>(sourceModifiedMillis),
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height)
        ));
    } catch (const std::exception& error) {
        logError("beginBuild", error.what());
        throwIOException(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_indexedheif_IndexedHeifNative_appendBaseTile(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint left,
    jint top,
    jobject bitmap
) {
    auto* builder = reinterpret_cast<Builder*>(handle);
    if (builder == nullptr || left < 0 || top < 0) return JNI_FALSE;
    try {
        builder->append(env, static_cast<uint32_t>(left), static_cast<uint32_t>(top), bitmap);
        return JNI_TRUE;
    } catch (const std::exception& error) {
        logError("appendBaseTile", error.what());
        throwIOException(env, error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_indexedheif_IndexedHeifNative_finishBuild(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* builder = reinterpret_cast<Builder*>(handle);
    if (builder == nullptr) return nullptr;
    try {
        const auto info = builder->finish();
        jintArray result = env->NewIntArray(info.size());
        if (result != nullptr) env->SetIntArrayRegion(result, 0, info.size(), info.data());
        return result;
    } catch (const std::exception& error) {
        logError("finishBuild", error.what());
        throwIOException(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_indexedheif_IndexedHeifNative_closeBuilder(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Builder*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_indexedheif_IndexedHeifNative_validateIndex(
    JNIEnv* env,
    jobject,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    JString indexPath(env, indexPathValue);
    if (!indexPath.valid()) return JNI_FALSE;
    const int fd = ::open(indexPath.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return JNI_FALSE;
    Header header;
    std::vector<Entry> entries;
    std::vector<Level> levels;
    const bool valid = readIndex(
        fd,
        static_cast<uint64_t>(sourceBytes),
        static_cast<int64_t>(sourceModifiedMillis),
        &header,
        &entries,
        &levels
    );
    ::close(fd);
    return valid ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_indexedheif_IndexedHeifNative_open(
    JNIEnv* env,
    jobject,
    jstring indexPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    JString indexPath(env, indexPathValue);
    if (!indexPath.valid()) return 0;
    std::unique_ptr<Decoder> decoder(new Decoder());
    decoder->fd = ::open(indexPath.c_str(), O_RDONLY | O_CLOEXEC);
    if (decoder->fd < 0 || !readIndex(
            decoder->fd,
            static_cast<uint64_t>(sourceBytes),
            static_cast<int64_t>(sourceModifiedMillis),
            &decoder->header,
            &decoder->entries,
            &decoder->levels
        )) {
        return 0;
    }
    return reinterpret_cast<jlong>(decoder.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_indexedheif_IndexedHeifNative_decode(
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
        static_cast<uint32_t>(right) > decoder->header.width ||
        static_cast<uint32_t>(bottom) > decoder->header.height) {
        return JNI_FALSE;
    }

    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    const uint32_t expectedWidth = ceilDiv(static_cast<uint32_t>(right - left), sampleSize);
    const uint32_t expectedHeight = ceilDiv(static_cast<uint32_t>(bottom - top), sampleSize);
    if (bitmapInfo.width != expectedWidth || bitmapInfo.height != expectedHeight) return JNI_FALSE;

    std::lock_guard<std::mutex> guard(decoder->mutex);
    const Level* selected = &decoder->levels.front();
    for (const Level& level : decoder->levels) {
        if (level.sample > static_cast<uint32_t>(sampleSize)) break;
        selected = &level;
    }

    void* pixelsValue = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixelsValue) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    bool success = true;
    try {
        std::unordered_map<size_t, std::vector<uint8_t>> cache;
        cache.reserve(8);
        auto* pixels = static_cast<uint8_t*>(pixelsValue);
        for (uint32_t y = 0; y < expectedHeight; ++y) {
            uint8_t* outputRow = pixels + static_cast<size_t>(y) * bitmapInfo.stride;
            const uint32_t sourceY = static_cast<uint32_t>(top) + y * sampleSize;
            const uint32_t levelY = sourceY / selected->sample;
            if (selected->sample == static_cast<uint32_t>(sampleSize)) {
                uint32_t x = 0;
                while (x < expectedWidth) {
                    const uint32_t sourceX = static_cast<uint32_t>(left) + x * sampleSize;
                    const uint32_t levelX = sourceX / selected->sample;
                    const uint8_t* sourcePixel = pixelAt(
                        *selected, decoder->entries, decoder->fd, cache, levelX, levelY
                    );
                    if (sourcePixel == nullptr) throw std::runtime_error("HEIF/AVIF index pixel is missing");
                    uint32_t run = std::min(expectedWidth - x, kTileSize - levelX % kTileSize);
                    run = std::min(run, selected->width - levelX);
                    memcpy(
                        outputRow + static_cast<size_t>(x) * 4,
                        sourcePixel,
                        static_cast<size_t>(run) * 4
                    );
                    x += run;
                }
            } else {
                for (uint32_t x = 0; x < expectedWidth; ++x) {
                    const uint32_t sourceX = static_cast<uint32_t>(left) + x * sampleSize;
                    const uint32_t levelX = sourceX / selected->sample;
                    const uint8_t* sourcePixel = pixelAt(
                        *selected, decoder->entries, decoder->fd, cache, levelX, levelY
                    );
                    if (sourcePixel == nullptr) throw std::runtime_error("HEIF/AVIF index pixel is missing");
                    memcpy(outputRow + static_cast<size_t>(x) * 4, sourcePixel, 4);
                }
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
Java_io_github_indexedheif_IndexedHeifNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Decoder*>(handle);
}
