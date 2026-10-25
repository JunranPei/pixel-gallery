# Image viewer vertical dismissal

The original whole-page slide version is preserved in commit `fc8cf575`. The revision below follows the user's rejection of that visual treatment and approval of an image-to-thumbnail transition.

## Interaction

A single finger starting in the media area can drag the fitted image upward or downward. On release, a deliberate drag (22% of viewport height, bounded to 64–160 dp) dismisses the detail page. A shorter same-direction fling needs at least 32 dp travel and 1200 dp/s. Small drags return to rest. A strong reverse fling cancels the dismissal even after a long drag.

Direction is decided once per stroke after touch slop: vertical movement must exceed horizontal movement by 1.3 times. Horizontal or ambiguous diagonal starts remain with the existing content/pager. Any second finger rejects dismissal for the entire stroke. If a vertical drag has already claimed the stroke, adding a finger returns the page to rest and consumes the tail until release.

Only the settled image contributes eligibility. Preview gestures query the live rendered scale and zoom-animation state; the tile renderer exposes a read-only fit/rest query. Zoomed images retain their existing pan behaviour. Videos and playing motion photos do not dismiss this way. The toolbar and bottom action region keep their existing controls, including the action reveal gesture. Existing two-finger rotation and pinch are forwarded intact when dismissal has not claimed a stroke.

The shared activity dispatcher sends one ACTION_CANCEL to both Compose/native content when dismissal wins, matching Android interception semantics. Viewport changes, leaving the route, activity stop and viewer modal states cancel/reset the new interaction.

## Image-to-thumbnail animation

Only the current image moves with the finger, with 0.8 drag resistance and a bounded reduction to 86% of its displayed size. The black backdrop fades separately, and controls fade in place. The grid underneath stays stationary and becomes visible only during the interaction. Its input and accessibility are suppressed while the viewer is on top.

Visible photo tiles register their URI/version, route scope and window coordinates; no library-wide lookup or scroll-to-target is performed. Anchors must be placed in the current reveal epoch and fully visible. The current thumbnail is temporarily hidden to avoid drawing a duplicate. On release, a valid target receives a 240 ms transition from the current image position/size to its thumbnail bounds and corner radius. Source pixels retain their aspect ratio while the clip rectangle continuously changes from Fit to Crop. A missing/offscreen/partly clipped target uses a 180 ms fade at the dragged position. A cancelled drag restores image, backdrop and controls in 180 ms.

One final frame is allowed to present before navigation removes the viewer. The covered grid retains its base route's bottom padding to avoid a second layout shift when returning. Runtime fallback images with missing metadata use the decoded drawable's intrinsic dimensions for their fit geometry.

## Power and index boundaries

The image's native layout, decoder, index identity, tile geometry and saved zoom transform stay unchanged. GPU scale/translation and clip changes transform the existing display output; they do not invoke image zoom APIs. There is no screenshot capture, bitmap copy, blur, decoder replacement or index rebuild. The image fade uses ModulateAlpha rather than requesting a full-window offscreen alpha buffer.

Animation properties are read in graphicsLayer/drawing lambdas. Grid reveal and restoration cause bounded placement/composition work at interaction boundaries, not one list update per animation frame. Thumbnail registrations are bounded by composed cells and removed on disposal. No timer, sensor listener or idle animation is added. The animation coroutine ends on completion/cancellation; the underlay is hidden again at rest. These choices bound the extra work to interaction, but the animation still has GPU blending/drawing cost and actual device power remains unmeasured.

## Evidence and handoff

The preserved whole-page version passed 31 targeted tests (`scratch/viewer-dismiss-final-build.log`). The revision adds seven geometry checks: matching the dragged first frame, portrait/landscape landing bounds, continuous crop without empty pixels, maintained aspect ratio, destination corner radius, cancellation restoration, and stationary fade fallback. Existing dismissal/rotation/image-handoff cases remain in the regression suite.

Final delivery build: `:app:assembleDebug` and the four targeted unit-test suites passed in 1m 53s (`scratch/viewer-thumbnail-delivery-build.log`). All 38 tests passed: dismissal 14, rotation 14, image handoff 3, geometry 7. The resulting normal debug APK was installed with `adb install -r` on `emulator-5556` (Gallery Test); installation returned `Success`. No UI gestures were performed by the agent. Visual acceptance and physical power measurements remain pending.

The user performs UI acceptance. No emulator input is injected. Manual cases: drag up/down and short return; double tap then pan a zoomed image; pinch/rotate without dismissal; add a finger during an exit drag; horizontal page swipe; bottom action reveal; toolbar taps; rotate during a returning drag; navigate back and reopen; background the app during a drag. Check both preview and indexed tile images, portrait and landscape. Verify that returning from a cancelled drag leaves the same image scale/centre. Check current-photo landing after paging, visible vs offscreen targets, square Crop from both portrait and landscape sources, header/footer fading without translation, and the restored grid's position. `VIEWER_DISMISS_SETTLE` logs whether a valid thumbnail destination was found, once per settling animation.

## Official sources consulted

- [Compose animation performance](https://developer.android.com/develop/ui/compose/animation/quick-guide): prefer draw-phase updates with graphicsLayer for property animations to avoid per-frame recomposition/layout.
- [Compose phases](https://developer.android.com/develop/ui/compose/performance/phases): defer frequently changing state reads to the phase that needs them.
- [Android touch interception](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup): cancel the child when the parent takes ownership; retain ordinary click/content handling otherwise.
- [Loupe](https://github.com/igreenwood/loupe): separate image dragging, background opacity and shared-element dismissal.
- [Landscapist gallery](https://skydoves.github.io/landscapist/gallery/#give-both-sides-the-same-content-scale): Fit/Crop mismatch can cause a discontinuous reframe.
- [Compose shared element resizing](https://developer.android.com/develop/ui/compose/animation/shared-elements/customize#resize-mode): distinguish graphical scaling from repeated measurement/layout.
- [Telephoto issue 47](https://github.com/saket/telephoto/issues/47): premature removal during dismissal can produce choppy transitions.
