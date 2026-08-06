# Local integration changes

The vendor tree is libjpeg-turbo 1.3.1 with the Android/Linaro indexed
tile-decode patch series applied. Project-specific changes inside that tree are
kept narrow and recorded here:

- `jdatasrc.c` implements the indexed decoder's absolute seek callback for the
  stdio source manager and keeps buffer offsets coherent after a seek;
- `jdhuff.c` and `jdphuff.c` zero-initialize and safely grow checkpoint arrays,
  allowing malformed or partially indexed input to be released safely;
- `jdmaster.c` permits indexed tile decoding to recalculate output dimensions
  after entering scanline state, which is required for progressive JPEG scans;
- `jsimd_none.c` plus the module build select portable C entry points on every
  Android ABI until the historical patched SIMD surface is ported and tested on
  maintained libjpeg-turbo releases.

The persistent `.ijx` serializer and JNI surface live outside the vendor tree in
`../indexed_jpeg_jni.cpp`. New local vendor edits must be added to this list and
must include a regression test before release.
