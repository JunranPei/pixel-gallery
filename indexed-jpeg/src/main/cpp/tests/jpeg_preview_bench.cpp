// Isolated device test tool. Inputs are always read-only; output is optional PPM.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <csetjmp>
#include <cstdint>
#include <string>
#include <vector>
#include <time.h>
#include <sys/resource.h>
#ifdef PIXEL_NDK_COMPARE
#include <android/imagedecoder.h>
#include <android/bitmap.h>
#include <fcntl.h>
#include <unistd.h>
#endif
extern "C" {
#include "jpeglib.h"
#include "jerror.h"
#include "jdpreview.h"
}

struct Error { jpeg_error_mgr pub; jmp_buf jump; char message[JMSG_LENGTH_MAX]; };
static void fail(j_common_ptr c) {
  auto *e = reinterpret_cast<Error *>(c->err);
  e->pub.format_message(c, e->message);
  longjmp(e->jump, 1);
}
static void warning(j_common_ptr c, int level) {
  if (level < 0) fail(c); // Truncation must not be repaired with fake EOI.
}
static double millis(clockid_t id) {
  timespec ts{}; clock_gettime(id, &ts); return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}
struct Result {
  std::vector<unsigned char> rgb;
  unsigned w = 0, h = 0, sourceW = 0, sourceH = 0, nativeW = 0, nativeH = 0;
  int status = -1, checks = 0, space = 0;
  double wall = 0, cpu = 0, header = 0;
  long readPosition = 0;
  std::string message;
};
struct Cancel { int count = 0; int after = -1; };
static int proceed(void *ctx) {
  auto *c = static_cast<Cancel *>(ctx); ++c->count;
  return c->after < 0 || c->count <= c->after;
}

