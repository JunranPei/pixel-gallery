# Branch 1 sync to 4.3.5.9

Target: `main`. Original and source SHAs and the real merge base are fixed in `original-inventory.json`.
Backup: `backup/main-before-status-sync-20261029`.
The merge has both original branch tip and feature-3 `7e6bec97` as parents.

## Preserved branch identity

- Production package: `com.pixel.gallery`.
- Version: `4.3.5.9-main`; base code 2076, arm64 split 4076.
- SingleTop default activity; feature-3's documentLaunchMode=always is deliberately omitted.
- Original debug/diagnostic package matrix, cold-read tests, v8 indexed JPEG/PNG pipeline, archive deletions, gitlink and native build customization remain.
- Native libraries stay uncompressed and the source build retains four ABI splits/universal output support. This synchronization checks isolated x86_64 test packages; no new production installation is performed.


## Shared updates

Current shared shell and theme, labels/migration, transfer journal, classification, video/PiP, deduplication status artwork and progress, LabelOff action, loading guards and swipe-dismiss toasts are integrated from feature-3.

## Deliberately different from feature-3

- Branch package IDs, task policy, 16KB direct-load packaging and diagnostic variants are retained.
- Local v8 addressable JPEG/PNG previews, source-index generations and cold-read diagnostics are retained instead of replacing them with feature-3's older tile/index stack.
- Source/cache concurrency remains bounded; the current viewer window implementation replaces the old no-op request extension.
- Both modern JPEG cores are isolated: 3.2.0 for local indexed tiles and 3.1.3 for the shared native fit preview. ARM SIMD remains enabled; x86 uses the portable backend.

## Audit

All 147 local paths are in `original-inventory.json`. Unchanged blobs/modes and local deletions are checked mechanically. The 24 changed local paths have reviewed exact candidate objects in `decisions.json`. The `docs_archive` gitlink stays at local `74fbdf43d1c1aee992c2963e146375e34f018055`; no submodule history was rewritten.
Additional differences outside the local inventory are the shared feature-3 updates, the branch settings instrumentation/runner registration, this audit directory, and branch 2's localized shortcut title. These are intentional integration/verification changes.

Build and runtime evidence is recorded in `verification.md`; compilation and representative UI checks do not establish exhaustive device or decoder performance coverage.
