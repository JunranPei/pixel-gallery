#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <spng.h>
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
#include <unistd.h>

namespace {

constexpr std::array<uint8_t, 8> kMagic = {'I', 'P', 'N', 'G', 'I', 'D', 'X', 0};
constexpr uint32_t kVersion = 2;
constexpr uint32_t kHeaderBytes = 88;
constexpr uint32_t kEntryBytes = 48;
constexpr uint32_t kTileSize = 512;
constexpr uint64_t kMaxEntries = 10'000'000;
constexpr uint32_t kFilteredZlibEncoding = 1;
constexpr uint32_t kRgbChannels = 3;
constexpr uint32_t kRgbaChannels = 4;

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
    uint32_t channels = 0;
    uint32_t encoding = 0;
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
    __android_log_print(ANDROID_LOG_ERROR, "IndexedPng", "%s: %s", stage, message.c_str());
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
    if (width == 0 || height == 0) throw std::runtime_error("PNG dimensions are empty");
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
            throw std::runtime_error("PNG tile directory is too large");
        }
        firstEntry += static_cast<size_t>(count);
        levels.push_back(level);
        if (width == 1 && height == 1) break;
        width = ceilDiv(width, 2);
        height = ceilDiv(height, 2);
        if (sample > std::numeric_limits<uint32_t>::max() / 2) {
            throw std::runtime_error("PNG pyramid has too many levels");
        }
        sample *= 2;
    }
    return levels;
}

std::vector<Entry> makeEntries(const std::vector<Level>& levels, uint32_t channels) {
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
                const uint64_t raw = static_cast<uint64_t>(entry.width) * entry.height * channels;
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

uint64_t filterCost(const uint8_t* row, const uint8_t* previous, size_t bytes, uint32_t channels, uint8_t filter) {
    uint64_t cost = 0;
    for (size_t index = 0; index < bytes; ++index) {
        const uint8_t left = index >= channels ? row[index - channels] : 0;
        const uint8_t up = previous == nullptr ? 0 : previous[index];
        const uint8_t predictor = filter == SPNG_FILTER_SUB ? left :
            (filter == SPNG_FILTER_UP ? up : 0);
        const uint8_t residual = static_cast<uint8_t>(row[index] - predictor);
        const int signedResidual = residual < 128 ? residual : residual - 256;
        cost += static_cast<uint64_t>(signedResidual < 0 ? -signedResidual : signedResidual);
    }
    return cost;
}

std::vector<uint8_t> filterTile(
    const std::vector<uint8_t>& raw,
    uint32_t width,
    uint32_t height,
    uint32_t channels
) {
    const size_t rowBytes = static_cast<size_t>(width) * channels;
    std::vector<uint8_t> filtered((rowBytes + 1) * height);
    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* row = raw.data() + static_cast<size_t>(y) * rowBytes;
        const uint8_t* previous = y == 0 ? nullptr : row - rowBytes;
        const uint64_t noneCost = filterCost(row, previous, rowBytes, channels, SPNG_FILTER_NONE);
        const uint64_t subCost = filterCost(row, previous, rowBytes, channels, SPNG_FILTER_SUB);
        const uint64_t upCost = filterCost(row, previous, rowBytes, channels, SPNG_FILTER_UP);
        uint8_t filter = SPNG_FILTER_NONE;
        if (subCost < noneCost) filter = SPNG_FILTER_SUB;
        if (upCost < (filter == SPNG_FILTER_SUB ? subCost : noneCost)) filter = SPNG_FILTER_UP;

        uint8_t* destination = filtered.data() + static_cast<size_t>(y) * (rowBytes + 1);
        destination[0] = filter;
        ++destination;
        for (size_t index = 0; index < rowBytes; ++index) {
            const uint8_t left = index >= channels ? row[index - channels] : 0;
            const uint8_t up = previous == nullptr ? 0 : previous[index];
            const uint8_t predictor = filter == SPNG_FILTER_SUB ? left :
                (filter == SPNG_FILTER_UP ? up : 0);
            destination[index] = static_cast<uint8_t>(row[index] - predictor);
        }
    }
    return filtered;
}

