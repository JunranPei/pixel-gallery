# Verification scope

- App unit tests: 355 passed, 0 failed, 0 skipped.
- Indexed JPEG planner tests: 5 passed.
- SSIV stored-sample tests: 6 passed.
- Each branch compiled its own app, unit-test and Android-test Kotlin sources.
- Full isolated x86_64 APK builds and emulator checks are recorded below after completion.
- Shared native/module source trees were checked identical across both candidates; branch 2 uses the same source directories to avoid rebuilding identical native libraries. App/UI/package configurations remain branch-specific.
- No production package or user media was installed, cleared or modified. Native decoder throughput, physical-device PiP, all ABI release builds and launcher-specific pin-confirmation behavior are outside this synchronization's runtime verification.

## Completed runtime checks (emulator-5556)

- Full x86_64 app and Android-test APK build passed in 6m31s. Manifest-only document fix rebuilt in 25s.
- Test package: com.pixel.gallery.shortcuts.test; version 4.3.5.9-multi-entry, x86 split code 6076.
- Settings -> Desktop shortcuts -> Create Shortcut -> Cancel passed using actual retained routes. Independent settings group screenshot was visually reviewed.
- Duplicate-status native suite passed: light/dark scan, preparation hides counts, no stop marker, identical no-match/cleanup result and distinct cancellation/error/incomplete states; LabelOff icon captured.
- Before fix, two document URIs both reused task 134 with singleTop. After changing branch 2 to standard launchMode, Albums and Bin opened in separate tasks 135 and 136, with independent SyncAlbums/SyncBin task titles. Bin intent displayed the actual Recycle Bin route.
- Two ordinary launcher starts reused the same normal task 137; reopening the Albums document reused task 135. This preserves manual entries without turning ordinary launches into automatic new windows.
- Settings data key custom_shortcuts, saved icon filenames and shortcut ID/URI scheme are unchanged. Launcher-specific pin confirmation and a real custom image were not exercised.
