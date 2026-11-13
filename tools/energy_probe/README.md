# Emulator energy sampling

Read-only Windows EMI package-energy sampling, combined with repeated UI actions in
the isolated `com.pixel.gallery.codexclean` emulator package. No additional driver,
administrator elevation, or app product change is required.

The host package counter reports cumulative picowatt-hours. Differences are converted
using `1 pWh = 3.6e-9 J`. Average power uses the meter's own timestamp; host and meter
clocks are checked. Package energy is the total; per-core subdomains are retained for
breakdown and never added to that total. No `_Total` performance counter is used.

## Run

From the repository root, with the visible GalleryAlbumSearchQA20261019 emulator and
the existing owned media fixtures (1080x2400, English UI, serial emulator-5554):

Python requires `psutil` for host process accounting and `matplotlib` for the report
plot. The native meter and arithmetic use the standard library. `adb` must be on PATH.
The UI helpers are included here; temporary audit scripts are not required.

```powershell
python -m unittest discover -s tools/energy_probe -v
python tools/energy_probe/probe_meter.py --output scratch/energy-meter-probe
python tools/energy_probe/benchmark.py --output scratch/energy-benchmark --repetitions 3
python tools/energy_probe/report.py scratch/energy-benchmark
```

Each block contains an idle baseline, a work window, and a second idle baseline.
The baseline power at the work midpoint is linearly interpolated. Signed differences,
raw samples, timestamps, guest CPU time, host CPU time, action counts, and baseline
drift are saved. Large baseline drift is flagged, without silently deleting trials.
Opening modes rotate in a balanced order across repetitions. Six opening, scrolling,
or navigation cycles run in each 18-second work window. Menu tests hold the menu
still for that interval. UI dumps and screenshots happen outside counted windows.

Settings are backed up before sampling and restored byte-for-byte with the app
stopped in `finally`. Existing output evidence is not overwritten. The output
`restore.json` records the restoration check. If the emulator exits unexpectedly,
keep `settings-before.pb` and restore it after reconnecting.
Use the same output path with `--resume` to retain completed blocks and collect
the remaining cases. Each resume saves its own settings backup and metadata.

## Phone model

`pixel6_cpu_reference.json` records three published Tensor G1 active CPU-core curve
points extracted from Perfetto v59.1. `reference.py` reports CPU-work equivalents
under an explicit unit time-scale assumption. A calibrated CPU-active estimate is
only produced when the profile supplies at least three positive paired emulator /
reference CPU-time measurements with provenance. Full-phone energy remains absent:
GPU, display, shared cluster and other domains are not covered by these three points.

A reference-equivalent work value is a conditional model output, not phone-meter
data. Repeatability intervals from host samples do not include cross-device model
error. Individual core domains are not assumed to partition the package exactly;
they are neither summed nor subtracted from the package to infer GPU energy.

## Sources

- [Microsoft EMI interface and units](https://learn.microsoft.com/windows-hardware/drivers/powermeter/energy-meter-interface)
- [Microsoft EMI measurement structure](https://learn.microsoft.com/windows/win32/api/emi/ns-emi-emi_channel_measurement_data)
- [Android Wattson device-specific power models](https://source.android.com/docs/core/power/wattson/wattson)
- [WattsOn resource scaling and power models](https://www.microsoft.com/en-us/research/wp-content/uploads/2012/08/WattsonMobicom12.pdf)
- [GreenScaler resource-feature modeling](https://softwareprocess.es/pubs/chowdhury2018EMSE-GreenScaler.pdf)