// heap state is used across libjpeg longjmp; no skipped C++ destructors.
struct State {
  jpeg_decompress_struct c{};
  Error e{};
  FILE *file = nullptr;
  std::vector<unsigned char> row;
  std::vector<uint64_t> sums, weights;
  Cancel cancel;
};
static Result decode(const char *path, const std::vector<unsigned char> *memory,
                     unsigned sample, bool direct, int cancelAfter = -1,
                     bool fastScanlines = false, bool rgba = false) {
  auto *result = new Result;
  Result &out = *result;
  auto *s = new State;
  s->cancel.after = cancelAfter;
  s->c.err = jpeg_std_error(&s->e.pub);
  s->e.pub.error_exit = fail;
  s->e.pub.emit_message = warning;
  const double start = millis(CLOCK_MONOTONIC), cpu = millis(CLOCK_THREAD_CPUTIME_ID);
  if (setjmp(s->e.jump)) {
    out.message = s->e.message; out.status = -1;
  } else {
    jpeg_create_decompress(&s->c);
    // This old Turbo declaration predates const input; the source only reads.
    if (memory) jpeg_mem_src(&s->c, const_cast<unsigned char *>(memory->data()), memory->size());
    else {
      s->file = fopen(path, "rb");
      if (!s->file) {
        jpeg_destroy_decompress(&s->c); delete s; delete result;
        Result failed; failed.message = "open failed"; return failed;
      }
      setvbuf(s->file, nullptr, _IOFBF, 65536);
      jpeg_stdio_src(&s->c, s->file);
    }
    jpeg_read_header(&s->c, TRUE);
    out.header = millis(CLOCK_MONOTONIC) - start;
    out.sourceW = s->c.image_width; out.sourceH = s->c.image_height;
    out.space = s->c.jpeg_color_space;
    out.w = (out.sourceW + sample - 1) / sample;
    out.h = (out.sourceH + sample - 1) / sample;
    if (static_cast<size_t>(out.w) * out.h * 3 > 32u * 1024u * 1024u)
      ERREXIT(&s->c, JERR_BAD_BUFFER_MODE);
    out.rgb.resize(static_cast<size_t>(out.w) * out.h * 3);
    if (direct) {
      out.status = jpeg_read_dc_preview(&s->c, sample, out.rgb.data(), out.w * 3,
                                       proceed, &s->cancel);
      out.nativeW = out.w; out.nativeH = out.h;
    } else if (rgba && fastScanlines && sample == 8 && jpeg_dc_rgba8_supported(&s->c)) {
      s->row.resize(static_cast<size_t>(out.w) * out.h * 4);
      out.status = jpeg_read_dc_rgba8(&s->c, s->row.data(), out.w * 4, proceed, &s->cancel);
      out.nativeW = out.w; out.nativeH = out.h;
      if (out.status == 0) for (size_t pixel = 0; pixel < static_cast<size_t>(out.w) * out.h; pixel++) {
        if (s->row[pixel * 4 + 3] != 255) ERREXIT(&s->c, JERR_BAD_BUFFER_MODE);
        for (unsigned channel = 0; channel < 3; channel++)
          out.rgb[pixel * 3 + channel] = s->row[pixel * 4 + channel];
      }
    } else {
      // Control: requesting 1/16 or 1/32 still produces 1/8 native output.
      s->c.scale_num = 1; s->c.scale_denom = sample;
      s->c.out_color_space = rgba ? JCS_EXT_RGBA : JCS_RGB;
      s->c.do_fancy_upsampling = FALSE;
      jpeg_start_decompress(&s->c);
      if (fastScanlines && sample >= 8 && !jpeg_dc_enable_fast_scanlines(&s->c)) {
        jpeg_abort_decompress(&s->c);
        out.status = JPEG_DC_UNSUPPORTED;
      } else {
      out.nativeW = s->c.output_width; out.nativeH = s->c.output_height;
      const unsigned factor = sample / 8;
      s->row.resize(out.nativeW * 3);
      s->sums.resize(out.w * 3); s->weights.resize(out.w);
      while (s->c.output_scanline < s->c.output_height) {
        if (fastScanlines && !proceed(&s->cancel)) break;
        const unsigned y = s->c.output_scanline;
        if (sample <= 8) {
          if (rgba) s->row.resize(static_cast<size_t>(out.w) * 4);
          JSAMPROW row = rgba ? s->row.data() : out.rgb.data() + static_cast<size_t>(y) * out.w * 3;
          jpeg_read_scanlines(&s->c, &row, 1);
          if (rgba) for (unsigned x = 0; x < out.w; ++x) {
            if (row[x * 4 + 3] != 255) ERREXIT(&s->c, JERR_BAD_BUFFER_MODE);
            for (unsigned channel = 0; channel < 3; ++channel)
              out.rgb[(static_cast<size_t>(y) * out.w + x) * 3 + channel] = row[x * 4 + channel];
          }
          continue;
        }
        if (y % factor == 0) {
          std::fill(s->sums.begin(), s->sums.end(), 0);
          std::fill(s->weights.begin(), s->weights.end(), 0);
        }
        JSAMPROW row = s->row.data(); jpeg_read_scanlines(&s->c, &row, 1);
        const unsigned wy = std::min(8u, out.sourceH - y * 8);
        for (unsigned x = 0; x < out.nativeW; ++x) {
          const unsigned ox = x / factor;
          const unsigned weight = std::min(8u, out.sourceW - x * 8) * wy;
          for (int c = 0; c < 3; ++c)
            s->sums[ox * 3 + c] += static_cast<uint64_t>(row[x * 3 + c]) * weight;
          s->weights[ox] += weight;
        }
        if ((y + 1) % factor == 0 || y + 1 == out.nativeH) {
          for (unsigned x = 0; x < out.w; ++x)
            for (int c = 0; c < 3; ++c)
              out.rgb[((y / factor) * out.w + x) * 3 + c] =
                  (s->sums[x * 3 + c] + s->weights[x] / 2) / s->weights[x];
        }
      }
      if (s->c.output_scanline == s->c.output_height) {
        jpeg_finish_decompress(&s->c);
        out.status = 0;
      } else {
        jpeg_abort_decompress(&s->c);
        out.status = JPEG_DC_CANCELLED;
      }
      }
    }
  }
  out.checks = s->cancel.count;
  if (s->file) { out.readPosition = ftell(s->file); fclose(s->file); }
  jpeg_destroy_decompress(&s->c);
  delete s;
  out.wall = millis(CLOCK_MONOTONIC) - start;
  out.cpu = millis(CLOCK_THREAD_CPUTIME_ID) - cpu;
  Result completed = std::move(out); delete result; return completed;
}