std::vector<uint8_t> unfilterTile(
    const std::vector<uint8_t>& filtered,
    uint32_t width,
    uint32_t height,
    uint32_t channels
) {
    const size_t rowBytes = static_cast<size_t>(width) * channels;
    if (filtered.size() != (rowBytes + 1) * height) {
        throw std::runtime_error("PNG index filtered tile size mismatch");
    }
    std::vector<uint8_t> raw(rowBytes * height);
    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* source = filtered.data() + static_cast<size_t>(y) * (rowBytes + 1);
        const uint8_t filter = *source++;
        if (filter != SPNG_FILTER_NONE && filter != SPNG_FILTER_SUB && filter != SPNG_FILTER_UP) {
            throw std::runtime_error("PNG index tile uses an unsupported filter");
        }
        uint8_t* row = raw.data() + static_cast<size_t>(y) * rowBytes;
        const uint8_t* previous = y == 0 ? nullptr : row - rowBytes;
        for (size_t index = 0; index < rowBytes; ++index) {
            const uint8_t left = index >= channels ? row[index - channels] : 0;
            const uint8_t up = previous == nullptr ? 0 : previous[index];
            const uint8_t predictor = filter == SPNG_FILTER_SUB ? left :
                (filter == SPNG_FILTER_UP ? up : 0);
            row[index] = static_cast<uint8_t>(source[index] + predictor);
        }
    }
    return raw;
}

std::vector<uint8_t> compressFiltered(const std::vector<uint8_t>& filtered) {
    z_stream stream{};
    if (deflateInit2(&stream, 3, Z_DEFLATED, 15, 8, Z_FILTERED) != Z_OK) {
        throw std::runtime_error("Unable to initialize PNG index tile compressor");
    }
    std::vector<uint8_t> compressed(compressBound(static_cast<uLong>(filtered.size())));
    stream.next_in = const_cast<Bytef*>(filtered.data());
    stream.avail_in = static_cast<uInt>(filtered.size());
    stream.next_out = compressed.data();
    stream.avail_out = static_cast<uInt>(compressed.size());
    const int result = deflate(&stream, Z_FINISH);
    const size_t compressedBytes = stream.total_out;
    deflateEnd(&stream);
    if (result != Z_STREAM_END) throw std::runtime_error("Unable to compress PNG index tile");
    compressed.resize(compressedBytes);
    return compressed;
}

