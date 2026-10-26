# Bulk-transfer regression investigation

The user reports freezing and process exits while moving large batches on their own phone after 4.3.5.5. Emulator logs do not establish the cause of that phone's failure. No phone-specific crash trace has been inspected. Existing implementation uses Dispatchers.IO for filesystem work and a serial queue executor.

## Confirmed code paths addressed

1. Every per-file checkpoint previously serialized the entire persisted queue into a String, converted it to a second byte array, and atomically rewrote that whole file. The execution loop also persisted a redundant Running job update before every item. Large batches multiplied serialization, allocations and durable writes.
2. Every checkpoint was published to the UI. MainScaffold derived receipt lists, reserved ID sets and other collection state from these full snapshots. Pure recovery checkpoints have no visual significance.
3. Each affected Room invalidation could trigger a paged full-library/favourites read, followed by browse-index regeneration. Merge rapid invalidations before issuing these queries, while retaining the first and final notification.

## Changes

- Keep atomic full snapshots for structural changes (enqueue, permission/conflict/cancel status, completion, recovery). Stream Gson directly to the snapshot writer and flush before AtomicFile.finishWrite.
- Append a checksummed item record for a single active item's change, fsync before continuing any filesystem mutation, and publish the in-memory state only after successful persistence. Compound retry updates use full snapshots. Identical job updates do not write or notify again.
- Revisions prevent older deltas from overwriting a newer atomic snapshot after an interruption between snapshot commit and log truncation. Replay ignores only an incomplete final record, truncates it before further appends, and stops without erasing evidence on corrupt complete records. Legacy version-1 snapshots without revision remain readable. Compact after 8 MiB of deltas.
- Compare meaningful UI progress on Dispatchers.Default, omit pure checkpoint-stage changes, and coalesce visible progress at 150 ms. Backend permissions, conflict resolution and execution read the authoritative unthrottled state. Notify the foreground-service progress at 750 ms.
- Cache derived counts and reservations per immutable snapshot. Cache fields are transient and rebuild after Gson recovery.
- Full-library and favourites invalidations deliver the first event immediately and merge subsequent events across a 150 ms interval before their next full read. This is notification coalescing; every media/index transaction still commits normally and the last database state is read.
- Recovery checkpoints, verified-file moves, label/index association updates, undo and replacement backup rules remain required; no additional parallel filesystem workers are introduced.

## Verification matrix

| Case | Layer | Evidence/status |
|---|---|---|
| 1000-item queue with 1000 durable item changes; no per-item full rewrite | Real host journal files + JVM | QuickSortJournalStoreTest; scratch/bulk-transfer-deltas-build.log |
| Legacy snapshot, replay, partial final record, corrupted complete record | Real host journal files + JVM | QuickSortJournalStoreTest |
| Interruption after snapshot commit before delta truncation; first-success/usage replay | Real host journal files + JVM | QuickSortJournalStoreTest |
| Checkpoint-only suppression; completion/error/permission updates; cache after Gson reload | JVM | QuickSortUiUpdatesTest |
| Existing cancellation/undo/persistence/retry/transfer rules | JVM | Selected QuickSort and MediaTransfer test suites |
| Merge 1000 database invalidations, deliver first/final and single update | JVM | MediaInvalidationsTest |
| Actual phone freezing/crash regression and long file transfer under active browsing | User phone | Not yet verified; prior emulator/host checks cannot establish phone acceptance |

The scale test measures journal bytes and full snapshot counts, not real media-transfer throughput or phone frame times. No user media is moved in these checks.

First run: 70 relevant JVM cases passed. The 1000-item/1000-change journal test wrote one full snapshot plus deltas totaling 1,113,005 bytes; the previous approach would rewrite at least 320,029,000 bytes for the same fixture. The later invalidation-coalescing refinement requires the delivery run recorded in scratch/bulk-transfer-fix-final-build.log.