#ifdef PIXEL_NDK_COMPARE
static Result platform(const char *path, unsigned sample) {
  Result r;
  const double start = millis(CLOCK_MONOTONIC), cpu = millis(CLOCK_THREAD_CPUTIME_ID);
  const int fd = open(path, O_RDONLY);
  if (fd < 0) { r.message = "open failed"; return r; }
  AImageDecoder *decoder = nullptr;
  int status = AImageDecoder_createFromFd(fd, &decoder);
  if (status == ANDROID_IMAGE_DECODER_SUCCESS) {
    auto *header = AImageDecoder_getHeaderInfo(decoder);
    r.sourceW = AImageDecoderHeaderInfo_getWidth(header);
    r.sourceH = AImageDecoderHeaderInfo_getHeight(header);
    r.header = millis(CLOCK_MONOTONIC) - start;
    r.w = (r.sourceW + sample - 1) / sample; r.h = (r.sourceH + sample - 1) / sample;
    r.nativeW = r.w; r.nativeH = r.h;
    status = AImageDecoder_setTargetSize(decoder, r.w, r.h);
    if (!status) status = AImageDecoder_setAndroidBitmapFormat(decoder, ANDROID_BITMAP_FORMAT_RGBA_8888);
    // Default: no ICC conversion; compare decoder output in the source space.
    const size_t stride = AImageDecoder_getMinimumStride(decoder);
    std::vector<unsigned char> rgba(stride * r.h);
    if (!status) status = AImageDecoder_decodeImage(decoder, rgba.data(), stride, rgba.size());
    if (!status) {
      r.rgb.resize(r.w * r.h * 3);
      for (unsigned y = 0; y < r.h; ++y) for (unsigned x = 0; x < r.w; ++x)
        for (int ci = 0; ci < 3; ++ci)
          r.rgb[(y * r.w + x) * 3 + ci] = rgba[y * stride + x * 4 + ci];
    }
  }
  if (decoder) AImageDecoder_delete(decoder);
  r.readPosition = lseek(fd, 0, SEEK_CUR); close(fd);
  r.status = status;
  r.wall = millis(CLOCK_MONOTONIC) - start; r.cpu = millis(CLOCK_THREAD_CPUTIME_ID) - cpu;
  return r;
}
#endif

