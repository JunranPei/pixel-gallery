# Album ordering interaction verification — 2026-11-04

Reference: https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures (event consumption, touch slop, real injected pointer events).

Environment: emulator-5562, GalleryAlbumSearchQA20261019, com.pixel.gallery.albumgroups.test. Source test: AlbumOrderUiChecks. UUID-scoped MediaStore fixtures; existing grouping/sort/enabled settings restored in finally. Actual MainScaffold, not a replacement grid.

| ID | Case / expected | Level | Result | Evidence |
|---|---|---|---|---|
| ~~O1~~ | Selected unpinned group member directly drags in Custom; persisted order changes | real UI + DataStore | passed | album-order-qa |
| ~~O2~~ | Pinned group members reorder within group | real UI + DataStore | passed | album-order-qa |
| ~~O3~~ | Group sort accessible only in More | real UI | passed | album-order-qa |
| ~~O4~~ | Clear group selection dismisses sort dialog | real UI | passed | album-order-qa |
| ~~O5~~ | Ungrouped Custom direct drag | real UI + DataStore | passed | album-order-qa |
| ~~O6~~ | Ungrouped pin queue drag | real UI + DataStore | passed | album-order-qa |
| ~~O7~~ | Name sort then Custom retains custom and pinned orders | real settings | passed | album-order-qa |
| ~~O8~~ | Thumb has no pin badge, selected pin button changes state | real UI / screenshot | passed | album-order-qa |
| O9 | New items append, absent items retain position, old JSON loads | model | passed previous turn | AlbumOrderStateTest |
| O10 | Storage write failure, rotation during gesture, cross-scope rejection | mixed | 未测 | 本轮未注入存储故障、未验证旋转及跨范围拖拽 |

Original defect: selected items waited for a second long-press; motion over touch slop before timeout cancelled the operation. Fixed path claims a drag from an already-selected reorderable item at touch slop. Regression checks must still verify ordinary taps and range selection.

## Evidence and limits

- Before: `scratch/album-order-before-device.log` reproduces group custom drag leaving persisted order unchanged.
- After: `scratch/album-order-after-device-final.log` records all six real UI workflows passing.
- Screenshots and installed APK SHA256: `scratch/album-order-evidence/`.
- First after-run reached ungrouped custom success, then injection targeted a hidden toolbar. Fixed test fixture layout; this was not counted as product success.
- New fixture media deleted; existing grouping, sort and grouping-enabled preferences restored. Tests do not claim device restart, write-failure injection, rotation, or cross-scope rejection coverage.
- Pin button uses filled icon when pinned and outlined icon otherwise; no thumb badge. Screenshot inspected.
