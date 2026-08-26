# Design

## Why PNG needs a different backend

PNG filtering makes each decoded scanline depend on earlier scanline data, while
all IDAT chunks form one logical DEFLATE stream. Byte offsets alone therefore do
not turn a PNG into a cheap random-access image. The explicit build step converts
that sequential representation into independently compressed 512-pixel tiles.

## Build lifecycle

1. A shared source policy verifies that the image is a static SDR sRGB PNG whose display
   semantics can be represented by the RGBA8 index. Untagged PNGs, explicit `sRGB`, canonical
   full-range sRGB `cICP`, and canonical `gAMA` + `cHRM` are accepted; animation, ICC, wide-gamut,
   limited-range, and mastering-display metadata fail closed to the platform decoder.
2. libspng validates the PNG and decodes it once. Opaque sources stay RGB8; sources with an
   alpha channel or `tRNS` are stored as premultiplied RGBA8.
3. Non-interlaced images are consumed a bounded band at a time. Adam7 images use
   a temporary row store because passes revisit rows non-sequentially.
4. Each tile applies a cheap, reversible per-row filter before independent zlib compression.
   The filter set is intentionally limited to None/Sub/Up so cache misses remain inexpensive to
   reconstruct on mobile CPUs.
5. Lower-resolution levels are generated losslessly from the preceding level,
   keeping peak memory bounded to a handful of tiles.
6. A fixed-width little-endian directory is written only after every payload is
   complete, then the host atomically publishes the temporary index.

## Decode lifecycle

The decoder selects the closest stored level that is no coarser than the requested
sample size, inflates and reverses filtering only for overlapping tiles, and writes the requested
output bitmap. RGB tiles expand directly into Android's RGBA bitmap without an intermediate
full-image allocation. File reads use positional I/O and the native handle serializes access.

SSIV keeps its compact 1024-pixel decoded tiles, but is told that exact size so it does not balance
the final column across the grid. Since 1024 is an integer multiple of the persistent 512-pixel
blocks, neighbouring requests no longer re-inflate a shared block merely because their boundaries
were shifted.

## Persistent format

The `.ipx` format is versioned and binds itself to source byte length and source
modification time. It contains no pointers, native structure padding, or ABI-sized
fields. Tile payloads carry CRC-32 values so damaged index data fails closed.
