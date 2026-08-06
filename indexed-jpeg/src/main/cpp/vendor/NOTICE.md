# Native source provenance

## Base source

The vendored JPEG implementation starts from the libjpeg-turbo 1.3.1 source
release published in March 2014:

- release archive: https://downloads.sourceforge.net/project/libjpeg-turbo/1.3.1/libjpeg-turbo-1.3.1.tar.gz
- upstream project: https://github.com/libjpeg-turbo/libjpeg-turbo
- upstream release notes: `libjpeg-turbo-indexed/README-turbo`

This old version is retained because the indexed decoder patch was written
against its internal decompressor structures. Updating the base requires a
deliberate port and new corruption/performance tests; it must not be performed
as an unreviewed source replacement.

## Indexed tile patch

The base tree has the Android indexed tile-decode patch series published by
Linaro applied. The archived patch attachment is:

https://sourceforge.net/p/libjpeg-turbo/patches/_discuss/thread/eda7eb07/c9da/attachment/Android_Refresh_Patches.tar.gz

The patch adds Huffman checkpoint collection, source seeking, and tile-oriented
decode entry points. It is the upstream origin of `ANDROID_TILE_BASED_DECODE`
and the added index structures in `jpeglib.h`; those mechanisms were not
invented by this repository.

## Code owned by this project

The persistent pointer-free `.ijx` serializer, validation rules, JNI boundary,
Android storage API, explicit user-controlled lifecycle, and tests are project
code outside the vendor tree. The additional vendor-tree compatibility changes
are enumerated in `PATCHES.md` so this repository is auditable without being
misrepresented as a GitHub fork of one single upstream project.

## Licensing

libjpeg-turbo and IJG notices are preserved verbatim in `README` and
`README-turbo`. The Android/Linaro changes are Apache-2.0 licensed; that text is
preserved as `LICENSE-APACHE-2.0`. No upstream copyright notices were removed.
