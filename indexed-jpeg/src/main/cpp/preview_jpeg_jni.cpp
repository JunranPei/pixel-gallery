#include <android/bitmap.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <csetjmp>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstring>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#include <time.h>
extern "C" {
#include "jpeglib.h"
#include "jdpreview.h"
}

namespace {
// Kept on the heap across libjpeg's longjmp. No C++ resources are unwound past.
struct Decode {
    jpeg_decompress_struct info{};
    jpeg_error_mgr error{};
    jmp_buf jump{};
    FILE* file = nullptr;
    unsigned char* rgb = nullptr;
    JNIEnv* env = nullptr;
    jobject cancel = nullptr;
    jmethodID getCancelled = nullptr;
    unsigned width = 0, height = 0;
    bool success = false;
    jobject fitBitmap = nullptr;
    void* fitPixels = nullptr;
    AndroidBitmapInfo fitInfo{};
    bool fitLocked = false;
    unsigned char* scanRows = nullptr;
    uint32_t* scanX = nullptr;
};
thread_local jlong lastFitTimings[7]{};
static jlong clockNs() {
    timespec now{}; clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<jlong>(now.tv_sec) * 1000000000LL + now.tv_nsec;
}
static jobject newBitmap(JNIEnv* env, unsigned width, unsigned height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jobject bitmap = nullptr;
    if (bitmapClass && configClass) {
        auto configId = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        auto create = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        if (configId && create) {
            jobject config = env->GetStaticObjectField(configClass, configId);
            bitmap = env->CallStaticObjectMethod(bitmapClass, create, width, height, config);
            env->DeleteLocalRef(config);
        }
    }
    if (configClass) env->DeleteLocalRef(configClass);
    if (bitmapClass) env->DeleteLocalRef(bitmapClass);
    return bitmap;
}
void errorExit(j_common_ptr info) {
    auto* state = reinterpret_cast<Decode*>(info->client_data);
    longjmp(state->jump, 1);
}
void strictWarning(j_common_ptr info, int level) {
    if (level < 0) errorExit(info);
}
int shouldContinue(void* opaque) {
    auto* state = static_cast<Decode*>(opaque);
    if (state->env->ExceptionCheck()) return 0;
    const bool stopped = state->env->CallBooleanMethod(state->cancel, state->getCancelled);
    return !stopped && !state->env->ExceptionCheck();
}
}

