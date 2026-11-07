# Shared spring motion, 2026-11-07

Scope: group geometry/placement, reorder neighbours, selection-sheet settling, search-header settling, primary pager and navigation. Existing layout, theme and direct pointer motion preserved.

Reference: https://developer.android.com/develop/ui/compose/animation/customize (spring stiffness/damping and interruption). Group/layout uses critical damping with stiffness 520; reorder neighbours 850; pager/navigation 620. Fraction threshold .001. These are distinct response families, not menu bounce copied everywhere. Group background and lazy-grid placement share response parameters; they remain separate animation owners, not one clock. The unchanged rounded-band union is retained.

Sheets retain release speed in normalized coordinates, clamp public geometry, cancel settling on grab and avoid duplicate restarts from external expanded-state delivery. Search settling accepts release velocity and can be interrupted by a new drag. Pager tap and snap use the same spring as navigation; navigation still reads targetPage immediately.

Cost reductions: group background caches each group's union Path by exact bands; unchanged geometry reuses it, including selection-color redraws. Scratch Path, Paint and CornerPathEffect are reused, and an unused per-draw row-height map and zipWithNext allocations are removed. Removed groups are evicted. Drawing stays in draw phase, with no new media/index work, blur, bitmap capture or persistent animation loop. Height-changing sheets still require layout; no claim of eliminating all per-frame work or measured battery savings.

Validation:
- Spring bounded response, sheet velocity/reversal/grab/cancel: PASS, 3 tests in `scratch/shared-motion-unit-final.log`.
- Compact dismissal threshold/gesture rules: PASS, 5 tests in the same log.
- Main pager/navigation, rapid reversal and direct drag: PASS, `scratch/shared-motion-interaction.log`: indicator starts before pager settles in both directions; rapid reversal and swipe converge; direct drag error 0.24 px and cancel residue 0 px.
- Group/ungrouped custom and pin dragging, sorting: PASS, 9 cases in `scratch/shared-motion-order.log`.
- Selection half-page actual touch: PASS, production component host with real pointer events; height follows MOVE before UP and CANCEL restores anchor. `scratch/shared-motion-host.log`.
- Search header: PASS, real pointer opening, re-grab during settling, hold and cancellation converge. Same host log.

No release build, version bump, commit or push requested.

Test setup corrections: initial unit checks used short wall-clock waits with a synthetic frame clock; Windows timer granularity meant the spring had only advanced several frames. Waiting for the exact endpoint with a bounded timeout passes. The old quick-sort-header suite passed its sheet assertions then failed on an unrelated obsolete Label backup text; a scoped shared-motion branch now runs sheet checks and search-header gestures without that settings section.

The path-cache optimization has been code-reviewed and exercised by the runtime grid suites. CPU, GPU and battery improvements have not been benchmarked; device-specific visual overlap/rounding across every column count is not established by these checks.
