# Indexed JPEG region decoder

`indexed-jpeg` is an opt-in Android library for large local JPEG files. It builds
a persistent Huffman/MCU seek index only when requested by the caller, then uses
that index to decode source regions without entropy-decoding every preceding MCU
row.

The module is intentionally independent from Pixel Gallery. Its public API lives
under `io.github.indexedjpeg`, its persistent file format is versioned, and the
host application owns all user interface and policy decisions.

## Behaviour

- Opening an image never creates an index automatically.
- One source index is shared by every supported tile sample size.
- A changed source file invalidates its old index.
- Native decode failures return `null`, allowing the host to use its existing
  decoder without breaking image display.
- Indexes live in app-persistent storage and are removed only by an explicit
  caller action or source-version cleanup.

## Upstream

The native decoder is based on libjpeg-turbo 1.3.1 plus the Android/Linaro
indexed tile-decode patches. See `src/main/cpp/vendor/NOTICE.md` for provenance
and licenses.