// Resample while reading: only two JPEG output rows plus one x-coordinate map.
// Ownership lives in Decode so libjpeg's longjmp cannot leak these allocations.
static bool readFitRows(Decode* state) {
    const unsigned sw = state->info.output_width, sh = state->info.output_height;
    const unsigned dw = state->width, dh = state->height;
    const size_t rowBytes = (static_cast<size_t>(sw) + 1) * 4;
    state->scanRows = static_cast<unsigned char*>(malloc(rowBytes * 2));
    state->scanX = static_cast<uint32_t*>(malloc(static_cast<size_t>(dw) * sizeof(uint32_t)));
    if (!state->scanRows || !state->scanX) return false;
    // Match SkMatrix's float inverse and the raster sampler's 32.32 coordinate walk.
    const float inverseX = 1.f / (static_cast<float>(dw) / sw);
    const float inverseY = 1.f / (static_cast<float>(dh) / sh);
    const int64_t stepX = static_cast<int64_t>(static_cast<double>(inverseX) * 4294967296.0);
    const int64_t startX = static_cast<int64_t>(static_cast<double>(.5f * inverseX) * 4294967296.0) - 2147483648LL;
    for (unsigned x = 0; x < dw; ++x) {
        const int64_t position = startX + x * stepX;
        int left = static_cast<int>(position >> 32);
        // Match Android's raster bilinear sampler: four fractional coordinate bits.
        unsigned weight = ((position >> 28) & 15) * 16;
        if (left < 0) { left = 0; weight = 0; }
        if (left >= static_cast<int>(sw) - 1) { left = sw - 1; weight = 0; }
        state->scanX[x] = (static_cast<uint32_t>(left) << 8) | weight;
    }
    for (unsigned y = 0; y < dh; ++y) {
        if (!shouldContinue(state)) return false;
        const float mappedY = (static_cast<float>(y) + .5f) * inverseY;
        const int64_t position = static_cast<int64_t>(static_cast<double>(mappedY) * 4294967296.0) - 2147483648LL;
        int top = static_cast<int>(position >> 32);
        unsigned wy = ((position >> 28) & 15) * 16;
        if (top < 0) { top = 0; wy = 0; }
        if (top >= static_cast<int>(sh) - 1) { top = sh - 1; wy = 0; }
        const unsigned bottom = std::min(static_cast<unsigned>(top) + 1, sh - 1);
        while (state->info.output_scanline <= bottom) {
            auto* row = state->scanRows + (state->info.output_scanline % 2) * rowBytes;
            JSAMPROW rows[1] = {row};
            if (jpeg_read_scanlines(&state->info, rows, 1) != 1) return false;
            memcpy(row + sw * 4, row + (sw - 1) * 4, 4);
        }
        const auto* a = state->scanRows + (top % 2) * rowBytes;
        const auto* b = state->scanRows + (bottom % 2) * rowBytes;
        auto* dst = static_cast<unsigned char*>(state->fitPixels) + y * state->fitInfo.stride;
        for (unsigned x = 0; x < dw; ++x) {
            const unsigned sx = state->scanX[x] >> 8, wx = state->scanX[x] & 255;
#if defined(__aarch64__)
            const uint16x8_t av = vmovl_u8(vld1_u8(a + sx * 4));
            const uint16x8_t bv = vmovl_u8(vld1_u8(b + sx * 4));
            const uint16x4_t ah = vmla_n_u16(vmul_n_u16(vget_low_u16(av), 256 - wx), vget_high_u16(av), wx);
            const uint16x4_t bh = vmla_n_u16(vmul_n_u16(vget_low_u16(bv), 256 - wx), vget_high_u16(bv), wx);
            uint32x4_t sum = vmlal_n_u16(vmull_n_u16(ah, 256 - wy), bh, wy);
            const uint16x4_t out = vshrn_n_u32(vaddq_u32(sum, vdupq_n_u32(32768)), 16);
            const uint8x8_t packed = vmovn_u16(vcombine_u16(out, vdup_n_u16(0)));
            const uint32_t pixel = vget_lane_u32(vreinterpret_u32_u8(packed), 0);
            memcpy(dst + x * 4, &pixel, 4);
#else
            for (unsigned c = 0; c < 4; ++c) {
                const unsigned ah = a[sx * 4 + c] * (256 - wx) + a[(sx + 1) * 4 + c] * wx;
                const unsigned bh = b[sx * 4 + c] * (256 - wx) + b[(sx + 1) * 4 + c] * wx;
                dst[x * 4 + c] = (ah * (256 - wy) + bh * wy + 32768) >> 16;
            }
#endif
        }
    }
    while (state->info.output_scanline < sh) {
        if (!shouldContinue(state)) return false;
        JSAMPROW rows[1] = {state->scanRows};
        if (jpeg_read_scanlines(&state->info, rows, 1) != 1) return false;
    }
    return true;
}