Delivery run completed: 72 relevant JVM cases passed, no failures/errors; arm64 release compiled successfully. The repair APK is kept separately at released_apks/4.3.5.5-auto-multitask/bulk-transfer-fix-test/PixelGallery-4.3.5.5-bulk-transfer-fix-arm64-v8a.apk. Signature matches the published 4.3.5.5 APK. SHA-256: f3b74d1e7f5490bd6f7af227b9bafd7ffd4e3eec19e25f3d5c218470734d17a9. No phone-side installation or UI acceptance is claimed.

References: [Android ANR guidance](https://developer.android.com/topic/performance/views/vitals/anr-views), [Gson streaming guide](https://github.com/google/gson/blob/main/UserGuide.md).

## Follow-up: per-cell churn, IME and viewer overlays

The user tested the first repair and still reports stutter with many files, keyboard-driven sheet displacement, and a viewer dismissal gesture winning over the destination sheet's collapse drag. These reports are not marked resolved by host checks.

- The existing grid already held slots and removed completed batches together, but a per-file database update still replaced its original Glide thumbnail with a placeholder, and changed hourglass to completion icon. Keep each active batch's original thumbnail and pending cover for the entire batch, including files already succeeded/failed/cancelled inside a nonterminal job. Release failures/cancellations only at the batch boundary; successful paths disappear together. When removal waits for a selection stroke/scroll, retain the pending cover until that removal actually applies.
- Calculate grid reconciliation and collection equality on Dispatchers.Default. An identical visual result reuses the previous presentation, avoiding per-file grid replacement. Before applying a changed presentation, recheck touch/selection/scroll ownership: a stroke may have started while the background calculation was running. Pending covers are separate from backend reservations; filesystem permissions/deduplication still use authoritative item status.
- Precompute move-receipt lists in the queue's background UI preparation. Skip whole-library selection/transfer maps when there are no recipients.
- Remove outer sheet imePadding and the keyboard-triggered forced expansion. Keep the sheet/header geometry stable; apply keyboard padding only to the body inside the existing navigation-bar padding. All three pickers use this component.
- The viewer's dismiss/rotation dispatcher runs before Compose hit testing. Register the actual sheet and undo-button window bounds as exclusions, including blank areas. Bounds are queried live as the sheet moves/collapses and removed on disposal. A stroke beginning in an excluded region never becomes eligible for image dismissal or rotation when it leaves the region.
- Viewer exit now removes its own route plus covering destination routes, instead of dropping only the destination route and leaving an already-dismissed viewer mounted to appear again.

Additional cases: 1000 individual database removals preserve the same thumbnails and covers; same-ID source-path changes; deferred removal and failed originals; pending-vs-backend reservation semantics; sheet/header/grid/blank-area exclusions, live collapsed bounds, disposal and independent undo-button exclusion. Run log: scratch/batch-grid-sheet-fix-build.log. Keyboard animation, detail-page dragging and actual phone frame times remain manual acceptance cases.

Follow-up delivery: release build and 109 JVM cases passed (19 suites, zero failures/errors/skips). The 1000-commit grid-presentation case is part of QuickSortGridPresentationTest (9 cases, 0.167 s total); this is a presentation-data assertion, not a rendered-frame performance measurement. APK: released_apks/4.3.5.5-auto-multitask/batch-grid-sheet-fix-test/PixelGallery-4.3.5.5-batch-grid-sheet-fix-arm64-v8a.apk; 23,927,542 bytes; SHA-256 732f888ba1a1ffcd348d046d242522dc5ea49732733c8df7d2f1d8259587ed5a. Package/version remain com.pixel.gallery.multitask / 4072 and the signature matches the previous release. The original release and first repair APK hashes remain unchanged. No device UI actions were automated.

References: [Compose inset padding and consumption](https://developer.android.com/develop/ui/compose/system/insets-ui), [Compose gesture dispatch and consumption](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures).
