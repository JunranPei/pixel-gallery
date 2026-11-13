"""Energy arithmetic, baseline subtraction and reproducible uncertainty summaries."""
from __future__ import annotations

import random
import statistics
from emi import PWH_TO_J


def energy_window(first, last, channel):
    a, b = first['channels'][channel], last['channels'][channel]
    de = b['energy_pwh'] - a['energy_pwh']
    dt = (b['time_100ns'] - a['time_100ns']) / 1e7
    if de < 0 or dt <= 0:
        raise ValueError('Energy counter reset, wrapped, or did not advance')
    elapsed = (last['monotonic_ns'] - first['monotonic_ns']) / 1e9
    if elapsed <= 0 or abs(dt - elapsed) > max(1, elapsed * .10):
        raise ValueError(f'Energy and host clocks disagree: {dt} vs {elapsed}')
    joules = de * PWH_TO_J
    return {'energy_j': joules, 'meter_seconds': dt, 'host_seconds': elapsed,
            'average_power_w': joules / dt,
            'midpoint_s': (first['monotonic_ns'] + last['monotonic_ns']) / 2e9}


def subtract_bracket(before, work, after):
    t0, tw, t1 = [x['midpoint_s'] for x in (before, work, after)]
    if not t0 < tw < t1:
        raise ValueError('Work must lie between its baseline windows')
    weight = (tw - t0) / (t1 - t0)
    watts = before['average_power_w'] * (1 - weight) + after['average_power_w'] * weight
    # Keep negative values: zero-clamping systematically biases small effects upward.
    return {'baseline_power_w': watts,
            'baseline_drift_w': after['average_power_w'] - before['average_power_w'],
            'incremental_energy_j': work['energy_j'] - watts * work['meter_seconds'],
            'incremental_power_w': work['average_power_w'] - watts}


def summarize(values, seed=20261113):
    if not values:
        raise ValueError('No samples')
    rng = random.Random(seed)
    boots = sorted(statistics.mean(rng.choices(values, k=len(values))) for _ in range(10000))
    return {'count': len(values), 'mean': statistics.mean(values), 'median': statistics.median(values),
            'min': min(values), 'max': max(values),
            'bootstrap_mean_95pct_interval': [boots[250], boots[9750]],
            'interval_method': 'percentile bootstrap; exploratory with small sample counts'}
