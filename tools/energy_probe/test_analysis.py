import struct
import unittest
from analysis import energy_window, subtract_bracket, summarize
from emi import decode_metadata
from reference import cpu_reference, calibration_factor
from preferences import string_preference


def reading(energy, seconds):
    return {'monotonic_ns': int(seconds * 1e9), 'channels': {'pkg': {
        'energy_pwh': energy, 'time_100ns': int(seconds * 1e7)}}}


class EnergyTests(unittest.TestCase):
    def test_independent_physical_unit_conversion(self):
        # 1 Wh = 3600 J; exactly 1e12 pWh over one hour is one watt.
        x = energy_window(reading(0, 0), reading(10**12, 3600), 'pkg')
        self.assertAlmostEqual(x['energy_j'], 3600)
        self.assertAlmostEqual(x['average_power_w'], 1)

    def test_reset_and_stale_clock_rejected(self):
        for a, b in [(reading(50, 0), reading(49, 1)), (reading(0, 1), reading(0, 1))]:
            with self.assertRaises(ValueError): energy_window(a, b, 'pkg')

    def test_clock_mismatch_rejected(self):
        end = reading(1, 10); end['monotonic_ns'] = 1000000000
        with self.assertRaises(ValueError): energy_window(reading(0, 0), end, 'pkg')

    def test_asymmetric_baseline_linear_drift(self):
        before = dict(midpoint_s=0, average_power_w=10)
        after = dict(midpoint_s=30, average_power_w=40)
        work = dict(midpoint_s=10, average_power_w=25, energy_j=50, meter_seconds=2)
        x = subtract_bracket(before, work, after)
        self.assertEqual(x['baseline_power_w'], 20)
        self.assertEqual(x['incremental_energy_j'], 10)

    def test_negative_difference_not_clamped(self):
        before = dict(midpoint_s=0, average_power_w=10)
        after = dict(midpoint_s=2, average_power_w=10)
        work = dict(midpoint_s=1, average_power_w=8, energy_j=8, meter_seconds=1)
        self.assertEqual(subtract_bracket(before, work, after)['incremental_energy_j'], -2)

    def test_baseline_order_checked(self):
        a = dict(midpoint_s=1, average_power_w=1)
        with self.assertRaises(ValueError): subtract_bracket(a, a, a)

    def test_confidence_summary_constant_and_repeatable(self):
        self.assertEqual(summarize([2, 2, 2])['bootstrap_mean_95pct_interval'], [2, 2])
        self.assertEqual(summarize([1, 2, 4]), summarize([1, 2, 4]))

    def test_metadata_v2_units_and_truncation(self):
        name = 'RAPL_Package0_PKG\0'.encode('utf-16-le')
        meta = b'\0' * 66 + struct.pack('<H', 1) + struct.pack('<IH', 0, len(name)) + name
        self.assertEqual(decode_metadata(2, meta)[0]['name'], 'RAPL_Package0_PKG')
        with self.assertRaises(ValueError): decode_metadata(2, meta[:-2])
        bad = bytearray(meta); bad[68] = 1
        with self.assertRaises(ValueError): decode_metadata(2, bad)

    def test_missing_phone_calibration_stays_missing(self):
        p={'id':'example','scope':'CPU active only','calibration':None,'scenarios':[{'id':'x','active_mw':1000}]}
        r=cpu_reference(1000,p)
        self.assertEqual(r['scenarios'][0]['work_equivalent_j_if_time_scale_equals_1'],1)
        self.assertIsNone(r['scenarios'][0]['calibrated_cpu_active_delta_j'])
        self.assertIsNone(r['phone_total_energy_j'])

    def test_calibration_median_and_invalid_pair(self):
        pairs=[dict(emulator_cpu_ms=100,reference_cpu_ms=x,provenance='measured fixture') for x in [200,300,10000]]
        self.assertEqual(calibration_factor(pairs),3)
        with self.assertRaises(ValueError): calibration_factor(pairs[:1])
        pairs[0]['emulator_cpu_ms']=0
        with self.assertRaises(ValueError): calibration_factor(pairs)

    def test_real_protobuf_framing_with_newline_length(self):
        # AndroidX Value.string is field 5. SCALE_FADE is ten bytes, so its
        # binary length delimiter is 0x0a, not a line separator in text.
        key=b'viewer_opening_effect';value=b'SCALE_FADE'
        inner=b'\x2a\x0a'+value
        entry=b'\x0a'+bytes([len(key)])+key+b'\x12'+bytes([len(inner)])+inner
        data=b'\x0a'+bytes([len(entry)])+entry
        self.assertEqual(string_preference(data,'viewer_opening_effect'),'SCALE_FADE')
        self.assertIsNone(string_preference(data,'missing'))
        with self.assertRaises(ValueError):string_preference(data[:-1],'viewer_opening_effect')


if __name__ == '__main__': unittest.main()