void writeTile(
    FILE* output,
    Entry& entry,
    const std::vector<uint8_t>& raw,
    uint32_t channels
) {
    if (raw.size() != entry.rawBytes) throw std::runtime_error("PNG tile size mismatch");
    const std::vector<uint8_t> filtered = filterTile(raw, entry.width, entry.height, channels);
    const std::vector<uint8_t> compressed = compressFiltered(filtered);
    const size_t compressedSize = compressed.size();
    const off_t offset = ftello(output);
    if (offset < 0 || static_cast<uint64_t>(offset) > std::numeric_limits<uint64_t>::max() - compressedSize) {
        throw std::runtime_error("PNG index offset overflow");
    }
    if (!fwriteAll(output, compressed.data(), static_cast<size_t>(compressedSize))) {
        throw std::runtime_error("Unable to write PNG index tile");
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
    uint32_t bandHeight,
    uint32_t channels
) {
    const size_t sourceStride = static_cast<size_t>(level.width) * channels;
    const uint32_t tileY = bandTop / kTileSize;
    for (uint32_t tileX = 0; tileX < level.columns; ++tileX) {
        Entry& entry = entries[level.firstEntry + static_cast<size_t>(tileY) * level.columns + tileX];
        if (entry.height != bandHeight) throw std::runtime_error("PNG band height mismatch");
        std::vector<uint8_t> tile(entry.rawBytes);
        const size_t tileStride = static_cast<size_t>(entry.width) * channels;
        for (uint32_t row = 0; row < bandHeight; ++row) {
            memcpy(
                tile.data() + static_cast<size_t>(row) * tileStride,
                band + static_cast<size_t>(row) * sourceStride + static_cast<size_t>(entry.x) * channels,
                tileStride
            );
        }
        writeTile(output, entry, tile, channels);
    }
}

std::vector<uint8_t> loadTileRaw(int fd, const Entry& entry, uint32_t channels) {
    std::vector<uint8_t> compressed(entry.compressedBytes);
    if (!preadAll(fd, compressed.data(), compressed.size(), entry.offset)) {
        throw std::runtime_error("Unable to read PNG index tile");
    }
    const size_t filteredBytes = entry.rawBytes + entry.height;
    std::vector<uint8_t> filtered(filteredBytes);
    uLongf decodedBytes = filtered.size();
    const int result = uncompress(filtered.data(), &decodedBytes, compressed.data(), compressed.size());
    if (result != Z_OK || decodedBytes != filtered.size()) {
        throw std::runtime_error("Unable to inflate PNG index tile");
    }
    std::vector<uint8_t> raw = unfilterTile(filtered, entry.width, entry.height, channels);
    const uint32_t actualCrc = crc32(0, raw.data(), static_cast<uInt>(raw.size()));
    if (actualCrc != entry.crc) throw std::runtime_error("PNG index tile checksum mismatch");
    return raw;
}

const uint8_t* pixelAt(
    const Level& level,
    const std::vector<Entry>& entries,
    int fd,
    std::unordered_map<size_t, std::vector<uint8_t>>& cache,
    uint32_t x,
    uint32_t y,
    uint32_t channels
) {
    if (x >= level.width || y >= level.height) return nullptr;
    const uint32_t tileX = x / kTileSize;
    const uint32_t tileY = y / kTileSize;
    const size_t index = level.firstEntry + static_cast<size_t>(tileY) * level.columns + tileX;
    auto found = cache.find(index);
    if (found == cache.end()) {
        found = cache.emplace(index, loadTileRaw(fd, entries[index], channels)).first;
    }
    const Entry& entry = entries[index];
    const uint32_t localX = x - entry.x;
    const uint32_t localY = y - entry.y;
    return found->second.data() + (static_cast<size_t>(localY) * entry.width + localX) * channels;
}

void generateLowerLevels(
    FILE* output,
    const std::vector<Level>& levels,
    std::vector<Entry>& entries,
    uint32_t channels
) {
    const int fd = fileno(output);
    for (size_t levelIndex = 1; levelIndex < levels.size(); ++levelIndex) {
        if (fflush(output) != 0) throw std::runtime_error("Unable to flush PNG index level");
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
                                    sourceY + dy,
                                    channels
                                );
                                if (pixel == nullptr) continue;
                                for (size_t channel = 0; channel < channels; ++channel) sums[channel] += pixel[channel];
                                ++count;
                            }
                        }
                        if (count == 0) throw std::runtime_error("PNG pyramid source pixel is missing");
                        uint8_t* destinationPixel = outputTile.data() +
                            (static_cast<size_t>(y) * outputEntry.width + x) * channels;
                        for (size_t channel = 0; channel < channels; ++channel) {
                            destinationPixel[channel] = static_cast<uint8_t>((sums[channel] + count / 2) / count);
                        }
                    }
                }
                writeTile(output, outputEntry, outputTile, channels);
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
    putU32(bytes, 80, header.channels);
    putU32(bytes, 84, header.encoding);

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
        throw std::runtime_error("Unable to publish PNG index directory");
    }
    if (fflush(output) != 0 || fsync(fileno(output)) != 0) {
        throw std::runtime_error("Unable to sync PNG index");
    }
}

