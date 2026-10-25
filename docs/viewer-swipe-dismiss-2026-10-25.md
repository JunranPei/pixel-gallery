# Image viewer vertical dismissal

## Interaction

A single finger starting in the media area can drag the fitted image upward or downward. On release, a deliberate drag (22% of viewport height, bounded to 64–160 dp) dismisses the detail page. A shorter same-direction fling needs at least 32 dp travel and 1200 dp/s. Small drags return to rest. A strong reverse fling cancels the dismissal even after a long drag.

Direction is decided once per stroke after touch slop: vertical movement must exceed horizontal movement by 1.3 times. Horizontal or ambiguous diagonal starts remain with the existing content/pager. Any second finger rejects dismissal for the entire stroke. If a vertical drag has already claimed the stroke, adding a finger returns the page to rest and consumes the tail until release.

Only the settled image contributes eligibility. Preview gestures query the live rendered scale and zoom-animation state; the tile renderer exposes a read-only fit/rest query. Zoomed images retain their existing pan behaviour. Videos and playing motion photos do not dismiss this way. The toolbar and bottom action region keep their existing controls, including the action reveal gesture. Existing two-finger rotation and pinch are forwarded intact when dismissal has not claimed a stroke.

The shared activity dispatcher sends one ACTION_CANCEL to both Compose/native content when dismissal wins, matching Android interception semantics. Viewport changes, leaving the route, activity stop and viewer modal states cancel/reset the new interaction.

## Animation and power

The viewer surface follows the finger through a graphicsLayer translation. Release runs one finite tween: 180 ms to dismiss, or 150 ms to return. The image is not scaled or remeasured for this animation. There is no screenshot, blur, crossfade, offscreen alpha buffer request, thumbnail matching, or pre-rendering of the gallery underneath. The native decoder, index identity, image transform memory and tile geometry are unchanged.

Offset state is read inside the graphicsLayer lambda, not in composition/layout. The new recognizer only receives touch events; it has no polling task, timer or sensor listener. The animation coroutine stops at completion or cancellation. This avoids adding continuous idle rendering; it does not claim zero energy cost for the short animation. Actual battery/thermal impact remains unmeasured.

## Evidence and handoff

The pure recognizer tests cover both directions, jitter, paging/diagonal priority, denied starts, cancellation, reversal, short/fast drags, landscape thresholds, density and repeated strokes. Final source verification passed `:app:assembleDebug` and 31 targeted unit tests (14 dismissal, 14 rotation/resize, 3 image handoff), with zero failures. Log: `scratch/viewer-dismiss-final-build.log`, successful in 3m 5s. The debug APK was installed successfully on emulator-5556 as Gallery Test (`com.pixel.gallery.multitask.test`). These checks establish compilation and the listed recognizer cases, not end-to-end gesture or power acceptance.

The user performs UI acceptance. No emulator input is injected. Manual cases: drag up/down and short return; double tap then pan a zoomed image; pinch/rotate without dismissal; add a finger during an exit drag; horizontal page swipe; bottom action reveal; toolbar taps; rotate during a returning drag; navigate back and reopen; background the app during a drag. Check both preview and indexed tile images, portrait and landscape. Verify that returning from a cancelled drag leaves the same image scale/centre.

## Official sources consulted

- [Compose animation performance](https://developer.android.com/develop/ui/compose/animation/quick-guide): prefer draw-phase updates with graphicsLayer for property animations to avoid per-frame recomposition/layout.
- [Compose phases](https://developer.android.com/develop/ui/compose/performance/phases): defer frequently changing state reads to the phase that needs them.
- [Android touch interception](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup): cancel the child when the parent takes ownership; retain ordinary click/content handling otherwise.