static jobject decodeImpl(JNIEnv* env, jint fd, jint minimumLongEdge,
                          jint fitWidth, jint fitHeight, jobject cancelled, bool exactFit = false) {
    const jlong totalStart = clockNs();
    if (fitWidth > 0) std::fill_n(lastFitTimings, 7, 0);
    // This experimental entry requires a seekable, whole, regular JPEG file.
    // Pipes/virtual assets are left to the caller's existing decoder.
    struct stat st{};
    if (fd < 0 || minimumLongEdge < 1 || !cancelled || fstat(fd, &st) || !S_ISREG(st.st_mode))
        return nullptr;
    auto* state = new Decode;
    state->env = env;
    state->cancel = cancelled;
    jclass atomicClass = env->GetObjectClass(cancelled);
    state->getCancelled = env->GetMethodID(atomicClass, "get", "()Z");
    env->DeleteLocalRef(atomicClass);
    if (!state->getCancelled || !shouldContinue(state)) { delete state; return nullptr; }
    const int ownedFd = dup(fd);
    if (ownedFd < 0) { delete state; return nullptr; }
    state->file = fdopen(ownedFd, "rb");
    if (!state->file) { close(ownedFd); delete state; return nullptr; }
    setvbuf(state->file, nullptr, _IOFBF, 65536);
    state->info.err = jpeg_std_error(&state->error);
    state->error.error_exit = errorExit;
    state->error.emit_message = strictWarning;
    state->info.client_data = state;
    if (!setjmp(state->jump)) {
        jpeg_create_decompress(&state->info);
        // Only the descriptor opened for this call is used by the Kotlin API.
        if (fseeko(state->file, 0, SEEK_SET) == 0) {
            jpeg_stdio_src(&state->info, state->file);
            jpeg_read_header(&state->info, TRUE);
            if (fitWidth > 0) lastFitTimings[0] = clockNs() - totalStart;
            const auto edge = std::max(state->info.image_width, state->info.image_height);
            unsigned sample = (edge + 31) / 32 >= static_cast<unsigned>(minimumLongEdge) ? 32 : 16;
            if (fitWidth > 0 && fitHeight > 0) {
                // Select the coarsest standard scale that covers the fit rectangle.
                // Check support BEFORE start_decompress (progressive startup could
                // otherwise allocate a coefficient image for a huge input).
                const double scale = std::min({1.0,
                    static_cast<double>(fitWidth) / state->info.image_width,
                    static_cast<double>(fitHeight) / state->info.image_height});
                if (state->info.data_precision == 8 &&
                    !state->info.progressive_mode && !state->info.arith_code &&
                    state->info.comps_in_scan == state->info.num_components &&
                    state->info.Ss == 0 && state->info.Se == 63 &&
                    state->info.Ah == 0 && state->info.Al == 0 &&
                    (state->info.jpeg_color_space == JCS_GRAYSCALE ||
                     state->info.jpeg_color_space == JCS_RGB || state->info.jpeg_color_space == JCS_YCbCr)) {
                    sample = 8;
                    while (sample > 1 && 1.0 / sample < scale) sample /= 2;
                    state->info.scale_num = 1; state->info.scale_denom = sample;
                    state->info.out_color_space = JCS_EXT_RGBA;
                    jpeg_calc_output_dimensions(&state->info);
                    state->width = state->info.output_width;
                    state->height = state->info.output_height;
                    if (exactFit) {
                        const float outScale = std::min({1.f, static_cast<float>(fitWidth) / state->width,
                            static_cast<float>(fitHeight) / state->height});
                        state->width = std::max(1L, std::lround(state->width * outScale));
                        state->height = std::max(1L, std::lround(state->height * outScale));
                    }
                    const bool resizeRows = state->width != state->info.output_width || state->height != state->info.output_height;
                    const size_t pixels = static_cast<size_t>(state->width) * state->height;
                    if (pixels <= 32u * 1024u * 1024u / 4) {
                        const jlong allocationStart = clockNs();
                        state->fitBitmap = newBitmap(env, state->width, state->height);
                        if (state->fitBitmap && !env->ExceptionCheck() &&
                            AndroidBitmap_getInfo(env, state->fitBitmap, &state->fitInfo) == ANDROID_BITMAP_RESULT_SUCCESS &&
                            state->fitInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
                            AndroidBitmap_lockPixels(env, state->fitBitmap, &state->fitPixels) == ANDROID_BITMAP_RESULT_SUCCESS)
                            state->fitLocked = true;
                        lastFitTimings[1] = clockNs() - allocationStart;
                    }
                    if (state->fitLocked) {
                        // Preserve standard high-quality chroma interpolation at
                        // the larger scales (notably full-size / 4:1:1 input).
                        state->info.do_fancy_upsampling = sample == 8 ? FALSE : TRUE;
                        const jlong startAt = clockNs();
                        if (!resizeRows && sample == 8 && jpeg_dc_rgba8_supported(&state->info)) {
                            lastFitTimings[2] = clockNs() - startAt;
                            lastFitTimings[5] = sample; lastFitTimings[6] = 2;
                            const jlong rowsStart = clockNs();
                            state->success = jpeg_read_dc_rgba8(&state->info,
                                static_cast<unsigned char*>(state->fitPixels), state->fitInfo.stride,
                                shouldContinue, state) == JPEG_DC_OK;
                            lastFitTimings[3] = clockNs() - rowsStart;
                        } else if (jpeg_start_decompress(&state->info)) {
                            const bool fast = sample == 8 && jpeg_dc_enable_fast_scanlines(&state->info);
                            lastFitTimings[2] = clockNs() - startAt;
                            lastFitTimings[6] = fast ? 1 : 0;
                            const jlong rowsStart = clockNs();
                            bool rowsOk = true;
                            if (resizeRows) rowsOk = readFitRows(state);
                            while (!resizeRows && state->info.output_scanline < state->info.output_height && shouldContinue(state)) {
                                JSAMPROW rows[8];
                                unsigned count = std::min(8u, state->height - state->info.output_scanline);
                                for (unsigned row = 0; row < count; ++row)
                                    rows[row] = static_cast<unsigned char*>(state->fitPixels) +
                                        static_cast<size_t>(state->info.output_scanline + row) * state->fitInfo.stride;
                                if (!jpeg_read_scanlines(&state->info, rows, count)) break;
                            }
                            lastFitTimings[3] = clockNs() - rowsStart;
                            const jlong finishAt = clockNs();
                            if (rowsOk && state->info.output_scanline == state->info.output_height && shouldContinue(state))
                                state->success = jpeg_finish_decompress(&state->info);
                            lastFitTimings[4] = clockNs() - finishAt;
                        }
                        lastFitTimings[5] = sample;
                    }
                }
            } else if ((edge + sample - 1) / sample >= static_cast<unsigned>(minimumLongEdge) &&
                jpeg_dc_preview_dimensions(&state->info, sample, &state->width, &state->height)) {
                const size_t bytes = static_cast<size_t>(state->width) * state->height * 3;
                // Bound the experimental allocation independently of source metadata.
                if (bytes <= 32u * 1024u * 1024u) state->rgb = static_cast<unsigned char*>(malloc(bytes));
                if (state->rgb) state->success = jpeg_read_dc_preview(
                    &state->info, sample, state->rgb, state->width * 3, shouldContinue, state) == JPEG_DC_OK;
            }
        }
    }
    jpeg_destroy_decompress(&state->info);
    fclose(state->file);
    if (state->fitLocked) AndroidBitmap_unlockPixels(env, state->fitBitmap);
    jobject bitmap = nullptr;
    if (fitWidth > 0) {
        if (state->success && shouldContinue(state)) bitmap = state->fitBitmap;
        else if (state->fitBitmap) {
            if (!env->ExceptionCheck()) {
                jclass type = env->GetObjectClass(state->fitBitmap);
                jmethodID recycle = env->GetMethodID(type, "recycle", "()V");
                if (recycle) env->CallVoidMethod(state->fitBitmap, recycle);
                env->DeleteLocalRef(type);
            }
            env->DeleteLocalRef(state->fitBitmap);
        }
    } else if (state->success && shouldContinue(state)) {
        jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
        jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
        if (bitmapClass && configClass) {
            auto configId = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
            auto create = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
            if (configId && create) {
                jobject config = env->GetStaticObjectField(configClass, configId);
                bitmap = env->CallStaticObjectMethod(bitmapClass, create, state->width, state->height, config);
                env->DeleteLocalRef(config);
                AndroidBitmapInfo info{};
                void* pixels = nullptr;
                if (bitmap && !env->ExceptionCheck() &&
                    AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
                    AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
                    for (unsigned y = 0; y < state->height; ++y) {
                        auto* dst = static_cast<unsigned char*>(pixels) + y * info.stride;
                        const auto* src = state->rgb + y * state->width * 3;
                        for (unsigned x = 0; x < state->width; ++x) {
                            dst[x * 4] = src[x * 3]; dst[x * 4 + 1] = src[x * 3 + 1];
                            dst[x * 4 + 2] = src[x * 3 + 2]; dst[x * 4 + 3] = 255;
                        }
                    }
                    AndroidBitmap_unlockPixels(env, bitmap);
                } else if (bitmap) {
                    env->DeleteLocalRef(bitmap); bitmap = nullptr;
                }
            }
        }
        if (configClass) env->DeleteLocalRef(configClass);
        if (bitmapClass) env->DeleteLocalRef(bitmapClass);
    }
    free(state->scanRows); free(state->scanX); free(state->rgb); delete state;
    return bitmap;
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_indexedjpeg_DcJpegPreview_decodeNative(
    JNIEnv* env, jobject, jint fd, jint minimumLongEdge, jobject cancelled) {
    return decodeImpl(env, fd, minimumLongEdge, 0, 0, cancelled);
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_indexedjpeg_DcJpegPreview_decodeFitNative(
    JNIEnv* env, jobject, jint fd, jint width, jint height, jobject cancelled) {
    if (width <= 0 || height <= 0) return nullptr;
    return decodeImpl(env, fd, 1, width, height, cancelled);
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_indexedjpeg_DcJpegPreview_decodeFitExactNative(
    JNIEnv* env, jobject, jint fd, jint width, jint height, jobject cancelled) {
    if (width <= 0 || height <= 0) return nullptr;
    return decodeImpl(env, fd, 1, width, height, cancelled, true);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_indexedjpeg_DcJpegPreview_lastFitTimingsNative(JNIEnv* env, jobject) {
    jlongArray result = env->NewLongArray(7);
    if (result) env->SetLongArrayRegion(result, 0, 7, lastFitTimings);
    return result;
}
