# Design

## Separation from the host application

The library owns only source validation, index persistence, native index
construction, and indexed region decoding. It has no dependency on Compose,
Pixel Gallery media models, SSIV, Glide, or the host tile cache. A host can
therefore replace the UI or extract this directory into its own repository.

## Lifecycle

1. `IndexedJpegStore.status()` reads the JPEG signature and validates the
   complete index structure, including checkpoint records, pyramid directory,
   payload bounds, and terminal marker. It does not scan or decode the source
   image.
2. `build()` records source checkpoints and may reopen the JPEG once per
   generated pyramid level. It writes and syncs a same-directory temporary
   file, then atomically replaces the published index. A failed publication
   keeps the previous index. Stale temporary files are removed only after a
   guarded age threshold.
3. `openDecoder()` validates the source version and loads the pointer-free
   index into a native handle.
4. `decodeRegion()` uses the same source index for every power-of-two sample
   size. Samples above the JPEG DCT limit use indexed 1/8 decode followed by
   bounded sampling into the requested tile.
5. Any native failure returns `null`; the host remains responsible for its
   ordinary decoder fallback.

`status()` also reports the persisted format and optimization shape. Legacy
fit previews, whole-JPEG pyramid layers, current addressable tiles, and a
seek-only current index are deliberately distinct states. Legacy files remain
usable, but upgrading them is an explicit host action rather than an automatic
open-time rebuild.

## Persistent format

The `.ijx` format is little-endian and versioned. It stores fixed-width scalar
fields and flattened checkpoint records, never C pointers, `size_t`, or native
structure padding. Its header binds the index to source byte length and source
modification time. Source changes therefore make the old index invalid without
silently applying offsets to different JPEG data.

Version 7 keeps the original-source seek checkpoints for `sample=1` and stores
each generated `sample=2/4/8...` level as independently decodable 1024x1024
JPEG blocks in the same container. The directory records every block's source
grid position, dimensions, encoded length, and payload offset. This prevents a
viewport miss from region-decoding a complete low-frequency JPEG layer.

## Deliberate policy

Indexes are stored below `noBackupFilesDir`, not `cacheDir`: they are explicit
user-created data, are not cloud-backed up, and are not removed by ordinary
cache eviction. The host exposes deletion and decides which images are offered
the feature.
