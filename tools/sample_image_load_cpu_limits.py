"""Read app thread CPU restrictions and cpufreq limits during a USB capture."""
import argparse
from pathlib import Path
import subprocess
import time

SNAPSHOTS = r'''
while true; do
  printf '\nSNAPSHOT '
  cat /proc/uptime
  app_pid=$(pidof com.pixel.gallery.loadtrace.nocache)
  printf 'APP_PID %s\n' "$app_pid"
  if [ -n "$app_pid" ]; then
    for task_dir in /proc/$app_pid/task/*; do
      IFS= read -r task_name < "$task_dir/comm" 2>/dev/null
      case "$task_name" in
        *adtrace*|glide*|pool-*|RenderThread)
          printf 'THREAD %s %s\n' "$task_dir" "$task_name"
          awk '/^Name:|^Pid:|^Cpus_allowed|^[0-9]+:|^uclamp|^effective uclamp|^policy |^prio / {print FILENAME, $0}' "$task_dir/status" "$task_dir/cgroup" "$task_dir/sched" 2>&1
          ;;
      esac
    done
  fi
  for policy_dir in /sys/devices/system/cpu/cpufreq/policy*; do
    printf 'POLICY %s\n' "$policy_dir"
    for leaf in related_cpus cpuinfo_max_freq scaling_max_freq scaling_min_freq scaling_cur_freq; do
      if IFS= read -r leaf_value < "$policy_dir/$leaf" 2>/dev/null; then
        printf '%s=%s\n' "$leaf" "$leaf_value"
      else
        printf '%s=UNREADABLE\n' "$leaf"
      fi
    done
  done
  for group in background foreground top-app; do
    if IFS= read -r leaf_value < "/dev/cpuset/$group/cpus" 2>/dev/null; then
      printf 'CPUSET %s %s\n' "$group" "$leaf_value"
    fi
  done
  sleep 1
done
'''

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--device', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--minutes', type=int, default=10)
    args = p.parse_args()
    if not 1 <= args.minutes <= 30:
        p.error('minutes must be between 1 and 30')
    args.output.mkdir(parents=True, exist_ok=True)
    adb = ['D:/Android_Studio_Sdk/platform-tools/adb.exe', '-s', args.device]
    stop = args.output / 'STOP'
    with (args.output/'cpu-limits.txt').open('xb') as output, (args.output/'cpu-limits-errors.txt').open('xb') as errors:
        proc = subprocess.Popen(adb+['shell',SNAPSHOTS], stdout=output, stderr=errors)
        deadline = time.monotonic()+args.minutes*60
        try:
            while not stop.exists() and time.monotonic()<deadline:
                if proc.poll() is not None:
                    raise RuntimeError(f'CPU restriction sampler exited with {proc.returncode}')
                time.sleep(0.5)
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
    (args.output/'cpu-limits-stopped.txt').write_text('stopped\n', encoding='utf-8')

if __name__=='__main__':
    main()
