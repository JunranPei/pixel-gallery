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