void decodeBaseLevel(
    const std::string& sourcePath,
    const std::string& rawTemporaryPath,
    FILE* output,
    const Level& level,
    std::vector<Entry>& entries,
    spng_ihdr* decodedHeader,
    uint32_t channels
) {
    std::unique_ptr<FILE, decltype(&fclose)> source(fopen(sourcePath.c_str(), "rb"), fclose);
    if (!source) throw std::runtime_error("Unable to open the PNG source");
    std::unique_ptr<spng_ctx, decltype(&spng_ctx_free)> context(spng_ctx_new(0), spng_ctx_free);
    if (!context) throw std::runtime_error("Unable to allocate the PNG decoder");
    int result = spng_set_png_file(context.get(), source.get());
    if (result != 0) throw std::runtime_error(spng_strerror(result));
    result = spng_get_ihdr(context.get(), decodedHeader);
    if (result != 0) throw std::runtime_error(spng_strerror(result));
    result = spng_decode_image(
        context.get(),
        nullptr,
        0,
        channels == kRgbChannels ? SPNG_FMT_RGB8 : SPNG_FMT_RGBA8,
        SPNG_DECODE_TRNS | SPNG_DECODE_PROGRESSIVE
    );
    if (result != 0) throw std::runtime_error(spng_strerror(result));

    const uint64_t rowBytes64 = static_cast<uint64_t>(level.width) * channels;
    if (rowBytes64 > std::numeric_limits<size_t>::max()) {
        throw std::runtime_error("PNG row is too large for this ABI");
    }
    const size_t rowBytes = static_cast<size_t>(rowBytes64);

    if (decodedHeader->interlace_method == SPNG_INTERLACE_NONE) {
        const uint32_t bandCapacity = std::min(kTileSize, level.height);
        uint64_t bandBytes64 = 0;
        if (mulOverflow(rowBytes64, bandCapacity, &bandBytes64) ||
            bandBytes64 > std::numeric_limits<size_t>::max()) {
            throw std::runtime_error("PNG decode band is too large for this ABI");
        }
        std::vector<uint8_t> band(static_cast<size_t>(bandBytes64));
        uint32_t bandTop = 0;
        uint32_t bandRows = 0;
        for (uint32_t expectedRow = 0; expectedRow < level.height; ++expectedRow) {
            spng_row_info rowInfo{};
            result = spng_get_row_info(context.get(), &rowInfo);
            if (result != 0 || rowInfo.row_num != expectedRow) {
                throw std::runtime_error("Unexpected PNG scanline order");
            }
            uint8_t* row = band.data() + static_cast<size_t>(bandRows) * rowBytes;
            result = spng_decode_row(context.get(), row, rowBytes);
            if (result != 0 && result != SPNG_EOI) throw std::runtime_error(spng_strerror(result));
            if (channels == kRgbaChannels) premultiplyRow(row, level.width);
            ++bandRows;
            if (bandRows == kTileSize || expectedRow + 1 == level.height) {
                writeBand(output, level, entries, band.data(), bandTop, bandRows, channels);
                bandTop += bandRows;
                bandRows = 0;
            }
        }
        return;
    }

    std::unique_ptr<FILE, decltype(&fclose)> rows(fopen(rawTemporaryPath.c_str(), "w+b"), fclose);
    if (!rows) throw std::runtime_error("Unable to create interlaced PNG row storage");
    uint64_t rawBytes = 0;
    if (mulOverflow(rowBytes64, level.height, &rawBytes) ||
        rawBytes > static_cast<uint64_t>(std::numeric_limits<off_t>::max()) ||
        ftruncate(fileno(rows.get()), static_cast<off_t>(rawBytes)) != 0) {
        throw std::runtime_error("Unable to allocate interlaced PNG row storage");
    }
    std::vector<uint8_t> row(rowBytes);
    while (true) {
        spng_row_info rowInfo{};
        result = spng_get_row_info(context.get(), &rowInfo);
        if (result == SPNG_EOI) break;
        if (result != 0 || rowInfo.row_num >= level.height) {
            throw std::runtime_error(result == 0 ? "Invalid interlaced PNG row" : spng_strerror(result));
        }
        const uint64_t rowOffset = static_cast<uint64_t>(rowInfo.row_num) * rowBytes;
        if (!preadAll(fileno(rows.get()), row.data(), row.size(), rowOffset)) {
            throw std::runtime_error("Unable to read interlaced PNG row storage");
        }
        result = spng_decode_row(context.get(), row.data(), row.size());
        if (result != 0 && result != SPNG_EOI) throw std::runtime_error(spng_strerror(result));
        if (!pwriteAll(fileno(rows.get()), row.data(), row.size(), rowOffset)) {
            throw std::runtime_error("Unable to update interlaced PNG row storage");
        }
        if (result == SPNG_EOI) break;
    }

    const uint32_t bandCapacity = std::min(kTileSize, level.height);
    std::vector<uint8_t> band(static_cast<size_t>(rowBytes64 * bandCapacity));
    for (uint32_t bandTop = 0; bandTop < level.height; bandTop += kTileSize) {
        const uint32_t bandRows = std::min(kTileSize, level.height - bandTop);
        const size_t bandBytes = static_cast<size_t>(rowBytes64 * bandRows);
        if (!preadAll(
                fileno(rows.get()),
                band.data(),
                bandBytes,
                static_cast<uint64_t>(bandTop) * rowBytes
            )) {
            throw std::runtime_error("Unable to read completed interlaced PNG rows");
        }
        for (uint32_t rowIndex = 0; rowIndex < bandRows; ++rowIndex) {
            if (channels == kRgbaChannels) {
                premultiplyRow(band.data() + static_cast<size_t>(rowIndex) * rowBytes, level.width);
            }
        }
        writeBand(output, level, entries, band.data(), bandTop, bandRows, channels);
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
    header.channels = getU32(bytes, 80);
    header.encoding = getU32(bytes, 84);
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
        (header.channels != kRgbChannels && header.channels != kRgbaChannels) ||
        header.encoding != kFilteredZlibEncoding ||
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

    const std::vector<Entry> expectedMetadata = makeEntries(levels, header.channels);
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

std::array<jint, 4> buildIndex(
    const std::string& sourcePath,
    const std::string& destinationPath,
    uint64_t sourceBytes,
    int64_t sourceModifiedMillis
) {
    std::unique_ptr<FILE, decltype(&fclose)> probe(fopen(sourcePath.c_str(), "rb"), fclose);
    if (!probe) throw std::runtime_error("Unable to open the PNG source");
    std::unique_ptr<spng_ctx, decltype(&spng_ctx_free)> probeContext(spng_ctx_new(0), spng_ctx_free);
    if (!probeContext) throw std::runtime_error("Unable to allocate the PNG decoder");
    int result = spng_set_png_file(probeContext.get(), probe.get());
    if (result != 0) throw std::runtime_error(spng_strerror(result));
    spng_ihdr imageHeader{};
    result = spng_get_ihdr(probeContext.get(), &imageHeader);
    if (result != 0) throw std::runtime_error(spng_strerror(result));
    spng_trns transparency{};
    const int transparencyResult = spng_get_trns(probeContext.get(), &transparency);
    if (transparencyResult != 0 && transparencyResult != SPNG_ECHUNKAVAIL) {
        throw std::runtime_error(spng_strerror(transparencyResult));
    }
    const bool hasTransparency = imageHeader.color_type == SPNG_COLOR_TYPE_GRAYSCALE_ALPHA ||
        imageHeader.color_type == SPNG_COLOR_TYPE_TRUECOLOR_ALPHA ||
        transparencyResult == 0;
    const uint32_t channels = hasTransparency ? kRgbaChannels : kRgbChannels;

    std::vector<Level> levels = makeLevels(imageHeader.width, imageHeader.height);
    std::vector<Entry> entries = makeEntries(levels, channels);
    Header header;
    header.sourceBytes = sourceBytes;
    header.sourceModifiedMillis = sourceModifiedMillis;
    header.width = imageHeader.width;
    header.height = imageHeader.height;
    header.tileSize = kTileSize;
    header.levelCount = static_cast<uint32_t>(levels.size());
    header.channels = channels;
    header.encoding = kFilteredZlibEncoding;
    header.entryCount = entries.size();
    header.directoryOffset = kHeaderBytes;
    header.payloadOffset = kHeaderBytes + static_cast<uint64_t>(entries.size()) * kEntryBytes;

    std::unique_ptr<FILE, decltype(&fclose)> output(fopen(destinationPath.c_str(), "w+b"), fclose);
    if (!output) throw std::runtime_error("Unable to create the PNG index");
    if (fseeko(output.get(), static_cast<off_t>(header.payloadOffset), SEEK_SET) != 0) {
        throw std::runtime_error("Unable to reserve the PNG index directory");
    }

    const std::string rowTemporaryPath = destinationPath + ".rows";
    try {
        spng_ihdr decodedHeader{};
        decodeBaseLevel(
            sourcePath,
            rowTemporaryPath,
            output.get(),
            levels.front(),
            entries,
            &decodedHeader,
            channels
        );
        if (decodedHeader.width != imageHeader.width || decodedHeader.height != imageHeader.height) {
            throw std::runtime_error("PNG dimensions changed during index creation");
        }
        generateLowerLevels(output.get(), levels, entries, channels);
        const off_t end = ftello(output.get());
        if (end < 0) throw std::runtime_error("Unable to determine PNG index size");
        header.totalBytes = static_cast<uint64_t>(end);
        writeHeaderAndDirectory(output.get(), header, entries);
        unlink(rowTemporaryPath.c_str());
    } catch (...) {
        unlink(rowTemporaryPath.c_str());
        throw;
    }

    return {
        static_cast<jint>(header.width),
        static_cast<jint>(header.height),
        static_cast<jint>(header.levelCount),
        static_cast<jint>(header.entryCount),
    };
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_indexedpng_IndexedPngNative_buildIndex(
    JNIEnv* env,
    jobject,
    jstring sourcePathValue,
    jstring destinationPathValue,
    jlong sourceBytes,
    jlong sourceModifiedMillis
) {
    JString sourcePath(env, sourcePathValue);
    JString destinationPath(env, destinationPathValue);
    if (!sourcePath.valid() || !destinationPath.valid()) return nullptr;
    try {
        const auto info = buildIndex(
            sourcePath.c_str(),
            destinationPath.c_str(),
            static_cast<uint64_t>(sourceBytes),
            static_cast<int64_t>(sourceModifiedMillis)
        );
        jintArray result = env->NewIntArray(info.size());
        if (result != nullptr) env->SetIntArrayRegion(result, 0, info.size(), info.data());
        return result;
    } catch (const std::exception& error) {
        logError("build", error.what());
        throwIOException(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_indexedpng_IndexedPngNative_validateIndex(
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
Java_io_github_indexedpng_IndexedPngNative_open(
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
Java_io_github_indexedpng_IndexedPngNative_decode(
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
                        *selected, decoder->entries, decoder->fd, cache, levelX, levelY,
                        decoder->header.channels
                    );
                    if (sourcePixel == nullptr) throw std::runtime_error("PNG index pixel is missing");
                    uint32_t run = std::min(expectedWidth - x, kTileSize - levelX % kTileSize);
                    run = std::min(run, selected->width - levelX);
                    uint8_t* destination = outputRow + static_cast<size_t>(x) * 4;
                    if (decoder->header.channels == kRgbaChannels) {
                        memcpy(destination, sourcePixel, static_cast<size_t>(run) * 4);
                    } else {
                        for (uint32_t pixel = 0; pixel < run; ++pixel) {
                            destination[pixel * 4] = sourcePixel[pixel * 3];
                            destination[pixel * 4 + 1] = sourcePixel[pixel * 3 + 1];
                            destination[pixel * 4 + 2] = sourcePixel[pixel * 3 + 2];
                            destination[pixel * 4 + 3] = 255;
                        }
                    }
                    x += run;
                }
            } else {
                for (uint32_t x = 0; x < expectedWidth; ++x) {
                    const uint32_t sourceX = static_cast<uint32_t>(left) + x * sampleSize;
                    const uint32_t levelX = sourceX / selected->sample;
                    const uint8_t* sourcePixel = pixelAt(
                        *selected, decoder->entries, decoder->fd, cache, levelX, levelY,
                        decoder->header.channels
                    );
                    if (sourcePixel == nullptr) throw std::runtime_error("PNG index pixel is missing");
                    uint8_t* destination = outputRow + static_cast<size_t>(x) * 4;
                    destination[0] = sourcePixel[0];
                    destination[1] = sourcePixel[1];
                    destination[2] = sourcePixel[2];
                    destination[3] = decoder->header.channels == kRgbaChannels ? sourcePixel[3] : 255;
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
Java_io_github_indexedpng_IndexedPngNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Decoder*>(handle);
}
