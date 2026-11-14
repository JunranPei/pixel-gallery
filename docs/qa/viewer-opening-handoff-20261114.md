# Viewer opening: detail drawing replaces the thumbnail during the animation

## Change

The opening thumbnail previously drew over the pager until `finish()`, even when
the detail image was already ready. Loading itself ran during the animation.

`viewerOpeningContent` now draws the ready detail layer and signals a handoff for
the same media URI and version. `ViewerOpeningPreview` checks that signal during
drawing and stops painting the cover. Resource callbacks (including the 200 px
bridge) alone do not remove it. The animation keeps its current visual state and
continues for its original duration. The existing thumbnail reference is released
at the original animation finish, so the grid cell stays hidden until then.

No image requests, cache policies, decoding dimensions, bitmap allocations or
copies were added. Image loading and the animation still start independently.

## Automated verification

- With the handoff disabled to reproduce the old behavior, the two new regression
  tests failed; the other 16 opening tests passed.
- With the fix restored, 90 tests passed, with zero failures/errors/skips: viewer
  geometry, gestures, video readiness/window state, preferences and album selection.
- The new tests cover resource-ready-before-draw, another page/version becoming
  ready, removal of the cover during an active animation, preservation of the
  current visual state and continuation of the remaining animation frames.
- `:app:assembleRelease` succeeded in the same verification run (3m 17s).
- `git diff --check` passed.

## Samsung verification

- Device: Samsung SM-S9380, serial R5CY13T5Z3J, Android 17, 1440 x 3120.
- Existing opening preference was Thumbnail. It was inspected and retained.
- Installed the updated normal release with `install -r`, preserving app data.
- Opened the supplied 11 JPEGs twice each (22 opens) with normal production caches.
- Ten first-open screenshots matched the corresponding local original in a
  centered fit comparison (sum of mean RGB errors 0.334 to 1.221 at 144 x 312).
  Photo 05 retained a saved zoom, so its identity was checked in the app's Details
  sheet: `image_5364491107960865_05.jpg`.
- Reviewed the recorded opening sequence. The frame at video time 1.75s shows
  detailed hair pixels while the picture is still expanding and the grid is
  visible behind it.
- The 55s recording contains 1,349 decoded frames. No frame had at least 98%
  black pixels in the central 58% of the viewport (threshold 32, excluding
  letterbox regions). No app crash/ANR/OOM was found in the captured log.
- Closed the Details sheet and returned to the photo grid after testing.

## OnePlus installation

- Subsequently installed the same APK on OnePlus CPH2745, serial
  3B15AP015BH00000, with `install -r`. Installed `base.apk` SHA-256 matched.
- Launch returned `Status: ok`; the gallery was the foreground activity.
- USB debugging and developer settings were enabled; the computer's saved public
  key was present and its authorization timeout was zero. Reapplied those values.
- Reset the USB gadget; the authorized debugging connection recovered in 3.3s
  without a new authorization prompt. The RNDIS/ADB configuration was retained.
- The 22-open recording above was performed on Samsung; no corresponding OnePlus
  image-opening recording was taken in this installation step.

## Artifact and local evidence

- Version remains 4.3.6.9-auto-multitask / 4086.
- APK: `artifacts/PixelGallery-4.3.6.9-opening-handoff-arm64-release.apk`
- Size: 28,023,507 bytes; arm64-v8a; non-debuggable normal release.
- SHA-256: `d2900e1935d119f5817d70eb2d36b68a17c76810739a55e7568c5f85ce1c3649`
- Installed `base.apk` SHA-256 matched that artifact.
- Local evidence: `scratch/opening-handoff-20261114/` contains the negative
  control XML/log, successful build log/test summary, opening settings screenshot,
  recording/actions, all 11 image screenshots, photo identity comparison, Details
  sheet dump, black-frame analysis and the intermediate animation frame.
- An earlier auxiliary recording used three skyline pictures from the current
  scrolled grid. Those are different pictures and are not a paired speed baseline
  for the supplied 11 JPEGs.
