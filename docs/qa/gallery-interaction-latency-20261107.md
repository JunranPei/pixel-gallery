# Gallery interaction latency — 2026-11-07

Scope: finish shared group state isolation first, then the main navigation indicator delay and album reorder tracking. Preserve the established UI and sort/pin/type boundaries. Use runtime state, coordinates and real injected touch events; no image recognition.

References:
- https://developer.android.com/develop/ui/compose/layouts/pager — `settledPage` stays unchanged until scrolling/animation finishes; `targetPage` represents the intended destination.
- https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-composables — detecting dragging does not move content; rendering must use the pointer displacement.

| Case | Expected | Status / evidence |
| --- | --- | --- |
| G1: 3 viewer tasks, 1 album task, repeated group toggles | Only current grid rebuilds; no media index work | PASS: 96 taps, 96 grid rebuilds, zero index/filter work; `scratch/group-multiwindow-evidence/listen-retained.json` |
| G2: Return from viewer; return to previously stopped Home | Latest shared expanded/collapsed state, no stale state | PASS: both return states and stopped-Home isolation asserted |
| N1: Tap Photos/Albums | Indicator starts before pager settles | PASS: both directions false on baseline, true on fixed; actual indicator animation weight sampled on draws |
| N2: Reverse selection during animation; swipe | Final page and indicator agree | PASS: fixed run, real tab request reversal and touch swipe |
| N3: Hidden/Lock navigation | Existing route selection remains immediate | PASS: reveal each side through existing accessibility action, tap route and return to Albums |
| D1: Drag inside a cell, then across cells | Dragged card follows pointer continuously; persisted order correct | PASS: pointer 69.76 px; baseline card 0 px, fixed card 70 px; crossing persists and reverse crossing works |
| D2: Group/ungrouped custom and pinned lanes | Reordering works within each lane | PASS: real group custom, group pins, ungrouped custom, ungrouped pins; persisted state checked |
| D3: Label/ordinary group boundary and pin boundary | Invalid cross-lane moves rejected | PASS: actual group type crossing rejected. Pin validation unchanged, separate cross-pin gesture not run in this pass |
| D4: Cancel/multitouch and edge scrolling | No stuck drag; selection and scroll still usable | Cancel PASS (0 px residual); multitouch and long edge-scroll not run in this pass |

Device: emulator-5562, independent debug package. Results do not establish the absence of every phone-specific Surface/GPU flash.

Model regression: AlbumOrderStateTest (7), AlbumRangeSelectionTest (4), PendingGroupExpansionTest (3): 14 passed, zero failures. `scratch/gallery-interaction-fixed-build.log`.

Instrumentation setup corrections: rapid switching initially addressed a changing accessibility target; an early probe also observed scroll completion only when another root state recomposed, leaving a stale scrolling flag. These failed test setup attempts are excluded from acceptance evidence. They are not reports of a new application scroll hang.

## Runtime comparison

`scratch/gallery-interaction-baseline.json` and `scratch/gallery-interaction-fixed.json` contain per-draw navigation state/animation weight plus accessibility-reported card coordinates. Their matching `.log` files contain completion/assertion results. No screenshots were captured.

The accepted baseline intentionally reports failing observations without throwing: indicator-before-settled is false in both directions, within-cell displacement is zero, and reversing across the same neighbour without lifting fails. Its swipe entry is only a stationary rightmost-page control, and rapid reversal is skipped because of the baseline test target race. The fixed run asserts all corrected results and additionally tests actual Photos-to-Albums swiping and rapid reversal.

Production changes: root navigation selection reads the pager destination instead of the settled page. The dragged card renders the pointer's displacement in its graphics layer with no placement animation; other cards still animate into their slots. The gesture forgets the prior neighbour after its own slot reaches the pointer, allowing reverse crossing. Persistence and sorting/pinning scope validation are unchanged.

`scratch/gallery-interaction-order-regression.log` passes all nine existing real UI/state cases: grouped custom, grouped pins, overflow sorting/deselect dismissal, ungrouped custom, ungrouped pins, saved order across sorting changes, label group dragging, ordinary group dragging/type-crossing rejection, and moving a whole expanded group by targeting its member. Text/coordinate snapshots replace screenshot capture in this run.

Final debug application APK SHA-256: `f8e6daf72be45c1433d16a4064bb218de86d5ba5a3b7c3f2701bc713b782d57b`. Production APK/version/release state was not changed by this task. No phone is currently connected; original-device flicker remains unverified, and the group isolation measurements do not prove zero remaining frame stalls.
