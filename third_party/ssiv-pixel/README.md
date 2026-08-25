# Pixel Gallery SSIV fork

This module vendors com.github.tibbi:subsampling-scale-image-view at commit
80efdaa570, the same revision used by Simple Gallery.

Pixel Gallery preserves the upstream gesture and coordinate model, but its tile
scheduler and decoder contract are intentionally extended for the gallery's
large-image power and cache policies. The main additions are:

- decoder capability reporting, including persistent-pyramid grids and live
  capability revisions;
- bounded decoded-tile dimensions, a bounded in-memory LRU, and visible-only
  RenderNode drawing;
- small adjacent-miss batches for sequential source decoders, disabled for
  independently addressable persistent tiles;
- stable-viewport cache admission and serialized, priority-ordered tile work;
- fit-preview deferral and preview-to-tile handoff used by the host viewer;
- `minScaleFactor`, `doubleTapReturnsToFit`, transform snapshots, and diagnostic
  callbacks.

Optional behavior controls preserve upstream-compatible defaults. Decoder
capabilities also have conservative defaults so ordinary region decoders keep
the sequential-source path unless they explicitly opt into a persistent grid.

Upstream license: see LICENSE.
