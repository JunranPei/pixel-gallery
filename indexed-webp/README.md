# Indexed WebP region decoder

`indexed-webp` is an opt-in Android backend for very large local static WebP images. WebP does not
provide cheap arbitrary region seeking, so one explicit build decodes the source once and writes a
lossless, multi-resolution tile pyramid. Later requests inflate only tiles covering the viewport.

- Opening a WebP never creates an index automatically.
- Build maps the compressed source and a temporary RGBA raster instead of keeping either copy on
  the Java heap.
- Pyramid payloads are independent zlib-compressed premultiplied RGBA8 tiles.
- Lossy and lossless static WebP are supported; animated WebP is rejected because indexing one
  frame would silently destroy animation semantics.
- Changed sources invalidate their old index, and tile CRC failures fail closed.
- The index is explicit user-created data under `noBackupFilesDir`, not an ordinary cache entry.

The public API is under `io.github.indexedwebp`. The host owns UI, scheduling, and fallback.
