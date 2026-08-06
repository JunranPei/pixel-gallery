# Indexed BMP row decoder

`indexed-bmp` activates direct region reading for large, uncompressed local BMP images. An explicit
build validates the DIB layout and writes a tiny source-bound manifest; it does not duplicate image
pixels. Later requests read only the scan-line spans intersecting the viewport.

- Opening a BMP never creates an index automatically.
- The initial activation reads only the headers and has negligible memory/storage cost.
- 24-bit and 32-bit `BI_RGB` BMP files, including top-down and bottom-up DIBs, are supported.
- RLE, embedded JPEG/PNG, bitfields, palette and color-managed variants fail closed to the host's
  ordinary preview path until a dedicated decoder is added.
- Source changes invalidate the manifest.

The public API is under `io.github.indexedbmp`.
