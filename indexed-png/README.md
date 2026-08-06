# Indexed PNG region decoder

`indexed-png` is an opt-in Android library for very large local PNG files.
PNG stores image rows in a single filtered DEFLATE stream, so a JPEG-style
Huffman/MCU seek table cannot provide efficient arbitrary regions. This backend
instead performs one explicit full decode and writes a lossless, multi-resolution
tile index. Later region requests inflate only the tiles covering the viewport.

## Behaviour

- Opening a PNG never creates an index automatically.
- Index creation is the only full-image operation.
- Every pyramid level is lossless premultiplied RGBA8; no JPEG/WebP loss is used.
- Baseline and Adam7-interlaced PNG files are supported.
- A changed source file invalidates its old index.
- Native failures return `null`, so hosts can keep their existing decoder as a
  safe fallback.
- Index data is explicit user-created data below `noBackupFilesDir`; normal cache
  eviction does not silently remove it.

The API lives under `io.github.indexedpng`. The host owns all UI, scheduling,
storage policy, and confirmation prompts.
