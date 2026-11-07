# Group toggles and background viewer flicker — code audit

Date: 2026-11-07

## User reproduction

Open multiple tasks displaying still-image viewers, leaving one task on the album grid. Initially viewer tasks do not flicker. Repeatedly expand/collapse groups in the grid: the first or first few animations sometimes stutter, later attempts become smoother; sometimes there is no initial stutter. Switching back to viewer tasks can then reveal flicker. Neither the stutter nor the flicker is guaranteed on each toggle.

This is NOT the single-Activity empty-media-source/recreate test used previously.

## Confirmed code paths

1. `AlbumGroupsViewModel.toggle` optimistically updates its local grouping and writes the entire grouping JSON, including `collapsedIds`, to the shared settings DataStore. Introduced in `7d10911c`; the current optimistic implementation still persists expansion globally.
2. `SettingsRepository.collectionCovers` and `excludedFolders` project values from that same DataStore with `map`, without equality filtering. A change to only the grouping key therefore also emits unchanged cover and exclusion settings.
3. `LabelsViewModel.labels` directly combines `settingsRepository.collectionCovers`. On each such emission it builds a full `media.associateBy(uri)` map and calls `buildLabelSummaries`, which builds another full `visibleMedia.associateBy(contentId)` map. Every Activity owns its own eagerly collected LabelsViewModel. This cover-triggered path was introduced in `4985a319`.
4. `PhotosViewModel.albums` also directly combines the cover stream; `hiddenAlbums` combines both the cover and hidden-root streams. `applyAlbumCovers` builds a full URI map whenever the cover map is nonempty. `MediaRepository.allEntries` refilters its entries on unchanged excluded-folder emissions and invalidates `viewerMediaSnapshot` before StateFlow's equality check.
5. `MainScaffoldContent` collects grouping using `collectAsState` at its root, independently of whether Home is foreground. It keeps base routes composed beneath the viewer. `RetainedGalleryLayer` suppresses placement for hidden routes but still composes and measures children. The hidden group grid therefore receives the new rows and animation targets as well. This multiplies work across retained tasks.

Important: downstream StateFlow equality can suppress identical RESULTS. It does not prevent the upstream filtering/index construction. These group-only writes do not themselves demonstrate an empty media-list emission, so the previous empty-list fix does not remove this workload.

## Existing device evidence

Source: `scratch/viewer-trace-captures/grid-live-20261107-004401-455/host-logcat-live.txt`.

- Line 11746: system destroys viewer task 29727 with reason `low-mem`.
- Line 11748: system destroys viewer task 29726 with reason `low-mem`.
- Line 34776: task 29727 restarts at 23:44:42.514; line 34908 records Activity creation with saved state at 23:44:42.538, same process PID 3180.
- Lines 39031–39053: the system again destroys multiple viewer activities with reason `low-mem`, including task 29727.
- Line 40125: task 29726 restarts at 23:44:46.810; line 40247 records Activity creation at 23:44:46.819.

These are device-log timestamps as recorded, not converted to the client timezone. The log contains older system events too: destruction of tasks 29726/29727 at 23:37:55 preceded the two observed flickers by several minutes. Do not claim the log timestamps link those destructions to a particular group tap.

Confirmed: background Activity destruction and later reconstruction can occur without process death. Keeping a media-list snapshot cannot preserve the destroyed Android image renderer.

Not yet confirmed: that allocations from the new group/cover paths crossed the memory threshold during the user's latest precise sequence. First-animation stutter becoming smoother does not prove memory recovery or a particular cache mechanism.

## Correction to previous regression claim

`ViewerRecreationFlickerChecks` deliberately recreates an Activity, reapplies test content, and waits for a large ImageView/SSIV before and after. It does not continuously observe frames, assert renderer identity, or operate groups across multiple tasks. Its former string `renderer stayed mounted` was stronger than its checks supported; the result text now explicitly says that frame continuity was not tested. That previous PASS must not be cited as proof that this multi-window flicker is fixed.

## Next acceptance matrix

| Case | Required observation | Status |
| --- | --- | --- |
| Several viewer tasks, no group interaction | Lifecycle and renderer identity/readiness on task switches | Runtime control passes; not a pixel-presentation guarantee |
| Same tasks plus repeated foreground group toggles | Work counts, allocations, lifecycle destruction reasons | Rebuild fanout reproduced; phone flicker not reproduced |
| Group-only settings writes after isolation | No cover/exclusion emissions or full-library rebuilds in viewer tasks | Passed real DataStore regression and 96 UI toggles |
| Hidden grids after isolation | No offscreen group row rebuild caused by another task's toggles | Not fixed: still four row rebuilds per toggle |
| Real cover/visibility edits | Still propagate to relevant tasks correctly | Positive and removal emissions passed; full cover-picker UI not re-tested |
| Return after system destroys background Activity | First visible rendering state; distinguish list restoration from image rendering | No destruction occurred in these trials; still pending |

