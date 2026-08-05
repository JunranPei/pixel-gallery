# Local integration changes

The vendor tree is libjpeg-turbo 1.3.1 with the Android/Linaro indexed
tile-decode patch series applied. This module adds three narrow integration
changes:

- the stdio source manager implements the seek callback required by indexed
  decoding and tracks absolute buffer offsets;
- index pointer arrays are zero-initialized so partially built indexes can be
  released safely after malformed input or allocation failure;
- portable C SIMD stubs are selected on every ABI until the old patched SIMD
  entry points are replaced with maintained implementations for modern Android
  architectures.

The persistent `.ijx` serializer and JNI surface are outside the vendor tree in
`../indexed_jpeg_jni.cpp`.
