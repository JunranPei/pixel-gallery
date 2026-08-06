# Indexed JPEG XL region decoder

`indexed-jxl` is an opt-in Android backend for very large local, still JPEG XL images. One
explicit build streams pixels through the official `libjxl` decoder into a lossless,
multi-resolution tile pyramid. Later requests inflate only tiles covering the viewport.

- Opening an image never creates an index automatically.
- Index construction holds at most one 512-row RGBA8 work band instead of the complete raster.
- The source is converted through LCMS to sRGB and stored as independently zlib-compressed,
  premultiplied RGBA8 tiles.
- Animation, extra spot channels, HDR, and samples deeper than 8-bit fail closed until their
  representation is implemented without silent data loss.
- A changed source invalidates its old index, and tile CRC failures fail closed.
- The index is explicit user-created data under `noBackupFilesDir`, not an ordinary cache entry.

The public API is under `io.github.indexedjxl`. The host owns UI, scheduling, and fallback.

## Vendored decoder

The Android native library vendors the decoder portions of `libjxl` 0.11.2, Highway 1.2.0,
Little CMS, and the build-time Brotli sources pinned by that libjxl release. Upstream license and
patent grant files remain beside their source under `src/main/cpp/vendor/libjxl`.