## Targeted correction direction

Filter each settings projection before expensive downstream work; for JSON, compare the raw string before decoding. Isolate transient group expansion and offscreen grid animation work from viewer tasks while preserving persisted group membership/order. Build full-media cover candidates only when needed. Then compare the exact multi-task sequence before/after; do not extend the empty-list delay and label that a root fix.

Platform references consulted: Android Activity lifecycle (`https://developer.android.com/guide/components/activities/activity-lifecycle`) and Glide RequestManager lifecycle (`https://github.com/bumptech/glide/blob/master/library/src/main/java/com/bumptech/glide/RequestManager.java`). Project-specific conclusions above come from local code and captured device logs.

## 2026-11-07 runtime experiments

Evidence directory: `scratch/group-multiwindow-evidence/`. Only emulator-5562 is connected. The production phone/package was not installed or modified. Tests use the independent debug package `com.pixel.gallery.multitask.test`.

`GroupMultiWindowChecks` creates 48 owned JPEG fixtures in 12 albums, opens three distinct real MainActivity tasks with the production image viewer plus a fourth task with the production album grid, and injects real touch events into the group card. Initial routes are supplied by the instrumentation host, not navigated through every production entry path. It tests task switching before any group interaction, then repeated group toggles and periodic switches back to each viewer. All temporary media and grouping changes are restored afterward.

The decision signals are direct runtime observations: FrameMetrics, Activity lifecycle, actual renderer identity/readiness/alpha on draws, full-library work counters, ART allocation/GC counters and settings response time. The first two trials also saved occasional screenshots; no image recognition is used as a pass/fail criterion. Later `listen-*` trials capture no screenshots at all. View readiness/identity does not prove the absence of every possible Surface/GPU presentation glitch.

### Before isolation

- `baseline1` and `baseline2`: 24 toggles each. Each trial produced 96 label rebuilds, 96 album-cover rebuilds, 96 label visibility filters, 24 shared media exclusion filters, and 96 group row rebuilds. Idle controls recorded zero work at these points.
- First-toggle maximum completed frame durations were 681.7 ms and 744.6 ms. No viewer Activity was destroyed and no sampled focused draw had a missing renderer drawable in these trials. These are debug emulator measurements, not phone latency guarantees.
- `listen-baseline`: 96 toggles with alternating 500/850/1600/600 ms observation delays and six sets of return visits. Confirms 384 rebuilds per Activity-owned path and 96 shared exclusion filters. This run overlapped a host build, so its timing is excluded from the performance comparison.
- `listen-baseline-clean`: repeated the same 96 toggles without a concurrent host build. Again 384 rebuilds per Activity-owned path and 96 shared exclusion filters. Total frame p95 100.5 ms, max 386.1 ms; measured UI work p95 44.9 ms. ART cumulative allocation during the observation windows was 203,772,704 bytes, with 11 GCs. This allocation includes normal UI work and is not retained heap.
- Neither long trial recorded a viewer Activity destruction or missing renderer state. This fails to reproduce the phone's flicker; it does not invalidate the phone report.
- `SettingsProjectionChecks` against the baseline APK deliberately fails: ten grouping-only changes yielded 11 emissions (one initial plus ten redundant) from each of covers/excluded/hidden-root projections.

### Narrow intervention under test

Only the cover, excluded-folder and hidden-root projections now suppress equal values. Cover JSON is compared before decoding, with a second equality check on the decoded map. Group row layout, animation, persistence and renderer logic are unchanged. Existing unrelated workspace edits are the same in both APKs.

Baseline APK SHA-256: `a38c40d89da4a89a9185cad49ce0174483f2295aef47736e7d1b3bb4e646e428`.
Isolation APK SHA-256: `1f977533f7cd2d9f8d8efc8598205b870db29b159b51e602d9d0872d8c3472c`.

### Why memory needs a separate observation

The Android 15 AOSP `ActivityThread` GC watcher can request release of background activities when used Java heap exceeds three quarters of the process maximum and activities have changed: https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/ActivityThread.java . This is a platform mechanism, not proof that a particular group tap crossed the threshold on the user's phone. The old phone trace reports 131,013 media entries; the emulator UI fixtures are much smaller. `GroupIndexPressureChecks` therefore separately exercises real repository/PhotosViewModel/LabelsViewModel code using 131,013 synthetic records in an isolated in-memory database. This measures allocation/CPU only and makes no visual flicker claim.

