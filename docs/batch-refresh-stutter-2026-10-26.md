# Transfer-time scrolling regression: remaining full-refresh work

The user reports that scrolling is still stuttering after the thumbnail/pending-cover repair. This invalidates the claim that per-cell replacement was the main cause. No trace from the user's phone is available; the following are source-confirmed workloads and controlled checks, not phone frame-rate measurements.

## Source-confirmed work left after the previous repair

1. Room invalidation still caused all-library and favourite paged reads at up to one query per 150 ms plus query time. These fed full sorting/filtering, browse-index rebuilding and album/label derivation. Holding the final tile presentation did not stop any of those upstream operations.
2. Trash watched the same media table and could reread its full result after every media-table invalidation, even when the change did not affect trash. The previous change had only coalesced library/favourite reads.
3. Membership observation materialized the entire media-label table after ID remapping. MainScaffold and LabelsViewModel then rebuilt membership maps/summaries.
4. Gallery UI state still considered per-item status/receipt changes meaningful, emitting sampled whole-queue snapshots to the scaffold throughout the batch. Service notification progress and gallery presentation were not yet separated.
5. The MediaStore observer's mutation flag covered one file at a time. Between files, a notification/queued refresh could start a full MediaStore reconciliation while the queue was still working.

## Current change

- A shared injected read-refresh coordinator batches presentation reads for the queue execution window. Initial data remains loadable even if subscribed while a transfer is active; completed batch boundaries publish committed data, and release on success, cancellation, pause or failure always allows a final refresh.
- The coordinator sits before full DAO reads for library, favourites, trash and memberships. It does not delay per-file database writes, checkpoints, timestamp/content verification or undo evidence. Its lease is idempotent and supports nested ownership.
- Invalidation queries now use EXISTS tokens rather than COUNT/full membership payloads. Room-generated code still watches media_entries for library/trash and both media_entries/media_labels for favourites; membership watches media_labels. The token value is never used as a media count. Schema/entity definitions are unchanged.
- Explicit label edits/manual media mutations publish immediately, including while a transfer window is held. Existing common-label delta semantics remain required.
- Gallery queue comparison suppresses intermediate item changes in nonterminal jobs; metadata/permission/conflict/error/first-success usage and terminal jobs remain observable. The foreground service continues sampling authoritative per-file progress for its notification; worker operations never read a stale gallery state.
- MediaStore idle refresh waits outside the mutation lock for transfer release and rechecks after acquiring that lock. The worker's recovery/post-transfer scan explicitly bypasses its own read window; it cannot wait on itself. One post-transfer scan catches MediaStore changes that occurred while self-notifications were suppressed.

## Verification matrix

| Case | Layer | Expected and evidence |
|---|---|---|
| 1000 committed changes inside a held window | Production refresh-flow helper, JVM | Initial read; no intermediate full reads; explicit boundary and final release read latest version. MediaReadRefreshCoordinatorTest |
| Nested/idempotent release and partial cancellation | JVM | No early release; final committed state is published. MediaReadRefreshCoordinatorTest |
| Subscription starts during an existing transfer | JVM | Initial data loads; subsequent per-file updates stay suppressed. MediaReadRefreshCoordinatorTest |
| User changes a label during transfer | JVM flow control | Explicit publication is immediate while hold remains active. MediaReadRefreshCoordinatorTest; actual Room/UI interaction not measured here |
| Ordinary idle updates | JVM | Each acknowledged update is observable without waiting for transfer. MediaReadRefreshCoordinatorTest |
| 20000-media index, 1000 source-to-destination changes | Actual MediaBrowseIndex builder under controlled flow, JVM | No intermediate index rebuild; boundary result contains 19000 source + 1000 destination entries, all IDs retained. MediaBrowseIndexTest |
| 1000 individual queue completions | Production gallery comparison, JVM | No intermediate gallery state changes; terminal/permission/persistence error remains visible. QuickSortUiUpdatesTest |
| DAO watcher dependencies | Generated Room 2.6.1 implementations | Table dependencies verified in app/build/generated/ksp/debug/java/com/pixel/gallery/data/local/dao/*Dao_Impl.java; not a runtime Room test |
| Queue recovery/undo, label/index/model regressions and prior sheet/gesture logic | JVM suites | Run log scratch/batch-refresh-fix-build.log |
| Scrolling frame times during real transfer; keyboard/overlay behaviour | User phone | Pending user acceptance; no device gestures automated |

References: [Room observable query invalidation](https://developer.android.com/training/data-storage/room/async-queries), [Compose performance and collection equality](https://developer.android.com/develop/ui/compose/performance/bestpractices), [Measuring app performance and garbage collection](https://developer.android.com/topic/performance/measuring-performance).

## Delivery evidence

- scratch/batch-refresh-fix-build.log: BUILD SUCCESSFUL; release APK assembled; 125 JVM cases across 21 suites passed, no failures/errors/skips.
- MediaReadRefreshCoordinatorTest records 1000 commits with 1 initial read, 0 during-transfer reads, 1 explicit boundary read and 1 release read. This uses the production flow coordinator under controlled notifications, not a live phone Room trace.
- MediaBrowseIndexTest records 20000 records/1000 changes with 1 initial build, 0 intermediate builds and 1 boundary build; final albums contain 19000 source and 1000 destination records without losing IDs.
- QuickSortUiUpdatesTest records 0 gallery changes for 1000 intermediate completions and 1 terminal change. Notification progress still reads the authoritative queue.
- Room-generated DAO watchers and Hilt's shared singleton provider were inspected after generation.
- APK: released_apks/4.3.5.5-auto-multitask/batch-refresh-fix-test/PixelGallery-4.3.5.5-batch-refresh-fix-arm64-v8a.apk; 23933786 bytes; SHA-256 26076ce92b0aeb4a71e3aedcd15c2d1fa3a8696aaec543eaf322ccee363b3ff4.
- Package/version remain com.pixel.gallery.multitask / 4072; arm64-v8a only; signing certificate matches the original 4.3.5.5 release. Prior APKs were kept separately.
- User-phone scrolling/ANR/crash acceptance remains pending. The controlled workload reduction is not proof of a particular phone frame rate. No UI gestures were automated.
