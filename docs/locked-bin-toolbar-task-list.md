# Locked Folder and Bin toolbar work

> Current scope is maintained in [current-gallery-task-list.md](current-gallery-task-list.md). The user's 2026-10-25 instructions cancel additional release/arm64 packages and assistant-run tests. The older delivery and acceptance notes below are historical.

Active acceptance list. Mark an item complete only after its stated evidence exists.

1. [ ] **Keep overflow controls fixed.** Main, album, Bin, and Locked Folder grids use the same right-aligned overflow position in normal and selected states; other actions do not move it.
2. [x] **Finish Bin actions.** Use the refreshed toolbar and shared overflow; offer file-date, deletion-date, name, and size sorting; provide select all, restore, and permanent delete; remove the `Empty` action.
3. [x] **Finish Locked Folder actions.** Use the refreshed toolbar and shared overflow; offer date, name, and size sorting; provide select all, share, move out, and permanent delete.
4. [x] **Share reusable controls.** Reuse the common toolbar, sorter, and selection behavior where their layouts and actions match; selected sort options keep their shape/color feedback without a checkmark.
5. [x] **Keep locked labels usable and hidden from label grids.** Locking preserves label and Favorites memberships; the Locked Folder viewer can show and edit them; locked media does not appear in label/Favorites album grids; moving it out restores its memberships.
6. [x] **Expose label editing in locked details.** The viewer action and Details sheet offer the same add/edit-label entry point as ordinary media, including when the file has no labels yet.
7. [ ] **Build and install for user verification.** The user has explicitly taken over UI testing. Build and install without running tests or interacting with the app; the current UI acceptance stays pending their report.
8. [ ] **Deliver the requested artifacts.** Increment only the fourth version segment, commit, push and verify the remote branch, then build the arm64-v8a release package.
9. [ ] **Prevent toolbar flicker when clearing selection.** Check entering and leaving selection and screen transitions; keep the rightmost overflow anchor fixed throughout.
10. [x] **Remove ticks from the shared option selector globally.** Every `SettingsOptionControl` consumer uses shape/color feedback without a selected checkmark, including settings and sorting.
11. [x] **Fix newly created label presentation.** Pending/new labels use the same album-card layout as existing labels, including empty labels; verify creation, selection, save and reopen.
12. [x] **Unify the three half-page pickers.** Label, Move/Copy and Quick Sort reuse the common sheet and grid controls; preserve only their necessary action differences. Move/Copy follows the Label layout. Interaction restoration has passed the subsequent regression.
13. [x] **Share grid columns across pickers.** Quick Sort, Label and Move/Copy follow the album grid column setting and retain pinch adjustment/scrollbars.
14. [ ] **Keep the viewer beneath Move/Copy.** Opening the destination half sheet from a viewer must keep that viewer, current page and zoom composed underneath; dismissing the sheet returns to the same image.
15. [x] **Preserve Quick Sort interaction.** Start compact without automatic upward movement; dragging down collapses to the persistent header rather than disappearing; expansion, closing the chooser and exiting selection/Quick Sort must remain usable. Shared visual components must not force a modal interaction on Quick Sort or remove partial/full dragging from Label and Move/Copy.
16. [x] **Show Undo without target albums.** Quick Sort keeps its Undo control visible when no target album is selected; availability of an undoable job controls enabled/disabled state, not visibility.
17. [ ] **Use exactly one toolbar and one root body.** The private-constructor singleton owns the only mounted toolbar and body background. All pages, including selection, search, Locked, Hidden, Settings and viewer, supply header content only. Route or selection changes must not replace the root, mount another toolbar, or change selection geometry.

The five follow-up requirements above were recovered from the user's later messages in the previous chat on 2026-10-24. They are part of acceptance, not optional follow-up work. The user subsequently confirmed that the refreshed Locked Folder toolbar was installed.

18. [ ] **Preserve Hidden state.** List all saved hidden directories, including empty or unindexed ones. Keep file IDs, labels and Favourites memberships unchanged. Exclude hidden media from normal photos/albums, label counts/covers/grids and Favourites; unhide restores visibility using the existing relationships. Match complete directory boundaries.
19. [ ] **Preserve search and viewer interactions.** Home retains horizontal swipe between title and full-width search, without exposing the settled hidden page. Returning from selection must not request keyboard focus again. Opening Move/Labels from expanded viewer controls must retain their expanded state.

