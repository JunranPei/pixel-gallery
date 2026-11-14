"""Capture diagnostic image events and a matching Android system trace on one named device."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import shutil
import subprocess

PACKAGE = "com.pixel.gallery.loadtrace.nocache"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="Explicit adb device serial")
    parser.add_argument("--seconds", type=int, default=45)
    parser.add_argument("--output", type=Path, required=True, help="New output directory")
    parser.add_argument("--adb", default=shutil.which("adb") or "D:/Android_Studio_Sdk/platform-tools/adb.exe")
    args = parser.parse_args()
    if not 5 <= args.seconds <= 60:
        parser.error("--seconds must be between 5 and 60")
    args.output.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, "-s", args.device]
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    remote = f"/data/misc/perfetto-traces/pixel-load-{stamp}.perfetto-trace"
    config = f'''buffers {{ size_kb: 65536 fill_policy: DISCARD }}
duration_ms: {args.seconds * 1000}
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
    ftrace_events: "sched/sched_switch"
    ftrace_events: "sched/sched_waking"
    ftrace_events: "power/cpu_frequency"
    ftrace_events: "power/cpu_idle"
    atrace_categories: "gfx"
    atrace_categories: "view"
    atrace_categories: "am"
    atrace_categories: "wm"
    atrace_categories: "binder_driver"
    atrace_apps: "{PACKAGE}"
}} }} }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{
    scan_all_processes_on_start: true
    proc_stats_poll_ms: 1000
}} }} }}
'''
    (args.output / "trace-config.txt").write_text(config, encoding="utf-8")
    details = subprocess.run(adb + ["shell", "dumpsys", "package", PACKAGE], capture_output=True, check=True)
    (args.output / "installed-package.txt").write_bytes(details.stdout)
    if b"versionName=" not in details.stdout:
        raise RuntimeError(f"{PACKAGE} is not installed on {args.device}")
    with (args.output / "image-events.txt").open("wb") as log, (args.output / "capture-errors.txt").open("wb") as errors:
        # No buffer clearing: preserve prior phone logs. -T 1 starts near the current end.
        listener = subprocess.Popen(adb + ["logcat", "-v", "monotonic", "-T", "1", "ViewerLoadMetrics:I", "*:S"], stdout=log, stderr=errors)
        try:
            print(f"Capture started on {args.device}; operate Pixel Load Diagnostic for {args.seconds} seconds.", flush=True)
            result = subprocess.run(adb + ["shell", "perfetto", "--txt", "-c", "-", "-o", remote],
                input=config.encode(), stdout=errors, stderr=errors, timeout=args.seconds + 20)
            if result.returncode != 0:
                raise RuntimeError("Perfetto failed; see capture-errors.txt. Image events have been preserved.")
        finally:
            listener.terminate()
            try:
                listener.wait(timeout=5)
            except subprocess.TimeoutExpired:
                listener.kill()
                listener.wait()
    subprocess.run(adb + ["pull", remote, str(args.output / "system.perfetto-trace")], check=True)
    print(f"Saved image-events.txt and system.perfetto-trace in {args.output.resolve()}")

if __name__ == "__main__":
    main()
