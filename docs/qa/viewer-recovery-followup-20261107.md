# 4.3.6.4 flicker follow-up

User reports the flicker persists. No root-cause or fix claim follows from the previous group-work isolation results.

Added a test-only first-draw observer around real ACTION_VIEW Activity recreation, with no test-content reinjection and memory-cache clearing before each recreation. It observes actual ImageView/SSIV readiness, alpha, focus and Activity identity. The first emulator run collected no focused draws in all three recovery phases, so it is INVALID for assessing visible blank frames. The test now explicitly rejects this condition. No new production fix was made on that basis.

Phone is not connected; only emulator-5562 was present. Await a user-started capture on the updated existing viewertrace package. No phone capture has been started.

Old listener APK verified from scratch/release-4.3.6.1-source/app/build/outputs/apk/release/app-release.apk: com.pixel.gallery.viewertrace, 4.3.6.2-auto-multitask, versionCode 4079, arm64-v8a. Certificate SHA-256 2f1be29e2d55ae2f4040dfbaeb64f16ae78ab9cfd0ae4c6afe79c52dae4a7fdf. Current trace build uses -PviewerTestVariant=trace -PofficialRelease=false, with current 4.3.6.4 source and the same signature. This enables the existing ViewerLoadMetrics diagnostics without changing production package identity or viewer rendering logic.
