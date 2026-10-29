# Viewer rotation, video dismissal, and scrollbar reach

## Interaction contract

- A two-finger twist changes image/video pixels while MOVE events arrive; it never calls Activity orientation APIs. Touch slop separates accidental jitter from a deliberate twist. Pinch/pan that already won remains pinch/pan.
- Release commits one signed quarter turn at 35 degrees, or returns to the prior angle. The 160 ms snap is finite. A cancelled stroke cannot persist an intermediate angle.
- The graphics layer updates during dragging. Layout constraints swap once at a settled quarter turn; fit compensation keeps the visible bounds consistent across that remeasure. Native image decoders, source identity and persistent index formats remain unchanged.
- Content angles use separate `image:rotation:` / `video:rotation:` memory keys. Old Activity orientation integers are not interpreted as content rotation. The optional screen-direction setting remains independent.
- Ordinary video playback keeps SurfaceView. A twist or retained non-zero content angle uses TextureView on the existing playback session. Returning to zero restores SurfaceView. The user explicitly accepted this tradeoff; actual device power and surface-switch smoothness remain manual acceptance items.
- Video swipe dismissal shares image dismissal, excludes controls and requires fit zoom. It captures at most one bounded video frame (long edge at most 1440 px) per revealed dismissal; animation transforms that frame instead of repeatedly reading back the decoder surface. A failed capture retains the existing surface. Returning cancels the transition and restores live video.
- Rotated media fades at the end rather than snapping into an unrotated grid thumbnail. Unrotated media retains the existing thumbnail transition.
- Header and its concave lower edge use the same precomposed underlay and opacity during dismissal.

## Scrollbar

The visible handle remains 26 × 40 dp including its padding. Its separate hit target is 48 × 48 dp and follows only the handle. The long track retains its original 26 dp width so the expanded hit target does not block a whole column of photo gestures. Idle visibility increases from 1 to 2 seconds. Grabbing the handle retains the finger offset instead of jumping to the finger centre. Drag ownership is reported synchronously.

## Ordinary move vs quick sort: source audit

These are not one implementation:

| Stage | Ordinary transfer | Quick sort |
| --- | --- | --- |
| Entry | `PhotosViewModel.executeTransfer` → `transferMedia` | persistent queue → `moveForQuickSort` |
| Immediate UI | running/progress state; summary after completion | clears accepted selection, reserves entries and shows an overlay; work continues in the queue |
| Same-volume fast path | MediaStore path update; returns when provider reports an updated row | MediaStore path update, then waits for filesystem/provider agreement and verifies bytes/timestamps |
| Other paths | direct rename or verified copy/removal; SAF support | verified temporary/local copy, persistent checkpoints and undo receipts; no SAF destination |
| Library refresh | forced full sync in `finally` before reporting final progress/summary; then ViewModel refresh | after 20 changes or 2 seconds, at job boundaries and on exit |
| Index migration | direct rename/copy paths relocate indexes; the successful MediaStore update branch returns before that helper | `finishQuickMove` relocates indexes and remaps labels where needed |

The visible completion timing differs, but this audit is not a device benchmark. It does not demonstrate that ordinary byte movement is slower. Quick sort performs more verification work; its queue permits further interaction while processing continues. Reusing the same safe move core should preserve SAF/conflict semantics and recovery/undo checkpoints. No file-operation semantics were changed in this UI revision. The early MediaStore return in the ordinary path needs attention in a dedicated unification change.

## Design proposals

The user selected a top-anchored horizontal speed strip, combining the anchor from A with the ordered row from B. This replaces the speed dialog with immediate application and no confirmation. The strip has 48 dp choices, scrolls when needed, and keeps the viewer controls visible while open.

`player-landscape-options.html` replaces the rejected time-row fullscreen proposal with two alternatives: in the top playback group, or at the media's upper-right corner. The landscape mock starts with the existing bottom actions collapsed, retaining the second row through the existing reveal interaction. Landscape entry still awaits selection; the mock is not proof of native layout fidelity.

### Accepted top action strip

The user's subsequent choice supersedes the two landscape entry proposals. `player-swipe-toolbar.html` previews the accepted strip, now implemented in `VideoActionStrip` and `VideoToolbarActions`:

- Initially only mute is visible at the right edge. Swipe left to reveal all five actions; swipe right to retain only mute.
- Fixed left-to-right order: mute, screen rotation, speed, frame capture, PiP.
- The screen button uses Material's `ScreenRotation` phone/arrow icon, not fullscreen corners. It toggles Activity portrait/landscape without altering content rotation or zoom preferences. Existing viewer disposal restores the prior orientation policy.
- The row translates inside a fixed clip. Dragging updates immediately; release uses a bounded 180 ms settle. Empty space to the left of the visible row cannot initiate a strip drag. The gesture claims horizontal movement after touch slop, leaving taps to buttons.
- Hidden controls are removed from accessibility semantics; a custom accessibility action expands/collapses the strip. UI auto-hide waits while the strip is being dragged/settled.
- Existing progress Slider and both bottom action rows are retained.

The final user correction adds five snap positions rather than binary open/closed: mute only; mute + rotation; through speed; through capture; all five including PiP. Stops follow actual button widths (48 / 48 / 64 / 48 / 48 dp) so no button is cut at rest. A slow release selects the nearest section; a fling selects the next boundary in its direction, not the far endpoint. Cancellation restores the section at gesture start. Accessibility expansion/collapse also advances one section, and unavailable hidden buttons are excluded from semantics and input.

Action-strip build: `scratch/viewer-action-strip-build.log`, `assembleDebug` successful in 2m03s. The HTML collapsed/expanded states were rendered and visually reviewed. Native drag/tap feel remains user acceptance; no automatic emulator gestures were sent.

## Sources and verification boundaries

- [Android multi-touch](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch)
- [Compose graphics and layer transforms](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)
- [TextureView and arbitrary rotation](https://developer.android.com/reference/android/view/TextureView)
- [Media3 surface choice](https://developer.android.com/media/media3/ui/surface)
- [Media3 battery consumption](https://developer.android.com/media/media3/exoplayer/battery-consumption)
- [Android minimum touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- [Media3 fullscreen control placement](https://github.com/androidx/media/blob/release/libraries/ui/src/main/res/values/styles.xml)

Targeted JVM tests cover gesture arbitration, cancellation, angle direction, fit continuity, dismiss thresholds and transition geometry. Native appearance, video surface handoff, pinch/rotation interaction and actual power are for user testing. No synthetic emulator gestures or real file moves were performed.

Final five-stop validation: `scratch/viewer-action-stops-build.log` completed successfully (2m04s). The four targeted test suites contain 41 tests, zero failures/errors. `adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk` returned Success for Gallery Test. The user requested a commit; no push or release build was requested. Native UI acceptance remains with the user. Earlier HTML proposals were rendered in headless Chrome and visually reviewed separately from app testing.
