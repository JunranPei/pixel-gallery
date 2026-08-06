# Indexed HEIF/AVIF region decoder

`indexed-heif` is an opt-in Android backend for very large local HEIF/HEIC and AVIF still images.
One explicit build uses Android's platform region decoder to read bounded source rectangles and
writes a lossless, multi-resolution tile pyramid. Later requests inflate only tiles covering the
viewport.

- Opening an image never creates an index automatically.
- The complete decoded raster is never materialized on the Java or native heap.
- Pyramid payloads are independent zlib-compressed premultiplied RGBA8 tiles.
- Actual source codec support follows the Android device; unsupported AVIF/HEIF sources fail
  without replacing an existing valid index.
- Changed sources invalidate their old index, and tile CRC failures fail closed.
- The index is explicit user-created data under `noBackupFilesDir`, not an ordinary cache entry.

The public API is under `io.github.indexedheif`. The host owns UI, scheduling, and fallback.