### Large-index A/B result

Accepted runs: `index-baseline2.json` and `index-isolated.json`. Both confirm all 131,013 records loaded. Four production PhotosViewModels and four production LabelsViewModels are warmed, then measured over 12 grouping-only writes, with the same 1.5-second observation interval per write. A temporary cover entry exercises the manual-cover index path. Synthetic records use one existing test-file path and unique synthetic URIs; this is not a reproduction of all album membership/decoder characteristics on the phone. The test sets the private browsing lifecycle gate directly to load the synthetic database without importing real MediaStore rows. Repository and ViewModel transforms are otherwise unmodified.

| Metric per observation interval | Baseline | Isolation |
| --- | ---: | ---: |
| Process CPU time, median | 3,787.5 ms | 39 ms |
| ART cumulative allocated bytes, median | 144.916 MiB | 0.252 MiB |
| GC count over all 12 intervals | 32 | 0 |
| Album-cover / label rebuilds per write | 4 / 4 | 0 / 0 |
| Visibility filters per write, labels / shared media | 4 / 1 | 0 / 0 |

CPU time sums work across threads and is not the duration of a visible stall. Allocation is cumulative temporary allocation, not retained memory or a measured instantaneous peak. The approximately 99.8% allocation reduction supports causation for this redundant indexing workload, not an unconditional claim about the phone's visible flicker.

`projection-after.log` passes both negative and positive checks: no cover/visibility emissions from ten group-only changes; real cover, exclusion and hidden-root edits/removals each emit once. `projection-before.log` fails on the old APK, demonstrating that the check detects the defect.

The first large-index attempt (`index-baseline.json`) timed out during setup because the test had not activated the PhotosViewModel browsing gate. It contains no measurements and is excluded. Likewise the initial multi-window setup attempt reused a singleTop Activity instead of creating distinct tasks; it was excluded, the exact owned fixture was cleaned, and subsequent accepted runs assert distinct task IDs.

### Post-isolation real-window listener result

`listen-isolated.json`: 96 real toggles, same variable delays and three viewer tasks plus one album task, without concurrent host builds. Cover rebuilds, label rebuilds and both visibility filters are all zero during the toggles. Group row rebuilds remain 384, including the retained grids in other tasks.

Total frame median/p95/max: 35.3 / 115.4 / 334.4 ms. The longest frame is `toggle-0-0`: UI work 299.2 ms, draw 246.3 ms, layout 0.138 ms, GPU 8.42 ms. A later toggle also spends 235.8 ms in draw. Thus this intervention does NOT establish that all group jank was caused by the redundant indexing. The small-library frame distribution does not show a clear improvement (baseline clean p95 100.5 ms). Further attribution of the remaining draw work requires additional tracing.

The 96 toggles allocate 195,224,288 bytes over their observation windows, with 21 GCs; this small-library UI run must not be confused with the separate large-index workload's 32-to-0 GC result. Both the real baseline and isolation trials have unchanged sampled viewer renderer identities, ready content with alpha 1, and zero observed Activity destruction during the experiment. System destruction records after completion are explicit test cleanup (`finish-imm`), not `low-mem` failures.

Overall: 240 accepted baseline UI toggles and 96 post-isolation toggles. The expensive cross-window indexing path is confirmed and isolated. The actual phone flicker and its causal link to a particular group operation remain unverified. This is not a completed flicker fix or a release acceptance report.

### Covered-route presentation isolation

`listen-retained.json` / `.log`: another 96 real toggles passed all per-tap assertions. Group row rebuilds fell from 384 to 96 (only the foreground album task); all four library-index/filter counters remain zero. A directly opened viewer first captures initialized group state before retaining its presentation. Shared persistence is unchanged.

Two additional real taps/return visits passed: backing out of a viewer shows the latest expanded group; switching back to a stopped Home task shows the latest collapsed group. The stopped Home task did not rebuild during the remote toggle. This is deferred rendering, not independent per-window group settings.

Frame total p95 74.0 ms, max 334.8 ms; measured UI p95 23.7 ms. Slow frames still exist, so these are not claims of zero jank. No Activity was destroyed during the run. The first viewer's 33 missing-renderer observations are exclusively in `return-to-albums-sync`, after the test intentionally presses Back to leave the viewer; they must not be counted as image flicker. There are also six startup observations before initial image readiness. All focused viewer observations during control/toggle/return batches retained their renderer and ready content.
