# Design

## Why TIFF does not use the PNG sidecar pyramid by default

TIFF is a directory and block container. `TileOffsets`/`TileByteCounts` or
`StripOffsets`/`StripByteCounts` already provide random access, and pyramid TIFFs may expose
reduced-resolution IFDs or SubIFDs. Copying those pixels into a second global pyramid would waste
storage and one-time energy without improving a well-formed tiled TIFF.

## Activation lifecycle

1. libtiff opens the source and validates the base directory.
2. Compatible SubIFDs and reduced-image directories are collected as power-of-two overview levels.
3. Pixel layout, orientation, alpha semantics, and maximum decoded block size are checked.
4. A fixed, versioned 52-byte manifest is atomically written and bound to source size and modified
   time. No pixel payload is written.

## Decode lifecycle

The decoder chooses the finest native overview no coarser than the requested sample. It switches to
that IFD, decodes only overlapping native blocks, and samples them directly into a premultiplied
RGBA8 Android bitmap. Blocks are shared within one region request and released immediately after it.

## Deliberate limits

This backend is lossless for its supported layouts. Formats requiring color management, tone
mapping, page selection, or orientation remapping are left to a future policy-aware adapter. A
future optional sidecar pyramid can serve non-pyramidal TIFFs with inefficient giant strips, but it
must remain explicit and must preserve the source's intended color/bit-depth semantics.
