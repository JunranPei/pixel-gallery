# Indexed TIFF region decoder

`indexed-tiff` activates the random-access structures already present in local TIFF and BigTIFF
files. Unlike PNG, TIFF normally has an IFD containing independent tile or strip offsets, so an
activation does not duplicate the image or perform a full transcode. The saved `.itx` file is a
small source-bound manifest; pixels continue to come losslessly from the original TIFF blocks.

## Behaviour

- Opening a TIFF never creates an activation automatically.
- Building validates the base IFD and compatible power-of-two reduced-resolution IFDs.
- Decode reads only strips or tiles intersecting the requested region and selects a TIFF overview
  no coarser than the requested sample size when one exists.
- Classic TIFF and BigTIFF byte signatures are accepted through libtiff.
- The first release accepts top-left, contiguous, unsigned 8-bit grayscale, RGB, and declared RGBA.
- Multi-page semantics, palette/YCbCr/CMYK, planar data, 16-bit/HDR samples, and rotated IFDs fail
  closed instead of being silently converted to an incorrect 8-bit image.
- A changed source file invalidates its activation.
- Oversized native blocks are rejected so a region request cannot unexpectedly allocate a whole
  enormous strip.

The public API is under `io.github.indexedtiff`. The host owns confirmation UI and fallback.
