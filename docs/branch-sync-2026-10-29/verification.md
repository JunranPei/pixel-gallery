# Verification scope

- App unit tests: 355 passed, 0 failed, 0 skipped.
- Indexed JPEG planner tests: 5 passed.
- SSIV stored-sample tests: 6 passed.
- Each branch compiled its own app, unit-test and Android-test Kotlin sources.
- Full isolated x86_64 APK builds and emulator checks are recorded below after completion.
- Shared native/module source trees were checked identical across both candidates; branch 2 uses the same source directories to avoid rebuilding identical native libraries. App/UI/package configurations remain branch-specific.
- No production package or user media was installed, cleared or modified. Native decoder throughput, physical-device PiP, all ABI release builds and launcher-specific pin-confirmation behavior are outside this synchronization's runtime verification.

## Completed runtime checks (emulator-5556)

- Full x86_64 app and Android-test APK build passed in 5m30s.
- Test package: com.pixel.gallery.main.test.debug; version 4.3.5.9-main, x86 split code 6076.
- Settings -> Display navigation passed through the shared retained shell. No desktop-shortcuts entry appears in branch 1.
- Duplicate-status native suite passed: light/dark scan, preparation count suppression, no progress stop marker, identical no-match/cleanup result, cancellation/error/incomplete distinctions, Label/LabelOff icons.
- Two launcher starts reused the same activity/task (task 131); second intent delivered to the top instance.
- Compiled manifest launchMode is singleTop; documentLaunchMode=always is absent.
- Screenshots were visually reviewed; see native/.
