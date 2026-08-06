# Design

## Why HEIF/AVIF uses a sidecar

Android can region-decode HEIF and AVIF, but a later viewport request still invokes the compressed
source codec. An explicit sidecar converts the chosen primary still image into independent,
lossless tiles so revisits do not reopen or re-decode the original bitstream.

## Bounded build

1. The host explicitly requests a build and opens Android's `BitmapRegionDecoder`.
2. The sample-1 image is decoded one source rectangle of at most 512x512 pixels at a time.
3. Each software ARGB bitmap is immediately copied into an independently zlib-compressed tile.
4. Lower levels are generated from preceding lossless tiles with a small working set.
5. The directory is published only after all payloads and checksums are complete.

## Decode

The closest stored level no coarser than the requested sample is selected. Only overlapping tiles
are inflated and sampled into the Android bitmap. This path never reopens the original HEIF/AVIF.

The module indexes the primary still image exposed by Android. Image-sequence timing and playback
semantics are outside this static region backend.
