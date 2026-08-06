# Design

Uncompressed BMP rows already have deterministic offsets, so a tile pyramid would waste storage
and build power. The manifest records the validated dimensions, pixel-array offset, DWORD-aligned
row stride, bit depth and row direction. Decode then reads one bounded byte span for each sampled
output row and converts BGR/BGRX pixels directly into the destination Android bitmap.

The backend deliberately supports only `BI_RGB` 24/32-bit DIBs in its first version. This keeps the
row-addressing guarantee exact; compressed, palette, mask and embedded-codec variants remain on the
existing preview path instead of being decoded incorrectly.
