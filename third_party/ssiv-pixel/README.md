# Pixel Gallery SSIV fork

This module vendors com.github.tibbi:subsampling-scale-image-view at commit
80efdaa570, the same revision used by Simple Gallery.

Pixel Gallery keeps the upstream tile scheduling and decoder interfaces, and
adds only two opt-in viewer behavior controls:

- minScaleFactor: minimum scale relative to fit-screen (upstream default 1f)
- doubleTapReturnsToFit: return every non-fit scale to fit-screen on double tap

Both controls preserve upstream behavior by default. Pixel Gallery enables
them only for the normal still-image viewer.

Upstream license: see LICENSE.