static std::vector<unsigned char> fixture(int w, int h, int components,
    int subsampling, bool progressive = false, bool restart = false,
    bool separated = false, int pattern = 0) {
  jpeg_compress_struct c{}; jpeg_error_mgr e{};
  c.err = jpeg_std_error(&e); jpeg_create_compress(&c);
  unsigned char *encoded = nullptr; unsigned long size = 0;
  jpeg_mem_dest(&c, &encoded, &size);
  c.image_width = w; c.image_height = h; c.input_components = components;
  c.in_color_space = components == 1 ? JCS_GRAYSCALE :
                     (components == 4 ? JCS_CMYK : JCS_RGB);
  jpeg_set_defaults(&c);
  if (subsampling == 3 && components == 3) jpeg_set_colorspace(&c, JCS_RGB);
  if (components == 3 && subsampling != 3) {
    c.comp_info[0].h_samp_factor = subsampling == 0 ? 1 : subsampling == 4 ? 4 : 2;
    c.comp_info[0].v_samp_factor = subsampling == 2 ? 2 : 1;
    for (int ci = 1; ci < 3; ++ci)
      c.comp_info[ci].h_samp_factor = c.comp_info[ci].v_samp_factor = 1;
  }
  if (progressive) jpeg_simple_progression(&c);
  jpeg_scan_info scans[3]{};
  if (separated) {
    for (int i = 0; i < 3; ++i) {
      scans[i].comps_in_scan = 1; scans[i].component_index[0] = i;
      scans[i].Ss = 0; scans[i].Se = 63;
    }
    c.scan_info = scans; c.num_scans = 3;
  }
  jpeg_set_quality(&c, 95, TRUE);
  if (restart) c.restart_interval = 3;
  jpeg_start_compress(&c, TRUE);
  std::vector<unsigned char> row(w * components);
  while (c.next_scanline < c.image_height) {
    unsigned y = c.next_scanline;
    for (int x = 0; x < w; ++x) for (int ci = 0; ci < components; ++ci) {
      if (pattern == 1) row[x * components + ci] = ci == 0 ? 230 : (ci == 1 ? 40 : 90);
      else if (pattern == 2) row[x * components + ci] = ((x / 16 + y / 16) % 2) ? 220 : 35;
      else if (pattern == 3) {
        uint32_t v = (x + 1) * 0x9e3779b9u ^ (y + 7) * 0x85ebca6bu ^ (ci + 3) * 0xc2b2ae35u;
        v ^= v >> 16; v *= 0x7feb352du; v ^= v >> 15;
        row[x * components + ci] = v & 255;
      } else row[x * components + ci] = 40 + (x * 7 + y * 3 + ci * 29) % 170;
    }
    JSAMPROW p = row.data(); jpeg_write_scanlines(&c, &p, 1);
  }
  jpeg_finish_compress(&c);
  std::vector<unsigned char> bytes(encoded, encoded + size);
  free(encoded); jpeg_destroy_compress(&c); return bytes;
}
static void require(bool condition, const char *message) {
  if (!condition) { fprintf(stderr, "FAIL %s\n", message); exit(2); }
}
static std::vector<unsigned char> coefficientFixture(int restart, bool optimize, int pattern, int sampling = -1) {
  auto seed = fixture(33, 25, sampling < 0 ? 1 : 3, sampling < 0 ? 0 : sampling);
  jpeg_decompress_struct source{}; jpeg_error_mgr readError{};
  source.err = jpeg_std_error(&readError); jpeg_create_decompress(&source);
  jpeg_mem_src(&source, seed.data(), seed.size()); jpeg_read_header(&source, TRUE);
  auto coefficients = jpeg_read_coefficients(&source);
  for (int ci = 0; ci < source.num_components; ci++)
  for (unsigned y = 0; y < source.comp_info[ci].height_in_blocks; ++y) {
    auto row = source.mem->access_virt_barray(reinterpret_cast<j_common_ptr>(&source), coefficients[ci], y, 1, TRUE);
    for (unsigned x = 0; x < source.comp_info[ci].width_in_blocks; ++x) {
      row[0][x][0] = 0;
      for (int k = 1; k < 64; ++k)
        row[0][x][k] = pattern == 0 ? (k == 63 ? 511 : 0) :
                       (pattern == 1 ? ((k & 1) ? 511 : -511) : 0);
    }
  }
  jpeg_compress_struct dest{}; jpeg_error_mgr writeError{};
  dest.err = jpeg_std_error(&writeError); jpeg_create_compress(&dest);
  unsigned char *encoded = nullptr; unsigned long size = 0;
  jpeg_mem_dest(&dest, &encoded, &size); jpeg_copy_critical_parameters(&source, &dest);
  dest.restart_interval = restart; dest.optimize_coding = optimize;
  jpeg_write_coefficients(&dest, coefficients); jpeg_finish_compress(&dest);
  std::vector<unsigned char> result(encoded, encoded + size);
  free(encoded); jpeg_destroy_compress(&dest);
  jpeg_finish_decompress(&source); jpeg_destroy_decompress(&source); return result;
}
static void selftest() {
  int count = 0;
  double worstMAE = 0, worstMax = 0;
  for (int comp : {1, 3}) for (int subsampling : {0, 1, 2, 3}) {
    if (comp == 1 && subsampling != 0) continue;
    for (auto dims : {std::array<int, 2>{1, 1}, {17, 19}, {257, 129}, {128, 256}})
      for (int pattern : {0, 1, 2, 3}) for (bool restart : {false, true}) {
        auto bytes = fixture(dims[0], dims[1], comp, subsampling, false, restart, false, pattern);
        for (unsigned sample : {16u, 32u}) {
          auto a = decode(nullptr, &bytes, sample, true);
          auto b = decode(nullptr, &bytes, sample, false);
          require(a.status == 0 && b.status == 0, "valid JPEG decodes");
          require(a.w == (dims[0] + sample - 1) / sample &&
                  a.h == (dims[1] + sample - 1) / sample, "ceil size");
          double sum = 0, max = 0;
          for (size_t i = 0; i < a.rgb.size(); ++i) {
            int delta = abs(static_cast<int>(a.rgb[i]) - b.rgb[i]);
            sum += delta; max = std::max(max, static_cast<double>(delta));
          }
          double mae = sum / a.rgb.size();
          worstMAE = std::max(worstMAE, mae); worstMax = std::max(worstMax, max);
          require(mae < 14 && max < 100, "pixel mean reference tolerance");
          if (pattern == 1) {
            require(abs(a.rgb[0] - (comp == 1 ? 230 : 230)) <= 4, "constant red/gray");
            if (comp == 3) require(abs(a.rgb[1] - 40) <= 4 && abs(a.rgb[2] - 90) <= 4,
                                   "constant color independently specified");
          }
          if (pattern == 2) for (unsigned y = 0; y < a.h; ++y)
            for (unsigned x = 0; x < a.w; ++x) {
              int sumGray = 0, weight = 0;
              for (unsigned sy = y * sample; sy < std::min((y + 1) * sample, unsigned(dims[1])); ++sy)
                for (unsigned sx = x * sample; sx < std::min((x + 1) * sample, unsigned(dims[0])); ++sx) {
                  sumGray += ((sx / 16 + sy / 16) % 2) ? 220 : 35; ++weight;
                }
              for (int ci = 0; ci < 3; ++ci)
                require(abs(a.rgb[(y * a.w + x) * 3 + ci] - (sumGray + weight / 2) / weight) <= 3,
                        "independent area-weighted checkerboard");
            }
          auto repeated = decode(nullptr, &bytes, sample, true);
          require(a.rgb == repeated.rgb, "deterministic repeat"); ++count;
        }
      }
  }
  for (int kind = 0; kind < 3; ++kind) {
    auto b = fixture(128, 128, kind == 2 ? 4 : 3, 2, kind == 0, false, kind == 1);
    require(decode(nullptr, &b, 16, true).status == JPEG_DC_UNSUPPORTED, "unsupported encoding"); ++count;
  }
  auto b = fixture(513, 511, 3, 2);
  require(decode(nullptr, &b, 8, true).status == JPEG_DC_UNSUPPORTED, "unsupported sample"); ++count;
  for (int after : {0, 3}) {
    auto result = decode(nullptr, &b, 16, true, after);
    require(result.status == JPEG_DC_CANCELLED, "cancelled result");
    require(result.checks == after + 1, "cancel bounded");
    require(decode(nullptr, &b, 16, true).status == 0, "retry after cancel"); ++count;
  }
  for (size_t cut : {size_t(0), size_t(10), b.size() / 2, b.size() - 2}) {
    std::vector<unsigned char> truncated(b.begin(), b.begin() + cut);
    require(decode(nullptr, &truncated, 16, true).status == -1, "truncation fails"); ++count;
  }
  // Critical fused-table boundary: coefficient 63 can be nonzero, with NO EOB.
  // Optimized Huffman tables also exercise one-bit codes and long amplitudes.
  for (int restart : {0, 1, 2, 3, 31, 65535}) for (bool optimize : {false, true})
    for (int pattern : {0, 1, 2}) for (unsigned sample : {16u, 32u}) {
      auto bytes = coefficientFixture(restart, optimize, pattern);
      auto dc = decode(nullptr, &bytes, sample, true);
      require(dc.status == 0, "coefficient-boundary valid JPEG");
      for (auto value : dc.rgb) require(value == 128, "DC independent of arbitrary AC coefficients");
      ++count;
    }
  // The fit path must match standard 1/8 scanlines byte for byte, including
  // color conversion and odd edges, rather than the DC area's tolerance.
  for (auto dims : {std::array<int, 2>{1, 1}, {17, 19}, {257, 129}, {128, 256}})
    for (int kind : {0, 1, 2, 3, 4, 5}) for (bool restart : {false, true})
      for (int pattern : {0, 1, 2, 3}) {
        auto bytes = fixture(dims[0], dims[1], kind == 5 ? 1 : 3,
                             kind == 5 ? 0 : kind, false, restart, false, pattern);
        auto reference = decode(nullptr, &bytes, 8, false);
        auto fast = decode(nullptr, &bytes, 8, false, -1, true);
        require(reference.status == 0 && fast.status == 0, "fit scanlines decode");
        require(reference.rgb == fast.rgb, "fit byte-exact standard output");
        ++count;
      }
  auto fitSeed = fixture(257, 129, 3, 0, false, true, false, 3);
  for (int sampling : {0, 1, 2, 3, 4}) for (int restart : {0, 1, 3, 31, 65535})
    for (bool optimize : {false, true}) for (int pattern : {0, 1, 2}) {
      auto bytes = coefficientFixture(restart, optimize, pattern, sampling);
      auto reference = decode(nullptr, &bytes, 8, false);
      auto fast = decode(nullptr, &bytes, 8, false, -1, true);
      require(reference.status == 0 && fast.status == 0 && reference.rgb == fast.rgb,
              "retained chroma coefficients and optimized Huffman byte-exact"); ++count;
      auto fused = decode(nullptr, &bytes, 8, false, -1, true, true);
      require(fused.status == 0 && fused.rgb == reference.rgb, "fused RGBA coefficient boundaries exact"); ++count;
    }
  for (int after : {0, 3}) {
    require(decode(nullptr, &fitSeed, 8, false, after, true).status == JPEG_DC_CANCELLED,
            "fit cancelled during scanlines");
    require(decode(nullptr, &fitSeed, 8, false, -1, true).status == 0,
            "fit retry after cancellation"); ++count;
    require(decode(nullptr, &fitSeed, 8, false, after, true, true).status == JPEG_DC_CANCELLED,
            "fused RGBA cancellation");
    require(decode(nullptr, &fitSeed, 8, false, -1, true, true).status == 0,
            "fused RGBA cancellation retry"); ++count;
  }
  for (size_t cut : {size_t(0), size_t(10), fitSeed.size() / 2, fitSeed.size() - 2}) {
    std::vector<unsigned char> cutBytes(fitSeed.begin(), fitSeed.begin() + cut);
    require(decode(nullptr, &cutBytes, 8, false, -1, true).status == -1,
            "fit truncated input rejected"); ++count;
    require(decode(nullptr, &cutBytes, 8, false, -1, true, true).status == -1,
            "fused truncated input rejected"); ++count;
  }
  auto fuzzSeed = fixture(257, 129, 3, 2, false, true, false, 3);
  int rejected = 0;
  for (unsigned trial = 0; trial < 1000; ++trial) {
    auto bytes = fuzzSeed;
    uint32_t random = trial * 0x9e3779b9u + 0x85ebca6bu;
    for (unsigned mutation = 0; mutation < 1 + trial % 4; ++mutation) {
      random ^= random >> 16; random *= 0x7feb352du; random ^= random >> 15;
      size_t at = random % bytes.size(); bytes[at] ^= 1u << (random % 8);
    }
    auto mutated = decode(nullptr, &bytes, trial & 1 ? 16 : 32, true);
    require(mutated.status == -1 || mutated.status == 0 || mutated.status == JPEG_DC_UNSUPPORTED,
            "mutated input result is bounded");
    if (mutated.status != 0) ++rejected;
    ++count;
    // Exercise the new adapter + retained chroma too, not only the earlier DC API.
    auto fitMutated = decode(nullptr, &bytes, 8, false, -1, true);
    require(fitMutated.status == -1 || fitMutated.status == 0 || fitMutated.status == JPEG_DC_UNSUPPORTED,
            "mutated fit result bounded"); ++count;
  }
  printf("{\"mutated_inputs\":1000,\"rejected\":%d}\n", rejected);
  auto fusedSeed = fixture(257, 129, 3, 0, false, true, false, 3);
  for (unsigned trial = 0; trial < 1000; trial++) {
    auto bytes = fusedSeed;
    uint32_t bits = trial * 0x9e3779b9u + 0x85ebca6bu;
    for (unsigned mutation = 0; mutation < 1 + trial % 4; mutation++) {
      bits ^= bits >> 16; bits *= 0x7feb352du; bits ^= bits >> 15;
      bytes[bits % bytes.size()] ^= 1u << (bits % 8);
    }
    auto result = decode(nullptr, &bytes, 8, false, -1, true, true);
    require(result.status == -1 || result.status == 0 || result.status == JPEG_DC_UNSUPPORTED,
            "fused mutation bounded"); ++count;
  }
  printf("{\"tests\":%d,\"worst_mae\":%.3f,\"worst_max\":%.0f,\"status\":\"PASS\"}\n",
         count, worstMAE, worstMax);
}
int main(int argc, char **argv) {
  if (argc == 2 && std::string(argv[1]) == "--selftest") { selftest(); return 0; }
  if (argc == 2 && (std::string(argv[1]) == "--pixel-manifest" || std::string(argv[1]) == "--rgba-manifest")) {
    for (auto dims : {std::array<int,2>{1,1}, {17,19}, {257,129}, {128,256}})
      for (int kind : {0,1,2,3,4,5}) for (bool restart : {false,true})
        for (int pattern : {0,1,2,3}) for (unsigned sample : {1u,2u,4u,8u}) {
          auto bytes = fixture(dims[0], dims[1], kind == 5 ? 1 : 3,
                               kind == 5 ? 0 : kind, false, restart, false, pattern);
          auto r = decode(nullptr, &bytes, sample, false, -1, true, std::string(argv[1]) == "--rgba-manifest");
          require(r.status == 0, "manifest standard decode");
          uint64_t hash = 14695981039346656037ULL;
          for (unsigned char value : r.rgb) { hash ^= value; hash *= 1099511628211ULL; }
          printf("%d,%d,%d,%d,%d,%u,%u,%u,%016llx\n", dims[0], dims[1], kind,
                 int(restart), pattern, sample, r.w, r.h, (unsigned long long)hash);
        }
    return 0;
  }
  if (argc == 6 && (std::string(argv[1]) == "--fixture" || std::string(argv[1]) == "--fixture-norst" || std::string(argv[1]) == "--fixture-progressive")) {
    auto bytes = fixture(atoi(argv[3]), atoi(argv[4]), 3, atoi(argv[2]),
                         std::string(argv[1]) == "--fixture-progressive",
                         std::string(argv[1]) == "--fixture", false, 3);
    FILE *file = fopen(argv[5], "wb"); if (!file) return 3;
    const auto written = fwrite(bytes.data(), 1, bytes.size(), file); fclose(file);
    return written == bytes.size() ? 0 : 4;
  }
  if (argc < 4) { fprintf(stderr, "jpeg-preview-bench dc|standard sample input [output.ppm]\n"); return 1; }
  const std::string mode = argv[1];
  const bool direct = mode == "dc";
  const unsigned sample = std::strtoul(argv[2], nullptr, 10);
  if (sample != 1 && sample != 2 && sample != 4 && sample != 8 && sample != 16 && sample != 32) return 1;
#ifdef PIXEL_NDK_COMPARE
  auto r = mode == "platform" ? platform(argv[3], sample) : decode(argv[3], nullptr, sample, direct, -1, mode == "fitfast" || mode == "fitfused", mode == "fitfused");
#else
  auto r = decode(argv[3], nullptr, sample, direct, -1, mode == "fitfast" || mode == "fitfused", mode == "fitfused");
#endif
  printf("{\"mode\":\"%s\",\"status\":%d,\"source\":[%u,%u],\"output\":[%u,%u],"
         "\"native\":[%u,%u],\"space\":%d,\"wall_ms\":%.3f,\"cpu_ms\":%.3f,"
         "\"header_ms\":%.3f,\"read_position\":%ld,\"checks\":%d}\n",
         mode.c_str(), r.status, r.sourceW, r.sourceH, r.w, r.h,
         r.nativeW, r.nativeH, r.space, r.wall, r.cpu, r.header, r.readPosition, r.checks);
  if (r.status) { fprintf(stderr, "%s\n", r.message.c_str()); return 2; }
  if (argc >= 5) {
    FILE *out = fopen(argv[4], "wb"); if (!out) return 3;
    fprintf(out, "P6\n%u %u\n255\n", r.w, r.h);
    const auto wrote = fwrite(r.rgb.data(), 1, r.rgb.size(), out);
    fclose(out); if (wrote != r.rgb.size()) return 4;
  }
  return 0;
}
