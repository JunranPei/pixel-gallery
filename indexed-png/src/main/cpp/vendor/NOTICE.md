# Native source provenance

The PNG parser and decoder is libspng 0.7.4, upstream tag `v0.7.4`, commit
`fb768002d4288590083a476af628e51c3f1d47cd`:

https://github.com/randy408/libspng/tree/v0.7.4

Only upstream `spng/spng.c` and `spng/spng.h` are vendored. Their unmodified
SHA-256 values are:

- `spng.c`: `b505d7ed7da59c0d318b3ad4d74ff1676f765bd1b074bfed1110ecaa6c4826da`
- `spng.h`: `9823355fe6659f1f54fb1c3df3b2544a14f708e42ca05db55254d85913be3114`

libspng is BSD-2-Clause licensed. Its license is preserved as
`libspng/LICENSE`. The indexed tile container, JNI bridge, and Android API are
separate project code and do not modify the vendored libspng files.
