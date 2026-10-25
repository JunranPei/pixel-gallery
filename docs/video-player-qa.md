# Video player implementation and verification

Scope: autoplay, mute, stop/loop/next, position/speed memory, background pause/audio/PiP, frame capture and destination. Display uses existing SettingsGroup/SettingsOptionControl. Existing Slider and file-action rows remain. Rotation and screen-direction preferences are deferred; existing rotation is untouched.

References:
- https://developer.android.com/develop/ui/views/picture-in-picture
- https://developer.android.com/develop/ui/compose/system/pip-enter
- https://developer.android.com/reference/android/view/PixelCopy
- https://developer.android.com/media/media3/session/background-playback

| ID | Scenario | Expected | Level | Result |
|---|---|---|---|---|
| V01 | Saved settings, cold opening, autoplay off | No playback before preferences load; mute/speed applied | Device | Pending |
| V02 | Mute and speed dialog confirm/cancel | Actual audio volume/playback rate; cancellation preserves rate | Real UI + player | Pending |
| V03 | Paused frame capture | PNG contains video pixels only; one valid output; source unchanged | Real UI + storage | Pending |
| V04 | Bad save folder | Failure, no success or leaked pending MediaStore item | Device failure injection | Pending |
| V05 | Remember position and speed, reopen; memory off | Resume remembered state; new video 1× and position zero when disabled | Device | Pending |
| V06 | Playback ends | Stop, loop, next without wrapping past final video | Device | Pending |
| V07 | Home/background, settings overlay, manual pause | Pause default; audio mode continues with foreground service; manual pause respected | System lifecycle | Pending |
| V08 | Manual and automatic PiP, exit/close | Video only; restore toolbar on expansion; no playback after close | System UI | Pending |
| V09 | Display and viewer appearance | Existing dynamic colors, groups, dialogs, Slider and bottom rows | Screenshot review | Pending |
| V10 | Platform fallback | Same mute/speed/end/seek policy and aspect ratio | Device | Pending |
| V11 | Capture chosen SAF directory | Persisted grant, cancellation, actual write and cleanup on error | System picker/storage | Pending |
| V12 | Compile and regression | Build plus relevant existing viewer checks | Build/test | Pending |

## Handoff for manual testing

The user requested to perform interaction testing themselves. Automated UI execution was stopped; no further UI scenarios will run for this handoff. The interrupted run is not a pass. Its temporary video (MediaStore ID 3807) was removed, and the new player preferences written by the test were moved to a task-specific backup so the handoff starts with player defaults. Existing gallery settings and user media were retained.

An earlier cold-open check reached the viewer with autoplay disabled, mute and 1.5× applied. A screenshot showed the new toolbar with the existing Slider. The subsequent mute interaction did not establish success; the UI test sequence remains incomplete. All functional acceptance entries above therefore remain pending user testing.

Device power measurements and rotation testing are outside this change. Compilation and packaging are recorded separately from functional acceptance.

Final debug packaging succeeded on 2026-10-25 (`:app:assembleDebug`, 2m 13s; `scratch/player-final-build.log`). The package is `app/build/outputs/apk/debug/app-debug.apk`, built without the injected-ABI/test-only flag. Functional acceptance remains with the user.
