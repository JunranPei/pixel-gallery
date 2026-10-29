# Viewer rotation

## Interaction

- Images and videos use the same two-finger gesture to switch portrait/landscape. The former Rotate button is removed.
- A pair must start on the media, outside the header, file actions, playback button and seek bar. Fingers must be at least 48 dp apart.
- A rotation-dominant movement of at least 12 degrees claims the gesture; the content receives one cancellation. No live picture rotation is applied.
- At least 35 degrees must remain when the pair is released. The screen changes only after **all** fingers lift, once per stroke, in either turning direction. Turning back below the threshold cancels the change.
- Pinch/pan that wins first stays pinch/pan. An existing one-finger drag, a third finger, replaced fingers, crossing/too-close fingers or cancellation cannot trigger rotation.
- The recognizer is disabled off the viewer, in PiP/multi-window, while paging or while viewer dialogs are open. Ordinary taps/double taps and controls retain their existing handlers.
- Leaving the viewer restores the activity's previous requested orientation.

## Display settings and optional memory

Display retains Video player as its third group and adds a separate Images group. Each has four default orientation choices (system, lock current, landscape, portrait), a Remember orientation switch and a Remember zoom and position switch. The user explicitly chose to support images as well as videos.

Orientation and zoom memory are per item/version. When enabled, a saved orientation overrides the default for that item. With memory off, revisiting an item uses the default orientation and fits the image/video to the viewport. Disabling a memory switch clears that type's old records. Image zoom memory defaults on to preserve existing behaviour; video zoom memory and both direction-memory switches default off.

Image renderer state is still retained during a single visit for preview/tile handoff, even when cross-visit memory is disabled. Persistent zoom snapshots are taken when leaving media or stopping the activity, not per frame. History is bounded and writes skip unchanged values. Turning off zoom memory does not delete image indexes or tile caches.

## Rendering, indexes and power

The activity already handles orientation and screen-size configuration changes without recreation. This implementation changes its requested orientation; it does not rotate/re-encode media, change EXIF or replace video SurfaceView with TextureView.

Index identity and all image decoder/index-store code are unchanged. Source paths, modification versions, tile coordinates and index files are not rewritten by rotation. A changed viewport can require newly visible tiles or a different sampling level. That is normal bounded rendering work, not index rebuilding.

The recognizer has no sensor listener, timer or frame loop. Pure rotation movement is withheld from the image zoom handler, avoiding a rotation-induced preview-to-tile handoff. The preview is retained across viewport changes. Fit stays fit, and zoomed images retain their pixel scale and source centre subject to new viewport bounds. The tile renderer samples the **old** viewport centre during resize rather than interpreting the new centre as the old one.

These are code-level properties, not measured device battery results. Physical-device power/thermal results and visible gesture smoothness remain unverified.

## Validation / manual handoff

No automatic emulator interaction is run: the user requested to do UI testing themselves. Unit checks cover angle boundaries, clockwise/counterclockwise, cancellation, zoom/pan priority, pointer replacement, close fingers, multiple turns, the remaining-finger tail and preview resize scale.

Final verification on 2026-10-25: `:app:assembleDebug` and the targeted unit suite passed (14 rotation/resize checks, 3 existing gesture-handoff checks, 3 preference migration checks; zero failures). Final log: `scratch/rotation-package-verified.log` (2m 6s). APK: `app/build/outputs/apk/debug/app-debug.apk`, application ID `com.pixel.gallery.multitask.test`. This confirms packaging and the listed code checks, not device interaction or power acceptance.

Manual checks: fit and deep-zoom indexed images, ordinary/animated fallback images, playing/paused video, both orientation directions, reverse gesture before release, one-finger tail, slider/double-tap preservation and return from PiP. Check the watched region after resizing, and verify video position/play state survives. Check each default orientation and each memory switch separately for images/videos, including reopening the same item, paging away/back, disabling then reenabling memory and restarting the app.

References:
- Android gesture dispatch: https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures
- Configuration handling: https://developer.android.com/guide/topics/resources/runtime-changes
- SurfaceView performance/power: https://developer.android.com/media/media3/ui/surface

## User review fixes

The user confirmed the rotation gesture works. The retained gallery route now hides its corner decoration together with its content, and the viewer owns an opaque black background. This fixes the pale header/body corners visible through the immersive viewer.

The secondary action row distributes buttons across the available width, retaining horizontal scrolling on narrow windows. Video progress controls use the measured bottom action area height instead of a fixed landscape offset, including while the second row is revealed. The existing Slider and action icons are unchanged. These layout fixes are built and handed to the user for visual acceptance; no automated emulator interaction is used.
