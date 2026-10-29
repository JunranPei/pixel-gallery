Preview decoder: libjpeg-turbo 3.1.3

Unmodified upstream source archive:
https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.1.3.tar.gz

SHA-256: 3a13a5ba767dc8264bc40b185e41368a80d5d5f945944d1dbaa4b2fb0099f4e5

The archive includes LICENSE.md, README.ijg and all upstream notices. The build
extracts it locally, preserves those notices and applies only the readable
DC-preview extension in jdpreview.c, jdpreview.h and jdpreview_entropy.inc through
patch_preview_turbo.cmake. This is separate from the existing 1.3.0 tile-index
fork; their entropy state formats and native symbols are not shared.

The extension adds DC-only area-averaged 1/16 and 1/32 RGB preview output and
fused AC-symbol skipping. It does not alter any upstream standard decode entry
point. EXIF orientation and ICC color conversion remain the caller's job.
