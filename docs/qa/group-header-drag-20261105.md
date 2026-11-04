# Group header drag and viewer trace build — 2026-11-05

Scope: group headers always use saved manual order, label groups before ordinary groups. Group content sorting/pinning remains independent. No new UI controls. Long press selects and begins drag; selected group also supports immediate drag. Member cells resolve to their owning group only when dragging a group.

Reference: https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures (gesture consumption and long press).

| Case | Layer | Result |
|---|---|---|
| Expanded group moves as a whole, member target accepted | Model + real MainScaffold | PASS |
| Label group drag, ordinary group drag | Real MainScaffold + settings | PASS |
| Cross-type drag rejected | Model + real MainScaffold | PASS |
| Manual order independent of global sort; new groups append; JSON round trip | Model | PASS |
| Existing member custom/pin drag and dialog dismissal | Real MainScaffold regression | PASS |
| Pixel Trace metrics enabled, correct package and ARM64 APK | Build/artifact | Built after this commit; artifact JSON records commit, flags and SHA256 |
| Flicker reproduced on user's phone | Device | Not tested; this delivery supplies diagnostics, not a claimed flicker fix |

Build uses committed sources plus this change, excluding unrelated pending viewer/transfer edits. Isolated UI fixtures restore original group settings and delete only their own media in finally. Runtime evidence: scratch/group-drag-device.log. Build evidence: scratch/group-drag-build.log.

Model: 7 tests, 0 failures. Real UI: all 9 workflows passed on emulator-5562. Before fix, identical long-press drag retained [Label A, Label B]; after fix it persisted [Label B, Label A]. Evidence: scratch/group-drag-before-device.log and scratch/group-drag-device.log; screenshot and installed APK hash in scratch/group-drag-evidence/.

Boundary test uses adjacent cross-type headers, so it does not accidentally perform a legitimate same-type reorder while travelling to the boundary. Tests compare only their own fixture label groups, excluding the device's existing default Labels group.

Not tested: prolonged edge auto-scroll, process interruption during a drag, storage write failure. Existing export schema carries the saved group ordering without a new format.
