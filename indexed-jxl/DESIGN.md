# Design

## Why JPEG XL uses a sidecar

The public libjxl decoder does not expose JPEG-style persistent MCU seek points for arbitrary
viewport decoding. Reopening the compressed source for every evicted viewport would therefore
repeat much of the image decode. The explicit sidecar moves that cost to a user-requested build
and makes later region reads proportional to the requested viewport.

## Bounded build

1. The host explicitly requests a build; the source is read-only memory mapped.
2. libjxl validates the codestream, applies its orientation, and converts ordinary still pixels
   to 8-bit sRGB through LCMS.
3. Its scanline callback fills one work band of at most 512 rows. A completed band is immediately
   split into independent 512x512 lossless tiles.
4. Lower pyramid levels are generated from the preceding lossless level with a small tile working
   set.
5. Payload checksums and the directory are synced before the temporary index is atomically
   published.

The callback order is validated. An incomplete, overlapping, or out-of-order stream aborts the
build rather than publishing a corrupt index.

## Decode

The closest stored level no coarser than the requested sample is selected. Only overlapping tiles
are inflated and sampled into the Android bitmap. The original JPEG XL codestream is not reopened
after a valid index has been activated.

## Deliberate exclusions

This first backend stores premultiplied RGBA8 sRGB. Animated frames, HDR/high-bit-depth pixels,
and non-alpha extra channels are rejected because flattening them into that representation would
silently discard image semantics. These can be added as separately versioned index payloads.
