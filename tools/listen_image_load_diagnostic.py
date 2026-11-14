"""Continuous USB log collection with bounded, consecutive system-trace segments."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--minutes', type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.minutes <= 30:
        parser.error('--minutes must be between 1 and 30')
    args.output.mkdir(parents=True, exist_ok=False)
    adb = ['D:/Android_Studio_Sdk/platform-tools/adb.exe', '-s', args.device]
    stop = args.output / 'STOP'
    state_path = args.output / 'state.json'
    def state(**values):
        state_path.write_text(json.dumps(values, indent=2), encoding='utf-8')
    deadline = time.monotonic() + args.minutes * 60
    capture_tool = Path(__file__).with_name('capture_image_load_diagnostic.py')
    with (args.output / 'continuous-image-events.txt').open('wb') as events, (args.output / 'worker-log.txt').open('wb') as errors:
        listener = subprocess.Popen(adb + ['logcat', '-v', 'monotonic', '-T', '1', 'ViewerLoadMetrics:I', '*:S'], stdout=events, stderr=errors)
        count = 0
        state(status='listening', device=args.device, segments=count, maxMinutes=args.minutes)
        try:
            while not stop.exists() and time.monotonic() < deadline:
                count += 1
                segment = args.output / f'trace-{count:03d}'
                result = subprocess.run([sys.executable, '-X', 'utf8', str(capture_tool), '--device', args.device,
                    '--seconds', '30', '--output', str(segment)], stdout=errors, stderr=errors, timeout=60)
                state(status='listening' if result.returncode == 0 else 'trace-error', device=args.device,
                    segments=count, lastReturnCode=result.returncode, maxMinutes=args.minutes)
                if result.returncode != 0:
                    break
        except Exception as error:
            state(status='error', device=args.device, segments=count, error=repr(error))
            raise
        finally:
            listener.terminate()
            try:
                listener.wait(timeout=5)
            except subprocess.TimeoutExpired:
                listener.kill()
                listener.wait()
        state(status='stopped', device=args.device, segments=count, requestedStop=stop.exists())

if __name__ == '__main__':
    main()