## Current revision (2026-10-25)

The earlier acceptance below is historical and does not establish acceptance of this revision. Later user reports reopened toolbar, search, selection and Hidden defects. The user now performs all UI testing; no tests or app UI operations are being run for this revision.

- Route functions now return header/content descriptions. Legacy route-local Scaffolds and actual top bars are removed, including full-screen duplicate review and viewer headers.
- `UnifiedGalleryContainer` has a private constructor and one instance; a mount guard prevents two roots in the same window. One permanent toolbar uses fixed slots and one permanent body owns its background.
- Hidden is a visibility setting only. The saved paths are the directory-list source. Favourites now combines hidden paths; shared folder-boundary matching prevents `/A` from hiding `/A2`. Underlying media and label backup sources continue to include hidden files.
- Build, installation and delivery evidence will be recorded below. Flicker and interaction behavior remain pending user verification.

## Historical functional acceptance (2026-10-24)

At that time the prior run recorded all 16 functional/verification requirements (#1-7, #9-17) as passed, including restoration after the user's interaction regression reports. The shared class preserves separate modal Label/Move and persistent non-modal Quick Sort behavior. Delivery (#8) remains pending the final commit/push and release package verification.

Restoration results: `scratch/picker-restoration-header-device.log`, `scratch/picker-restoration-album-device-final2.log`, and `scratch/picker-empty-undo-device-final.log` all finish with `INSTRUMENTATION_CODE: -1` and passing reports. The real MainScaffold suite scans 318 sample files and verifies selection/clear/exit, stationary compact Quick Sort entry, drag-down retaining the header, system Back, album toolbar Back, Move overlay/cancel, and 98-file selection. The dedicated gesture suite verifies four-column layout, drag tracking, cancellation, count-chip clearing and shared option callbacks. The Undo suite additionally asserts visible empty-target controls, disabled taps producing no callback, and available Undo invoking its callback.

Final Debug version `4.3.5.2-auto-multitask` / base code `2069` is installed on `emulator-5556`; the installed APK SHA-256 matches the build output. No user media was moved or deleted by these restoration suites. Harness repairs exclude off-screen retained accessibility rectangles and use an actual blank gap rather than a newly populated album position; a disabled-icon-node assertion was replaced by the actual no-callback check.

Evidence directory: `scratch/toolbar-acceptance/`. Final functional build: `scratch/toolbar-final-low-memory-build.log`; 235 JVM tests passed with zero failures/errors/skips, and the Debug APK built successfully. The six Python database migration tests also passed. An earlier full run encountered host virtual-memory exhaustion; the final run used a bounded, single-worker build. The deliberate pre-fix name-sort failure is retained in `bin-name-before-fix.xml`.

The first emulator (`emulator-5554`) was also being driven by another project's download instrumentation. Subsequent acceptance used the separate `GalleryAlbumSearchQA20261019` emulator on `emulator-5556`; the other task was left running. All permanent deletions targeted only the newly created `ToolbarQA_A/B/C.png` fixtures.

| # | Result | Evidence |
|---|---|---|
| 1 | Pass | Main Photos, main Albums, album media, Bin and Locked normal/selected states retain More icon bounds `[975,116][1038,179]`. Latest captures: `final-home-*`, `final-albums-*`, `isolated-album-*`, `isolated-bin-*`, `isolated-locked-*`. |
| 2 | Pass | `isolated-bin-sort.xml` shows all four criteria. Name ascending and descending, file-date ascending/descending, deletion-date descending and size descending were selected on-device; solid-color fixture positions verify order. `bin-select-all.xml`/`bin-restored.sqlite` record two-file restore; `isolated-bin-all-selected.xml`, `isolated-bin-deleted.xml` and `isolated-bin-after-delete.sqlite` record permanent deletion. Both fixture files and rows are absent afterward. No Empty action remains. |
| 3 | Pass | `isolated-locked-sort-options.xml` and `isolated-locked-{name-asc,date-asc,size-desc}.png` verify each criterion. `isolated-locked-select-all.xml` selects both files; `isolated-locked-share-two.xml` is the actual Android two-file Sharesheet. `isolated-vault-after-delete.sqlite` confirms only the new C fixture was deleted; the pre-existing QA fixture remains. |
| 4 | Pass | Bin/Locked use shared collection toolbars and `MediaSorter.shared`; all option selectors use `SettingsOptionControl`. The three pickers now also reuse `SelectionBottomSheet`, `SelectionSearchField` and `SelectionGrid`. Move/Copy uses `AlbumCoverCard`, as Label does. |
| 5 | Pass | Original locked fixture ID 3600 moved out through the UI to MediaStore ID 3798; `isolated-moveout.sqlite` retains both custom-label and Favourites links on the new ID. Relocking retains both links while removing the normal media row (`isolated-relocked.sqlite`). The live Favourites grid is empty and custom-label count excludes the locked item (`isolated-label-exclusion-grid.xml`, `isolated-favourites-locked-empty.xml`). The 856-byte QA image is preserved in the vault and backed up as `isolated-preserved-locked-fixture.png`. |
| 6 | Pass | `isolated-locked-details-edit.xml` opens the shared editor. Removing both memberships, saving and reopening produces `No labels yet` / `Add labels` (`isolated-locked-zero-label-add.xml`). Adding both original memberships and a newly created label from Locked Details succeeds in `isolated-labels-added.sqlite`. |
| 7 | Pass | Relevant model/migration tests, Debug build and actual installation passed; device UI paths and post-operation file/database checks are recorded above. |
| 9 | Pass | `home-selection-transitions.mp4` records three selection/clear cycles. All 117 decoded frames retain the More icon; centroid x is 1006.961-1006.992, y is 146.933-147.066; the unselected second card's top stays at y=242 throughout. `toolbar-video-check.json` and the inspected contact sheet record this check. Home top-bar scroll offset is reset during selection to prevent a previously collapsed normal bar reappearing on exit. |
| 10 | Pass | `SettingsOptionControl` contains no selected icon path. Bin/Locked sorting and Quick Sort's shared option control were inspected live: selected options use shape/color only. Media/album selection indicators remain separate from mutually exclusive option selectors. |
| 11 | Pass | `isolated-new-label-draft-card.png` shows the new label as the same album card, plus its removable selected chip. Save/reopen persistence is confirmed in `isolated-labels-added.sqlite` and `final-label-four-columns.xml`. The obsolete checkbox-row implementation was removed. |
| 12 | Pass after restoration | The first modal Quick Sort implementation was rejected. `SelectionBottomSheet` now shares visual structure while preserving persistent Quick Sort versus modal Label/Move behavior. Shared modal content measures only the visible viewport while retaining partial/full anchors and keeping Copy/Move accessible. |
| 13 | Pass | A real two-pointer pinch on Albums changed the shared column setting from three to four. `final-albums-four-columns.xml`, `final-move-four-columns.xml`, `final-label-four-columns.xml` and `final-quicksort-four-columns.xml` verify propagation to all three pickers. Pinching Quick Sort changes the same setting; it was restored to three (`final-quicksort-three-columns.xml`). Shared grid retains pinch and scrollbar support. |
| 14 | Pass | The source viewer stays composed beneath TransferDestination. `final-viewer-before-move.png`, `final-viewer-under-move-half-sheet.png`, and `final-viewer-after-cancel-move.png` show the same QA image before, underneath and after cancellation; normal return to its source album passes. |
| 15 | Pass | `final-quick-sort-in-album-collapsed.png`, `final-quick-sort-in-album-expanded.png`, `final-quick-sort-drag-down-keeps-header.png`, and `final-quick-sort-selection-can-exit.png` accompany the passing real-scaffold assertions. No initial auto-rise; downward drag retains the header; Back and selection exits work. The off-screen 1000dp placeholder is gone. |
| 16 | Pass | `final-live-empty-target-undo.xml` shows `0 selected` and `Undo quick sort` together. `quick-sort-empty-targets-undo-disabled.png` and `quick-sort-empty-targets-undo-enabled.png` accompany callback assertions in the passing Undo suite. Empty targets clear drop hitboxes without removing the Undo control. |
| 17 | Pass | Main Albums -> Labels was exercised on `emulator-5556` after the route-layer fix. The 50-frame capture `scratch/flicker-frames-alpha/` and `scratch/flicker-alpha-contact.jpg` show a direct switch from the Albums top bar to the Labels top bar with no blank/intermediate top-bar frame. Retained routes now stay composed in place; inactive layers use alpha 0 and the active layer alpha 1, with no horizontal Scaffold translation or reconstruction. |

Additional defect fixed during acceptance: Android renames trashed files to `.trashed-<expiry>-<original-name>`. Bin name sorting now strips that prefix only for trashed media; ordinary filenames and Locked original filenames retain their semantics. A regression test first failed against the old comparator and passed after the fix.

Version preparation changes only `4.3.5.1` to `4.3.5.2` (version code 2068 to 2069), retaining the existing suffix. Release signing retains the repository's existing debug-key fallback because no release keystore is configured.

## Baseline verification notes (before the current implementation)

- The original inspection found a legacy Locked Folder top bar, an `Empty` Bin action, shared photo sorting for trash and vault, and a checkmark in selected sort controls. These are historical baseline observations; see the acceptance matrix for current state.
- The original label inspection found that vault moves removed the normal media row but preserved `media_labels`, while the mutation guard rejected vault IDs. The current implementation expands the guard to accept present vault files; the acceptance matrix records the remaining live checks.
- Platform reference check: [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions) require an explicit migration path to preserve user data; [MediaStore `IS_TRASHED`](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED) exposes trash state but not a deletion timestamp; multi-file sharing uses `ACTION_SEND_MULTIPLE` and readable content URIs ([Android sharing](https://developer.android.com/develop/ui/compose/sharing/send)).
- Current implementation/build/device results will be recorded here as work proceeds.

## Historical progress before final acceptance (2026-10-24)

There are 8 tasks in total. The persistent goal is active with this same scope. No task is being marked fully complete until every acceptance condition has evidence.

- `:app:testDebugUnitTest :app:assembleDebug` passed; `git diff --check` passed.
- The arm64/x86_64 debug APK was installed on `emulator-5556`; installed version is `4.3.5.1` / code `2068`. No version change, commit, push, or release build has been done for this task.
- Bin and Locked Folder now use the shared collection bar. Device XML shows the Locked Folder overflow bounds are `[975,116][1038,179]` in both normal and selected states (`scratch/locked-grid-live-5556.xml`, `scratch/locked-selected-live.xml`). The earlier Bin and album captures are `scratch/bin-task-5556.xml` and `scratch/albums-task-5556.xml`.
- The Bin sort menu and four sort criteria are present in `scratch/bin-menu-task-5556.xml` and `scratch/bin-sort-dialog-task-5556.xml`. Restore/delete/select-all behavior still needs complete device-path verification.
- Locked-item label editing is visible in `scratch/locked-info-sequence-live.png` and `scratch/locked-label-editor-live.png`. The isolated QA item is `QA-Lock-Label.png` (content ID `3600`). Database inspection confirmed its custom-label and Favourites links remain while its row is in `vault` and absent from `media_entries`. A reversible UI edit removed both links; the original database snapshot was restored afterward. Final check: links `(1,3600)` and `(2,3600)` are present again, vault row `3600` remains, the normal media row is absent, and the 856-byte fixture is still in the vault. Moving it out and checking restored memberships remains pending.
- The custom label still shows one item because a separate, unlocked fixture (content ID `100`) also belongs to it; the locked item is not included in that count.
- Release signing currently falls back to the debug key because no release keystore is configured. This has not been changed.

## Historical acceptance matrix before final verification

| # | Status | Evidence and remaining acceptance |
|---|---|---|
| 1 | In progress | The Locked Folder normal/selected overflow has matching right-aligned bounds. Verify paired normal/selected positions on both main grids, album media grid, and Bin before passing the cross-screen criterion. |
| 2 | In progress | The Bin toolbar and four-criterion sorter are visible. Verify sort choices update order, select-all scopes to Bin, restore returns media, permanent delete removes it, and no `Empty` action remains. |
| 3 | In progress | The Locked Folder toolbar and selected actions are wired; the live grid/selected-state XML is saved. Verify the three sorter choices, select-all, share, move-out, and permanent delete on device. |
| 4 | In progress | Shared controls are `MediaCollectionTopBar`, `SortDialog`, `SettingsOptionControl`, and `MediaSorter.shared`; sort selection passes `showCheckmark = false`. Finish action parity/regression checks for all consumers. |
| 5 | In progress | Locking retained custom-label/Favourites links and excluded the item from the normal media table. The fixture is restored. Verify label-grid exclusion directly and move the item out to confirm both links return with it. |
| 6 | In progress | Locked Details shows the same `Edit labels` entry and the editor opens with existing labels selected. Verify adding a label/Favourites from Locked Details and the zero-label `Add` state. |
| 7 | In progress | Unit tests/build pass and the APK is installed. Complete Bin/Locked emulator paths and retain the resulting evidence. |
| 8 | Pending | After acceptance, increment only the fourth version segment, commit the task files, push and verify the remote branch, then build the requested arm64-v8a release package. |

## Historical follow-up verification (2026-10-24)

- Rebuilt and installed Debug version `4.3.5.1-auto-multitask` / code `2068` on `emulator-5554` without clearing app data. The installed Locked Folder screen shows the refreshed title bar and fixed right-side overflow; its selected state retains the same More bounds `[975,116][1038,179]` and exposes Select all, Share, Move out, and Delete permanently. The selection overflow opens the shared `Sort Locked Files` dialog with Date, Name, and Size; selected options use shape/color only, with no checkmark.
- The installed Bin screen shows the refreshed title bar and the same More bounds `[975,116][1038,179]`. Its sorter exposes File date, Deleted date, Name, and Size. Select all selected all four current Bin items; the selection bar exposes Restore and Delete permanently, with no Empty action. No media was restored or permanently deleted during this inspection.
- The Home normal/selected and Albums normal captures also report the More icon bounds as `[975,116][1038,179]` (`scratch/home-normal-verify.xml`, `scratch/home-selected-live.xml`, `scratch/albums-normal-live.xml`, `scratch/albums-selected-live.xml`).

- Rechecked the running emulator `emulator-5554`; the installed package is `com.pixel.gallery.multitask.test`, version `4.3.5.1-auto-multitask` / code `2068`. The app was responsive and opened the Bin from the Albums page.
- Bin overflow opens `Sort Bin`; the app source and prior saved device capture expose the four criteria: file date, deletion date, name, and size. This run confirmed the menu entry, but did not yet prove each selection changes item order.
- Long-pressing one Bin item entered selection mode. Device accessibility bounds show the selection bar's overflow stays at `[943,84][1069,210]`; its actions are Select all, Restore, and Delete permanently. No destructive action was triggered on the existing media.
- `:app:testDebugUnitTest :app:assembleDebug` passed again. The emulator was left running, but no new APK installation was needed because the source and version were unchanged.
- The eight acceptance items remain incomplete overall: the remaining device operations and cross-screen position checks are still required before marking items complete. No version change, commit, push, or release build has been made.
- After the chooser refactor, `SettingsOptionControl` no longer renders a checkmark for any consumer; selection is represented by shape and color. Label editor entries always use the album-card grid, including empty labels.
- Quick Sort now uses the shared `SelectionBottomSheet` shell used by Label editing and Move/Copy, uses the shared grid-column setting (pinch-to-zoom), and includes the shared scrollbar. Device capture `scratch/quick-sort-shared-live.png` shows the half-page layout with three columns.
- The Home title bar now occupies the outer Scaffold's stable top-bar slot in both normal and media-selection states. Device XML before selection, during selection, and after clearing selection keeps the More icon at `[975,116][1038,179]`.
- The new Debug APK installed successfully after removing only obsolete test packages to recover emulator installation space; the main app data was preserved. Installed package remains version `4.3.5.1-auto-multitask` / code `2068`.
