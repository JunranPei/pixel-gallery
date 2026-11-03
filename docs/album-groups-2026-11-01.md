# Album groups

Accepted UI: option 08, one continuous grid. Labels and user groups precede ordinary albums;
the recycle bin is the final tile. Groups retain creation order, members retain the normal
album sort. Expanded groups keep their one-cell entrance; its cover background becomes
transparent. All members use the existing AlbumCoverCard, without extra padding or spans.
Multiple groups can expand independently. Backgrounds follow measured visible grid cells,
including unequal caption heights; they never affect item placement.

Long press albums, then More → Add to group → select or create a group. Assigning to another
group removes previous membership. Long press a group for rename/dissolve; long press Labels
for the existing label manager. Search returns flat matching albums even when their groups
are collapsed. Hidden/filtered/unavailable album paths retain membership for their return.
Folder renames remap group paths in the existing atomic settings transaction.

References used for implementation and checks:
- [Compose lazy grids, stable keys and layoutInfo](https://developer.android.com/develop/ui/compose/lists)
- [DataStore atomic read-modify-write](https://developer.android.com/topic/libraries/architecture/datastore)

| Case | Layer | Status / evidence |
|---|---|---|
| ~~Groups first; members preserve sort; bin last; unique keys~~ | JVM | Passed, AlbumGroupingTest |
| ~~Independent collapse and search of collapsed members~~ | JVM | Passed |
| ~~Transfer/remove membership and invalid target~~ | JVM | Passed |
| ~~Missing albums preserve memberships; new ordinary albums follow sort~~ | JVM | Passed |
| ~~Rename remap and JSON restore~~ | JVM | Passed |
| ~~Existing unit regressions~~ | JVM | 353 tests, 0 failures/errors, scratch/album-groups-build.log |
| ~~UI create and real persisted membership~~ | MainScaffold / DataStore | Passed, album-groups-verified-device.log |
| ~~Multiple expanded groups including labels~~ | MainScaffold | Passed |
| ~~Shared columns 2/3/4/6 and crossing rows~~ | Emulator | Passed, screenshots 01/02/03 |
| Partial-scroll background boundary | Source review | Viewport clipping compiled and checked; no separate gesture assertion |
| ~~Activity reopen and existing album route / selection actions~~ | MainScaffold | Passed |
| ~~Dissolve restores ordinary albums; original bytes readable~~ | MainScaffold / MediaStore | Passed; all five fixture URIs readable before cleanup |
| ~~Dark theme and large text~~ | Emulator | Passed; screenshots 06/07, real system font scale 1.5 restored to 1.0 |
| ~~Installation artifact~~ | Build | Passed; build_apks/PixelGallery-album-groups-preview.apk, installed on emulator-5562 |
| ~~Rejected invalid membership edit leaves stored state unchanged~~ | Real DataStore transaction | Passed in device suite |

Device tests use com.pixel.gallery.albumgroups.test and UUID-named MediaStore fixtures.
They restore initial grouping/sort/columns and remove only their five fixture URIs. No
production app settings or user files are changed. UI screenshots are distinct from JVM
test results. Native compilation is skipped through the existing reuse init script because
this change only modifies Kotlin/resources; native outputs themselves are unchanged.

Environment: stale .gradle-user cache junction pointed at missing D:/.gradle/caches/8.13;
recreating the missing target allowed the installed Gradle distribution to run. Emulator
37.3.5 software renderers exited during initialization; direct headless backend with GPU
host booted emulator-5562 successfully. Existing SDK installation was preserved.

Final build: scratch/album-groups-verified-build.log. Final device log:
scratch/album-groups-verified-device.log. Screenshot originals:
scratch/album-groups-evidence/ (9 PNGs). Before-padding-fix evidence is retained
separately in scratch/album-groups-before-padding-fix/. Screenshot review found and
fixed missing content-padding translation; final backgrounds align to the same cells.
The canvas also clips to its viewport so offscreen row backgrounds cannot paint above
or below the grid. The first dark UI host omitted MainActivity's Surface and inherited
black text; the test host now uses the same Surface as MainActivity. The corrected dark
and real 150% system font screenshots were opened and visually reviewed.

APK SHA-256: F9006C5CBAA4E00E6730E8B548B5E0F5118449F10548A4195060834D81610683.
Package: com.pixel.gallery.albumgroups.test; supports arm64-v8a, armeabi-v7a, x86,
x86_64. This is an independent debug preview, not a production-package installation.
No physical phone acceptance or release publication was performed. Existing unrelated
working-tree changes were preserved.

Follow-up: background rounding now shares ExpressiveShapes.AlbumCornerRadius (32dp)
with album covers, replacing the independent 14dp radius. Preview rebuilt and installed;
log scratch/album-groups-radius-build.log. Native screenshot:
scratch/album-groups-evidence/08-matching-corners-visible.png. The visible emulator window
is left open with Labels and a Travel demonstration group expanded. This demonstration
only groups existing test-emulator albums; no media files were moved.
Updated APK SHA-256: 5C3BAE3BB101D1D459B5867A35001A77CCB229F1256A90361A29BBDFB7CFBA0A.


## Dialog consistency and top overscroll follow-up

Baseline feature committed as 7d10911c before this UI work. Group actions, label
management and assignment now use a compact dialog with the existing GalleryMenuRow
icon/typography/surface treatment. Name dialogs use contextual folder/edit icons and
the same filled Save button as the existing album/label rename UI. Dissolve uses the
existing confirmation family with the group name and file-preservation reminder.
No global dialog or menu styling was changed.

The former decoration lived outside LazyVerticalGrid's visual overscroll node. It
tracked layout offsets but did not receive Android's stretch transform. AlbumGrid
now attaches a single overscroll visual node around both grid and background and
passes withoutVisualEffect() to the grid for event handling. Scrollbar stays outside
that transform. Reference:
https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary

A held top-pull probe compares a solid fixture album's upper edge with the matching
background edge on the same scanline. Old APK: rest cover=808/background=792;
held cover=829/background=792; relative drift=21 px. Fixed APK: rest=808/792;
held=829/813; relative drift=0 px. Baseline failure log:
scratch/album-groups-stretch-before-device.log. Full repaired suite:
scratch/album-groups-dialog-scroll-verified-device.log (PASS). Native screenshots and
metrics: scratch/album-groups-dialog-scroll-evidence/; includes all five dialog states.
Final build: scratch/album-groups-dialog-scroll-final-build.log.

An intermediate run exposed a queued system-back callback attempting to pop the
last route. The final branch now checks stack size before popping. The test also
waits for the album's unique Back control rather than the More button shared with
Home. The intermediate process exit skipped test cleanup; its five media fixtures
were removed by their exact IDs, and test-app groups 1 (1 member), Travel (3), 3 (8)
were reconstructed from the pre-run screenshots and MediaStore paths, retaining names,
members and order with new internal IDs. No source media was moved. Subsequent tests
write original-grouping.json before mutation; the final run restored that saved state
and its IDs, verified from the persisted DataStore file.

Updated independent preview APK SHA256:
34114e2fe9a036ba8e91510c716ce71fbeb778f064e274ea6fddf7ce1faf423b.

Final JVM regression: 353 tests, 0 failures/errors; scratch/album-groups-dialog-scroll-unit.log.


## Picker uses existing label selection components

The assignment picker now uses SelectionBottomSheet, SelectionSearchField,
SelectionGrid and AlbumCoverCard directly, following LabelEditorSheet's layout
and immediate-save interaction. No shared container implementation was changed.
The custom header close button, search-adjacent plus button, result counter and
membership status row were removed. A shared current group appears as a removable
AssistChip; selecting that same grid card removes membership, selecting another
card switches groups, and creating from the search query clears that query only
after persistence succeeds. The sheet and album selection remain open after saving.

Build: scratch/album-group-existing-sheet-build.log (PASS).
Device: scratch/album-group-existing-sheet-device.log (PASS), including 100 lazy
candidates, searching GroupQA099 and verifying only that group's stored membership,
create-from-search, remove and dismiss. Existing full grid regression also passed:
columns 2/3/4/6, multiple expanded groups, label group, reopen persistence, album
navigation, dark theme, 150% font and dissolve. The held overscroll probe passed.
An initial test run received an extra character through the system IME; the test
now uses the existing project's accessibility text-entry method. No production
UI change was needed for that test-input issue.

Original emulator groups 1 (1 album), Travel (3), 3 (8), including their IDs and
expanded state, were verified against persisted settings after cleanup. The normal
preview is left open on Travel membership in the visible emulator. Screenshots:
scratch/album-group-existing-sheet-evidence/. Independent APK updated at
build_apks/PixelGallery-album-groups-preview.apk. These follow-up edits are uncommitted.


Density follow-up: per-page persisted column settings now separate home photos, home albums, each album and label detail, label overview, hidden albums, favourites, trash, vault and group picker. Shared pinch recognizer survives column changes; picker parent owns two-finger gestures. Native test passed 3-to-6-to-2 pinch, unchanged home densities and no accidental membership edits. Album titles/captions use density-dependent font sizes; album page header also scales. Joined membership is indicated on the destination card, with a No group destination for removal. Build: scratch/album-density-build.log; device: scratch/album-density-device.log; screenshots: scratch/album-density-evidence/. Original grouping restored and independent preview APK updated.

Soft group backgrounds blend active container hues into the app surface: 30% tint in light mode, 40% in dark mode. Both collapsed and expanded groups follow app theme and dynamic colors. Build/device checks passed; light/dark screenshots visually reviewed in scratch/album-soft-colors-evidence/. Original grouping verified restored.

Dedicated palette replaces theme-container mixing: light F0F4F8/EFF5F1/F4F1F8/F6F3EF paired with dark 1C232A/1D2621/24212B/28231F. Labels use grey violet. New groups persist colorIndex selected from least-used colors excluding the previous group; legacy groups derive a stable fallback from ID. Rename/reorder/removal and JSON persistence tests pass. Build/unit log: scratch/album-palette-build.log. Native regression PASS: scratch/album-palette-device.log. Light and dark screenshots visually reviewed in scratch/album-palette-evidence/. Original group state verified restored; preview APK updated.

After commit 9f72544a: replaced four-thumbnail group entry with a single adaptive folder/label icon and a simple expand/collapse chevron. Existing grid geometry and palette retained. Build and native regression pass in scratch/album-folder-entry-build.log and scratch/album-folder-entry-device.log; screenshots in scratch/album-folder-entry-evidence/. New visual change remains uncommitted.


Selected option 04: two narrow album spines and one complete cover replace the single-icon experiment. Group backgrounds retain the shared album radius; spine content no longer inherits a second outer clipping mask at dense columns. Build and group regression: scratch/album-spines-build.log, scratch/album-spines-device.log.

Collection cover settings: ordinary albums, label albums (including favourites) and groups use one SelectionBottomSheet / SelectionGrid / AlbumCoverCard picker. Default automatic, staged manual choice, explicit Save, cancellation without mutation, and Use auto reset. Search is filename-based within current collection membership; each target picker has independent persisted columns. Overrides are stored by album path, label ID or group ID; album keys follow rename. Only currently available members resolve as manual covers, otherwise the existing automatic cover is used. Existing label/album counts and sort metadata are retained. Native suite collection-covers passed album/label/group menus, search, cancellation, persistence/relaunch, auto reset, 3-to-6-to-2 pinch without home column changes, label membership removal/re-add, media deletion fallback, dark mode, and fixture cleanup. Evidence: scratch/collection-cover-device.log and scratch/collection-cover-evidence/. CollectionCoverTest passed 3 tests for boundary/missing-media resolution, metadata preservation and rename. Build/unit log: scratch/collection-cover-build.log. Final visual-only fixes build: scratch/collection-cover-final-build.log. New changes remain uncommitted.


Pencil settings follow-up supersedes the cover sheet above: removed AlbumGroupActionDialog and CollectionCoverSheet. Long-press group/Labels enters the same selection toolbar as ordinary albums; the pencil opens a full GalleryPage using SettingsGroup/SettingsClickItem. Cover actions live alongside name and group management. Choose image opens the existing PhotoScreen for an ordinary album, or the shared PhotosScreen/date grid scoped to a label/group, with independent cover-picker columns. Picking returns to settings as a draft; Save persists and Back discards. The single UnifiedGalleryContainer still owns the toolbar. Device suites passed: scratch/collection-settings-device.log (all three types, pencil entry, original album grid, cancellation, persistence, reset, dark) and scratch/collection-settings-groups-device.log (rename form, dissolve, expansion, column boundaries, top stretch, group picker). Final style build passed: scratch/collection-settings-style-build.log. Evidence: scratch/collection-settings-evidence/. Original group settings verified restored. No commit created.


Correction: removed the independent collection settings page. The pencil now opens the original GalleryDialog form with inline album name and cover controls, Save/Cancel. Choosing media temporarily opens the album grid and returns to the same dialog draft. Restored the group selection overflow; Find duplicates within group passes only current visible group members to the existing ALL_FILES scan, including comparisons between albums inside the group. Manage labels and dissolve remain in that overflow. Native collection suite passed the edit dialog, cancel/save/reset/relaunch, actual group rename, and real group dedup scan of exactly 9 fixture media with non-empty results containing no outside IDs. Build: scratch/collection-dialog-build.log. Device: scratch/collection-dialog-device.log. Screenshots: scratch/collection-dialog-evidence/. No commit created.

### Settings dialog visual refinement
- Applied frontend-design skill; kept the shared GalleryDialog and native collection image picker.
- Consolidated body spacing, rounded the name field with the theme shape, combined cover preview and choose-image action into one row, and aligned custom-cover state with the automatic reset action.
- Built and installed the preview successfully. Emulator checked automatic/manual layouts, picking from the collection, and canceling a draft without saving. No new dark-mode screenshot was taken in this pass; colors use existing theme roles. Changes remain uncommitted.


### Centered cover and inline group picker
- Selected option 04: centered clickable cover, manual-only top-right clear button, no caption/mode controls. Clear restores automatic draft; Save applies.
- Removed group expand/collapse corner arrows.
- Group cover selection reuses the actual home grid with its existing columns, ordering, expansion and scroll state. Outside members are dimmed and disabled; tapping the active group exits to settings. Member albums open their native photo grid.
- Label/group photo empty and search-empty states reuse DuplicateResultContent with the photo illustration and no completion check.
- Preview assembled and installed. Device check verified 17 visible cell bounds identical before/after dimming, disabled outside-group interaction, repeated-group exit, member album navigation/back and label empty state. Centered-image pick/clear/cancel was also checked. Changes not committed.
