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
2. libspng validates the PNG and decodes it once to premultiplied RGBA8.
3. Non-interlaced images are consumed a bounded band at a time. Adam7 images use
   a temporary row store because passes revisit rows non-sequentially.
4. Level 1 tiles are independently compressed with zlib.
5. Lower-resolution levels are generated losslessly from the preceding level,
   keeping peak memory bounded to a handful of tiles.
6. A fixed-width little-endian directory is written only after every payload is
   complete, then the host atomically publishes the temporary index.

## Decode lifecycle

The decoder selects the closest stored level that is no coarser than the requested
sample size, inflates only overlapping tiles, and writes the requested output
bitmap. File reads use positional I/O and the native handle serializes access.

## Persistent format

The `.ipx` format is versioned and binds itself to source byte length and source
modification time. It contains no pointers, native structure padding, or ABI-sized
fields. Tile payloads carry CRC-32 values so damaged index data fails closed.
