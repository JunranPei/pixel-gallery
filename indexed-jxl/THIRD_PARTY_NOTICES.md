# Third-party notices

The native decoder is based on the official JPEG XL reference implementation, `libjxl` 0.11.2
(commit `332feb17d17311c748445f7ee75c4fb55cc38530`). It is distributed under the BSD 3-Clause
license with the additional patent grant in `src/main/cpp/vendor/libjxl/PATENTS`.

Pinned dependencies included by that release:

- Highway commit `457c891775a7397bdb0376bb1031e6e027af1c48` (Apache-2.0/BSD-3-Clause; see its source tree).
- Little CMS commit `5176347635785e53ee5cee92328f76fda766ecc6` (MIT; see its source tree).
- Brotli commit `36533a866ed1ca4b75cf049f4521e4ec5fe24727` (MIT; see its source tree).

The small Android test fixture comes from the official libjxl testdata repository and retains its
license and patent files under `src/androidTest/assets`.
