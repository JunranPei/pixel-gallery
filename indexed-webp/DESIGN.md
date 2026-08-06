# Design

## Why WebP needs a sidecar

The ordinary WebP bitstream has no public MCU/row restart index suitable for arbitrary rectangles.
Seeking to a byte offset cannot independently reconstruct a viewport. The explicit build therefore
converts the sequential representation into independently compressed tiles.

## Bounded build

1. The source file is read-only mapped and validated with libwebp.
2. A temporary RGBA raster file is sized to `width * height * 4` and memory mapped. libwebp decodes
   directly into it, avoiding a second Java/native heap-sized bitmap.
3. Base tiles are premultiplied and compressed independently with zlib.
4. Lower levels are generated from the preceding tiles with a small working set.
5. The versioned directory is published only after payloads and CRCs are complete; the temporary
   raster is then removed.

## Decode

The closest stored level no coarser than the requested sample is selected. Only overlapping tiles
are inflated and sampled into the Android bitmap. This path never reopens the original WebP.

Animated WebP is deliberately unsupported in phase one. Its frames, disposal modes, and timing need
a separate animation-aware policy rather than an incorrect static first-frame index.
