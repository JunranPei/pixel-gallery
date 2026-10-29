# Quick sort layout, Photos retention and transfer investigation

The user confirmed that the isolated 4.3.4.16 baseline also stutters. This does not establish a device-specific cause or prove which workload causes dropped frames.

## Delivered behavior

- Settings > Display gains a Quick sort group using the existing SettingsGroup, icon tint, typography, spacing and segmented control. Album capsule rows are persisted as 1–4, default 2; fewer destinations use only the required rows. No explanatory subtitle or extra settings page.
- Photos overview retains moved media in the original session slot. Completed receipts update the URI, ID and path; a verified receipt bridges the interval before the library observer publishes its update. Source album grids still remove completed batches together.
- Relocation follows successive moves and undo, including history trimming. Slot keys remain independent of the current file path, and active batches retain the pending cover.
- The main media grid now distinguishes date-header and photo content types. Each photo requests its existing 200 px thumbnail once, without a parallel 20 px preview request. Existing cache options and thumbnail dimensions remain intact. Model memoization now includes rotation and modification time.

Other settings worth considering, not implemented here: capsule cover visibility (cover versus initial letter), and long-press vibration. These are independent preferences; completed-batch visibility and file integrity remain behavior requirements.

## Transfer research and source audit

1. Android supports moving indexed media by updating MediaStore RELATIVE_PATH/DISPLAY_NAME. Our same-volume path already does this, subject to MIME/name compatibility and permissions. It preserves the existing media record where supported. [Android media storage](https://developer.android.com/training/data-storage/shared/media#update-item).
2. Native filesystem rename changes a directory entry; it cannot cross mounted filesystems. Plain rename may replace an existing destination. A future optimized publication path must preserve no-overwrite behavior, such as a supported RENAME_NOREPLACE operation, with capability checking and a safe fallback. Atomic visibility is not by itself crash durability. [Linux rename manual](https://man7.org/linux/man-pages/man2/rename.2.html).
3. SAF offers moveDocument only for providers advertising support. It does not establish timestamp, identity or content preservation for arbitrary providers. Current Quick sort deliberately accepts local albums; SAF expansion is separate work. [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract#moveDocument(android.content.ContentResolver,android.net.Uri,android.net.Uri,android.net.Uri)).
4. Work is already dispatched off the UI thread and serialized by the execution mutex. Dispatchers.IO shares workers with Default; it does not confer Android background scheduling priority. A dedicated, bounded worker with background priority is a candidate, along with interaction-aware admission of the next file. Do not change priority on a shared coroutine thread across suspensions, or pause halfway through deleting a source. [Kotlin IO dispatcher](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html), [Android threading](https://developer.android.com/topic/performance/threads).

Source-confirmed application read amplification in both 23b1c2a2 and the current implementation:

- Ordinary successful MediaStore move: fingerprintMediaFile(source), verifyMediaFile(actualTarget), then finishQuickMove calls verifyMediaFile(target) again: three full SHA-256 passes. Undo/recovery can add more.
- Local copy fallback: source -> temporary -> final, two full writes. Including fingerprint, verification inside each copy, pre-delete checks and final verification, the uncomplicated success path reads approximately ten file-lengths.
- These are logical application-level passes, not measured physical disk traffic; OS caches can serve some reads. They are a concrete optimization target, not proof of the phone's frame-time cause.

Recommended next transfer change: retain same-volume MediaStore moves; consolidate redundant verification stages; hash during necessary copy reads; where supported publish a verified temporary file without recopying it or overwriting another file; preserve durable checkpoints, original timestamps, labels/index relocation, conflict handling and undo. Profile elapsed time per stage and frame-time overlap before expanding concurrency. The transfer engine is not changed by this patch.

Rendering references: [Glide thumbnail request](https://bumptech.github.io/glide/javadocs/431/com/bumptech/glide/RequestBuilder.html), [Compose content types](https://developer.android.com/develop/ui/compose/lists#content-type).

## Verification

Focused JVM regression cases cover 1–4 rows, invalid values, few destinations, retained overview slots, changed IDs, receipt-before-library publication, subsequent moves, undo, history trimming, failure/cancellation, same-path replacement identities, and unchanged source-album removal behavior. Final build log: scratch/quicksort-rows-overview-verified-build.log. User-phone layout and scrolling acceptance remains manual.

Final run: 39 tests across 6 suites passed with no failures/errors; release assembly succeeded. APK: released_apks/4.3.5.5-auto-multitask/quicksort-rows-photos-fix/PixelGallery-4.3.5.5-quicksort-rows-photos-fix-arm64-v8a.apk. Package com.pixel.gallery.multitask, version code 4072, arm64-v8a only, 15 native libraries, 23,937,462 bytes. SHA-256: 9379f307b2f417f9c4277da1f365554d09b91c3b8ac2378daad97ca15c39ae67. Signature matches the previous release; alignment, package identity and absence of testOnly/split-only restrictions verified. No device installation or UI actions were performed.
