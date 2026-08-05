# Native source provenance

The vendored JPEG implementation is based on libjpeg-turbo 1.3.1:

https://downloads.sourceforge.net/project/libjpeg-turbo/1.3.1/libjpeg-turbo-1.3.1.tar.gz

It includes the Android indexed tile-decode changes published by Linaro in the
`Android_Refresh_Patches.tar.gz` attachment:

https://sourceforge.net/p/libjpeg-turbo/patches/_discuss/thread/eda7eb07/c9da/attachment/Android_Refresh_Patches.tar.gz

libjpeg-turbo and IJG licensing text is preserved in `README` and
`README-turbo`. The Android/Linaro changes are distributed under Apache-2.0;
the Apache license is preserved as `LICENSE-APACHE-2.0`.
