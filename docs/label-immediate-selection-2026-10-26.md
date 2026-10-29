# Immediate multi-media label editing

The selected-chip section and selected card state show the intersection of the current recipients' memberships. All labels remain available below it, including labels owned by only some recipients. An empty media selection has no shared labels and cannot submit edits.

Each click submits one explicit add/remove operation against a captured recipient set. No complete label list is replaced, no draft is saved at close, and the media selection remains intact after saving. Creating a label immediately creates and associates it; the search text clears only after success. While a save is pending, label actions and dismissal are disabled. Failure leaves the last committed state visible and preserves the create query.

Starting with A={1,2}, B={1}, the shared set is {1}. Independent cases:

| Action | A | B |
|---|---|---|
| Remove 1 | {2} | {} |
| Add 3 | {1,2,3} | {1,3} |
| Add 2 | {1,2} | {1,2} |

After Add 2, clicking 2 again removes it from both recipients; it is a toggle, not an undo of the preceding partial assignment. Removal only changes the associations on the selected recipients, never deletes the label itself.

Existing LabelRepository.applyEdits already applies targeted associations inside a Room transaction. Its DAO inserts use IGNORE on the composite labelId/contentId primary key, preserving an existing association. Reference: [Room OnConflictStrategy](https://developer.android.com/reference/androidx/room/OnConflictStrategy).

| Check | Level | Evidence/status |
|---|---|---|
| Shared intersection, duplicate observations, empty/missing recipient, changing group | JVM LabelsTest | Build log: scratch/labels-immediate-shared-build.log |
| Three user examples, repeated add, subsequent remove, empty recipients, invalid-label rollback | Isolated Room LabelMultiSelectionChecks | Compiled only; not executed for this handoff |
| Immediate click results, close without replay, selection retained, save failure, switching recipients while saving | User UI | Pending manual testing |

Debug delivery and instrumentation compilation: scratch/labels-immediate-delivery-build.log. No UI gestures or instrumentation suites are run during this handoff.
