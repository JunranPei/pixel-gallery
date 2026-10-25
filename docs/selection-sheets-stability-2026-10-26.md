# Selection sheets and Quick Sort stability — 2026-10-26

## Changes

### Latest revision after user testing

The user provisionally accepted the lower-row touch fix. The current revision replaces the temporary Dialog with the same inline surface as Quick Sort, so the media grid is accessible behind a collapsed panel. The earlier implementation and checks below describe the preceding delivered build.

- All three panels require two downward gestures. A gesture beginning expanded only collapses. A new gesture beginning fully collapsed uses a 40 dp maximum threshold, capped at 60% of the remaining distance to the screen edge after touch slop. Visible movement remains 35% of finger travel. The earlier fixed 96 dp threshold was unreachable from many points on the compact header, as the user reported. Cancellation returns in 150 ms; no repeating idle animation.
- Move and Label have no header back arrow. All three titles share the larger 24 sp style. Move and Quick Sort call the same 56 dp circular undo component, including disabled appearance and handedness; Move's undo floats above its panel instead of in the header.
- Picker body scrolling no longer expands/collapses the sheet. Only its header/handle controls sheet motion. Main-page nested-scroll connections are attached to the base grid layer, outside the picker hierarchy; picker pinch-column changes also have separate state. The grid viewport itself clips all four corners using its thumbnail radius (20 dp Quick Sort, 32 dp Move/Label); the previously added header/body corner decoration is removed. All picker surfaces use one color, and Move/Label titles are centered independently of header actions. Quick Sort's forced one-line caption is removed so all picker album titles use the existing full-name preference.
- Accepted downward dismissal translates the existing compact surface offscreen over 180 ms before removing it. Rejected pulls return over 150 ms. This is a finite graphics-layer animation with no repeating idle work. Build/install log for this refinement: `scratch/picker-grid-corners-dismiss-build.log`; visual acceptance remains with the user.
- Selection mode is derived from selected media or an open selection picker. With neither, it exits immediately. Entering Quick Sort therefore shows selection mode even at zero; dismissing any picker preserves selected media and exits selection mode only when the count is zero. The separate empty-selection latch has been removed. Label's header confirmation button is removed; closing still saves edits and preserves selection. Latest build: `scratch/picker-selection-principle-build.log`.
- Operations preserve the panel's expansion and retain empty media selection mode only while a picker is open. Label saves pending edits on close without clearing selected media; its former header confirmation control has been removed.
- Local Move uses the Quick Sort queue with the selected conflict policy and the existing checkpoint/permission/replacement recovery logic. Its header Undo uses the same session undo policy. Copy and SAF destinations retain the original adapter; SAF moves do not support undo.
- Both local Move and Quick Sort retain source slots until the whole batch is terminal, then remove successful items together. Failures/cancelled items remain. Removal waits for active grid touch/scroll to end. Undo restores successful items together. Trimmed queue history cannot make removed slots reappear.
- Quick Sort's selection-menu icon is accented. The redundant clear control beside the Selected tab is removed; the header chip remains.

Latest verification: CompactSheetPullTest, QuickSortBatchVisibilityTest, QuickSortGridPresentationTest and QuickSortSelectionTest passed the first build. Delivery additionally checks Quick Sort undo/persistence, album creation and transfer helpers. Logs: `scratch/persistent-picker-batches-build.log`, `scratch/persistent-picker-final-build.log`, `scratch/persistent-picker-delivery-build.log`. Instrumentation is compiled only. Two-stage gestures, repeated-group selection, panel position and Move undo remain for user UI acceptance; no emulator gestures are injected.

### Preceding build

The user narrowed the touch failure to Label and Move/Copy: a tap below the first row disables content interaction, but the sheet can still be dragged and dismissed. Quick Sort's persistent chooser is unaffected. The exact runtime failure has not been reproduced here.

Both affected sheets used a fixed 720 dp child plus an offset-dependent viewport inside ModalBottomSheet. The replacement measures one visible surface inside a Dialog, with half/full/hidden anchors and nested scrolling. It retains the header, colors, grid, scrollbar, keyboard expansion and dismissal guard. The persistent Quick Sort sheet is unchanged. Removing the offset/viewport dependency is a proposed fix; user reproduction remains the acceptance gate.

Quick Sort keeps the session's grid slots fixed. Per-item database commits continue normally; they change availability rather than slot position. Moved slots are static placeholders and cannot be selected again. Failed moves remain selectable if the source exists. Undo can restore the original path even with a different MediaStore ID. Leaving Quick Sort restores the live list. Explicit filter/search changes reset the presentation. No continuous animation or new decoding is added for completed slots.

Both album choosers now create from the search query with an inline Add action. Invalid names and duplicates under the actual parent are excluded; errors retain the query. Quick Sort still creates under Pictures; Move/Copy retains its selected-parent/Pictures behavior. Search still matches paths, but its placeholder only says Search albums. Selected media can enter Quick Sort from the library, album or label overflow menu.

## Sources

- [Compose gesture event consumption](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)
- [Compose Dialog](https://developer.android.com/develop/ui/compose/components/dialog)
- [Material3 1.5.0-alpha17 sources](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.5.0-alpha17/material3-android-1.5.0-alpha17-sources.jar): ModalBottomSheet.kt and SheetDefaults.kt inspected locally. No dependency upgrade.

## Verification

| ID | Case and expectation | Level | Result |
|---|---|---|---|
| S01 | Label second row/lower blank space, then first row and search remain responsive | User UI | Pending |
| S02 | Move/Copy second row/lower blank space, change destination and use actions | User UI | Pending |
| S03 | Half/full/keyboard/back/interrupted drag; no stuck scrim; save guards retained | User UI | Pending |
| S04 | Quick Sort persistent chooser remains usable | User UI | Pending |
| S05 | Invalid, overlong and duplicate album names rejected; other-parent duplicate allowed | JVM | Pending |
| S06 | Inline create succeeds; errors preserve query; empty album can be reselected | User UI | Pending |
| S07 | Incremental removal preserves indices and next range selection | JVM + user UI | Pending |
| S08 | Same-ID move, failure/cancel, undo with new ID, live reorder and all-removed slots | JVM | Pending |
| S09 | Existing lift/range and target-selection safety | JVM | Pending |
| S10 | Tap retains anchor; short drag returns; down-fling from full first collapses | JVM | Pending |
| S11 | Library/album/label selected overflow enters Quick Sort without clearing selection | User UI | Pending |

Build logs: `scratch/picker-stability-build.log`, `scratch/picker-stability-final-build.log`. The existing RedesignTouchChecks fixture now expects inline creation; it is not executed in this turn. No emulator touch input is injected. Storage, Room transactions, recovery and image indexes are unchanged. UI acceptance and hardware power remain unmeasured.
