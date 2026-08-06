# Indexed RAW render backend

`indexed-raw` is an opt-in Android backend for camera RAW files. A RAW file stores sensor data, not
a finished display image, so the explicit build develops one deterministic sRGB rendering with
LibRaw and stores it as a lossless multi-resolution tile pyramid.

- Opening a RAW file never starts demosaic or creates an index.
- The current embedded preview remains the cheap default until the user explicitly builds.
- Build uses camera white balance, an AHD-quality demosaic, 8-bit sRGB output, and disables LibRaw
  orientation so the host's existing orientation transform remains authoritative.
- The developed raster is streamed through a temporary PPM file; no second full output bitmap is
  kept on the Java heap.
- Later zoom reads only independently compressed tiles and never re-demosaics the source.
- Source changes invalidate the index. Renderer/profile changes require an index format bump.

The result is a stable developed interpretation, not a claim to reproduce every camera vendor's
private rendering. LibRaw 0.22.2 is included under its LGPL-2.1-or-CDDL-1.0 dual license.
