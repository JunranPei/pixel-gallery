# Design

## Why RAW needs a rendered sidecar

RAW decoding includes unpacking sensor samples, black-level correction, white balance, demosaic,
color conversion, and tone/gamma processing. A byte-offset index cannot skip those dependencies for
an arbitrary display rectangle. The reusable artifact must therefore be a versioned developed
rendering.

## Build lifecycle

1. LibRaw 0.22.2 opens and unpacks the local source with bounded allocation checks enabled.
2. A deterministic 8-bit sRGB profile is developed with camera white balance and AHD-quality
   interpolation. LibRaw orientation is disabled to avoid double rotation in hosts.
3. LibRaw streams the developed image to a temporary PPM file and releases its sensor buffers.
4. The PPM is mapped read-only a bounded band at a time into lossless 512-pixel RGBA tiles.
5. Lower-resolution levels are generated from existing tiles and the completed index is published
   atomically. The temporary PPM is deleted on success or failure.

## Limits

The build is intentionally explicit because demosaic can be expensive and LibRaw must hold working
sensor buffers. Optional external codecs (commercial DNG SDK, RawSpeed, LCMS, and external libjpeg)
are not enabled in phase one; unsupported sources fail without replacing the embedded-preview path.
