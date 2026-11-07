# Anchored overflow motion and motion audit

Reference: FlClash `lib/views/proxies/proxies.dart` → `_OverflowMenuButton` → `PopupAnchor`, backed by [prism_ui popup.dart at 63450c0](https://github.com/chen08209/prism_ui/blob/63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185/lib/popup.dart) and [motion.dart](https://github.com/chen08209/prism_ui/blob/63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185/lib/motion.dart).

The borrowed motion principles are anchor-to-menu geometry, a damped spring response, a later corner response, content fading early on close, and closing from the displayed intermediate shape. The Android adaptation retains Gallery's opaque MD3 surface, existing 32 dp final corners, width, content and theme colors. It uses one morphing surface, not prism_ui's liquid-glass shader/two-droplet merger or control bitmap capture.

Opening: mass 1, stiffness 400, damping ratio 0.754 over 450 ms. Corners: stiffness 324, ratio 0.75, delayed 70 ms. Return: stiffness 165, ratio 0.8 over 500 ms; content leaves in the first 100 ms. Endpoints are exact; rapid reversal captures the currently displayed geometry. Progress is evaluated in drawing/layer phases rather than recomposing the menu or grids each animation frame. No media/index changes.

The full-screen popup owns dismissal touches while open/closing. Back and the source button close it; the source button can reopen during closing. Closing menu content cannot receive actions. Item callbacks retain their existing behavior. Android [PopupProperties](https://developer.android.com/reference/kotlin/androidx/compose/ui/window/PopupProperties) supplies focus and Back ownership.

## Validation

| Case | Expected | Result |
| --- | --- | --- |
| Geometry endpoints/overshoot/corners | Exact endpoints, valid radii, modest overshoot | Pass, unit assertions |
| Interrupt opening/closing | Position continuity | Pass, unit assertions and real touch reversal |
| Opening and Back closing | Intermediate frames rendered; popup remains until finished | Pass, runtime frame listener |
| Source-button close/reopen | Responds during motion | Pass, real touch |
| Outside tap | Dismisses without tapping underlying content | Pass, underlying sentinel remains untouched |
| Menu item and Settings | Existing callback executes once | Pass, action counter |
| Light/dark | Original Gallery surfaces and legible text | Pass, settled screenshots reviewed |

Runtime host uses the production GalleryOverflowButton/MenuRow/Settings entry in an isolated MainActivity composition. It is a component integration check, not every gallery route. Motion state and actual touch results are recorded; light/dark settled screenshots are for visual review.

Unit evidence: four tests, zero failures in `scratch/overflow-motion-unit.log` and `app/build/test-results/testDebugUnitTest/TEST-com.pixel.gallery.ui.components.OverflowMotionTest.xml`. Device evidence: `scratch/overflow-motion-device-final.log`, `scratch/overflow-motion-evidence/frames.json`, `result.txt`, and settled screenshots. Device is emulator-5562, independent debug package `com.pixel.gallery.multitask.test`.

The real MainScaffold album-order regression also passes all nine cases, including group sorting through overflow and dismissing that sorting sheet when group selection is cleared. Evidence: `scratch/overflow-motion-order-regression.log`. This additionally covers grouped/ungrouped custom and pinned dragging, remembering custom order, group dragging and type-boundary rejection.

Visual review found the source icon handoff initially missed the retained-state change. The state is now observed in composition; the entire original IconButton, including its ripple, is hidden while the popup owns its visual. An isolated test-host dark-text issue was corrected by using the normal theme Surface. These were corrected before accepting final visuals; no media assets or rendering behavior were changed.

## Other candidates (not changed in this task)

| Surface | Current implementation | Suitable use |
| --- | --- | --- |
| Expanded group surface/cards | Separate 350 ms tween progress/placement | Shared progress, critically or slightly overdamped; avoid contour overshoot into neighbours |
| Other cards yielding during reorder | 180 ms tween while dragging | Tight near-critical spring; dragged card stays directly pointer-controlled |
| Selection half-page settling | 180 ms reveal and 150/180 ms dismissal | Continue release velocity with a constrained spring; direct drag stays synchronous |
| Search header settling | 180 ms tween after horizontal drag | Velocity-aware settling and reversal |
| Root navigation | Already a spring, damping 1.10/stiffness 520 | Keep current responsive trigger; no blanket replacement |
| Selected thumbnail size/corners | Already springs | Tune only if separately requested |

Existing tween animations also use easing, so they are not all constant-speed. This is not a recommendation to give opacity, color, scrolling, image zoom or directly dragged content menu-like bounce.
