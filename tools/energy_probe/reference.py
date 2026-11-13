"""Explicit CPU-work conversion; absent calibration never becomes phone energy."""
import statistics


def calibration_factor(pairs):
    if len(pairs)<3: raise ValueError('At least three paired CPU-time measurements required')
    factors=[]
    for p in pairs:
        if not p.get('provenance') or p['emulator_cpu_ms']<=0 or p['reference_cpu_ms']<=0:
            raise ValueError('Each calibration pair needs positive CPU times and provenance')
        factors.append(p['reference_cpu_ms']/p['emulator_cpu_ms'])
    return statistics.median(factors)


def cpu_reference(guest_delta_cpu_ms, profile):
    calibration=profile.get('calibration')
    factor=calibration_factor(calibration['pairs']) if calibration else None
    results=[]
    for scenario in profile['scenarios']:
        # 1 mW * 1 ms = 1 microjoule.
        equivalent=guest_delta_cpu_ms*scenario['active_mw']/1e6
        results.append({'scenario':scenario['id'],'active_mw':scenario['active_mw'],
            'work_equivalent_j_if_time_scale_equals_1':equivalent,
            'calibrated_cpu_active_delta_j':equivalent*factor if factor is not None else None})
    return {'reference_profile':profile['id'],'scope':profile['scope'],
        'cpu_time_scale':factor,'phone_total_energy_j':None,
        'unit_time_scale_is_a_condition_not_a_measured_calibration':True,'scenarios':results}
