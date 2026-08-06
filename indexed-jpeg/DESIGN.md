# Design

## Separation from the host application

The library owns only source validation, index persistence, native index
construction, and indexed region decoding. It has no dependency on Compose,
Pixel Gallery media models, SSIV, Glide, or the host tile cache. A host can
therefore replace the UI or extract this directory into its own repository.

## Lifecycle

1. `IndexedJpegStore.status()` reads only the JPEG signature and the small
   index header. It never scans or decodes the source image.
2. `build()` is the sole full-file operation. It writes a temporary file and
   publishes it only after the native builder finishes successfully.
3. `openDecoder()` validates the source version and loads the pointer-free
   index into a native handle.
4. `decodeRegion()` uses the same source index for every power-of-two sample
   size. Samples above the JPEG DCT limit use indexed 1/8 decode followed by
   bounded sampling into the requested tile.
5. Any native failure returns `null`; the host remains responsible for its
   ordinary decoder fallback.

## Persistent format

The `.ijx` format is little-endian and versioned. It stores fixed-width scalar
fields and flattened checkpoint records, never C pointers, `size_t`, or native
structure padding. Its header binds the index to source byte length and source
modification time. Source changes therefore make the old index invalid without
silently applying offsets to different JPEG data.

## Deliberate policy

Indexes are stored below `noBackupFilesDir`, not `cacheDir`: they are explicit
user-created data, are not cloud-backed up, and are not removed by ordinary
cache eviction. The host exposes deletion and decides which images are offered
the feature.
