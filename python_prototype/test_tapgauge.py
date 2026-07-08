"""
TapGauge DSP + calibration validation suite (spec section 9).

Runnable with `python3 test_tapgauge.py` -- no third-party deps required for the
core assertions. numpy is used ONLY as an independent cross-check of the
hand-rolled FFT (skipped automatically if numpy is absent).

Proves: hand-rolled radix-2 FFT matches a reference DFT (and numpy); a decaying
noisy sinusoid at a known frequency is recovered to within a tight tolerance;
parabolic sub-bin interpolation beats raw-bin reading; harmonic overtones don't
fool the fundamental picker; median-of-3 rejects a bad tap; the calibration
curve interpolates/enforces-monotonicity/clamps-extrapolation/scores-confidence/
detects-drift; and an end-to-end synthetic tank walk tracks truth within +/-10%.
"""

from __future__ import annotations

import math
import os
import random
import sys
import unittest

# Import local modules without setting PYTHONPATH (PYTHONPATH=. shadows
# site-packages in the Snowflake sandbox and hides numpy).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from tapgauge_dsp import (
    Peak, analyze_tap, bin_to_hz, combine_taps, fft, hann_window,
    magnitude_spectrum, next_pow2, parabolic_interpolation, reject_harmonics,
)
from tapgauge_calibration import (
    CalibrationEngine, CalibrationPoint, Confidence, days_until_empty,
)

SR = 44100


def decaying_sinusoid(freq, sr, dur_s, decay_tau_s=0.03, amp=1.0, phase=0.0):
    n = int(sr * dur_s)
    out = []
    for i in range(n):
        t = i / sr
        out.append(amp * math.exp(-t / decay_tau_s) * math.sin(2 * math.pi * freq * t + phase))
    return out


def add_noise(sig, snr_db, rng):
    power = sum(x * x for x in sig) / max(len(sig), 1)
    if power <= 0:
        return list(sig)
    noise_power = power / (10 ** (snr_db / 10.0))
    sigma = math.sqrt(noise_power)
    return [x + rng.gauss(0.0, sigma) for x in sig]


def knock_segment(freq, sr=SR, dur_s=0.10, snr_db=20.0, seed=0, overtones=None, decay_tau_s=0.03):
    rng = random.Random(seed)
    sig = decaying_sinusoid(freq, sr, dur_s, decay_tau_s=decay_tau_s, amp=1.0)
    if overtones:
        for mult, a in overtones:
            ov = decaying_sinusoid(freq * mult, sr, dur_s, decay_tau_s=decay_tau_s * 0.7,
                                   amp=a, phase=rng.uniform(0, 2 * math.pi))
            sig = [s + o for s, o in zip(sig, ov)]
    n_attack = int(sr * 0.002)
    for i in range(min(n_attack, len(sig))):
        sig[i] += rng.uniform(-0.8, 0.8)
    return add_noise(sig, snr_db, rng)


def naive_dft(x):
    n = len(x)
    out = []
    for k in range(n):
        s = 0j
        for t in range(n):
            s += x[t] * complex(math.cos(-2 * math.pi * k * t / n),
                                math.sin(-2 * math.pi * k * t / n))
        out.append(s)
    return out


class TestFFT(unittest.TestCase):
    def test_matches_naive_dft(self):
        rng = random.Random(1)
        x = [complex(rng.uniform(-1, 1), 0.0) for _ in range(64)]
        for a, b in zip(fft(x), naive_dft(x)):
            self.assertAlmostEqual(a.real, b.real, places=6)
            self.assertAlmostEqual(a.imag, b.imag, places=6)

    def test_rejects_non_power_of_two(self):
        with self.assertRaises(ValueError):
            fft([1 + 0j] * 3)

    def test_next_pow2(self):
        self.assertEqual(next_pow2(3528), 4096)
        self.assertEqual(next_pow2(4096), 4096)
        self.assertEqual(next_pow2(4097), 8192)

    def test_hann_window_endpoints_zero(self):
        w = hann_window(1024)
        self.assertAlmostEqual(w[0], 0.0, places=9)
        self.assertLess(w[1], 0.01)
        self.assertGreater(max(w), 0.99)

    def test_cross_check_numpy(self):
        try:
            import numpy as np
        except ImportError:
            self.skipTest("numpy not available")
        rng = random.Random(7)
        x = [rng.uniform(-1, 1) for _ in range(128)]
        mine = fft([complex(v, 0.0) for v in x])
        ref = np.fft.fft(np.array(x))
        for a, b in zip(mine, ref):
            self.assertAlmostEqual(a.real, float(b.real), places=5)
            self.assertAlmostEqual(a.imag, float(b.imag), places=5)


class TestPeakRecovery(unittest.TestCase):
    def test_recovers_known_frequency_clean(self):
        for f in (180.0, 437.0, 512.5, 1234.0, 1997.3):
            res = analyze_tap(knock_segment(f, snr_db=40.0, seed=int(f)), SR)
            self.assertIsNotNone(res.fundamental_hz, f"no peak for {f} Hz")
            self.assertLess(abs(res.fundamental_hz - f) / f, 0.01,
                            f"got {res.fundamental_hz:.2f} for true {f}")

    def test_recovers_under_noise(self):
        f = 623.0
        errs = []
        for seed in range(15):
            res = analyze_tap(knock_segment(f, snr_db=15.0, seed=seed), SR)
            self.assertIsNotNone(res.fundamental_hz)
            errs.append(abs(res.fundamental_hz - f) / f)
        errs.sort()
        self.assertLess(errs[len(errs) // 2], 0.02)

    def test_parabolic_beats_raw_bin(self):
        f = 517.3
        mags = magnitude_spectrum(knock_segment(f, snr_db=45.0, seed=3))
        nfft = (len(mags) - 1) * 2
        k = max(range(1, len(mags) - 1), key=lambda i: mags[i])
        raw_hz = bin_to_hz(k, SR, nfft)
        refined_bin, _ = parabolic_interpolation(mags, k)
        refined_hz = bin_to_hz(refined_bin, SR, nfft)
        self.assertLess(abs(refined_hz - f), abs(raw_hz - f))
        self.assertLess(abs(refined_hz - f) / f, 0.01)


class TestHarmonicRejection(unittest.TestCase):
    def test_fundamental_wins_over_overtones(self):
        f = 300.0
        res = analyze_tap(knock_segment(f, snr_db=35.0, seed=9,
                                        overtones=[(2.0, 0.7), (3.0, 0.5)]), SR)
        self.assertIsNotNone(res.fundamental_hz)
        self.assertLess(abs(res.fundamental_hz - f) / f, 0.02)

    def test_reject_harmonics_drops_multiples(self):
        peaks = [Peak(300.0, 10.0, 10.0), Peak(600.0, 6.0, 6.0),
                 Peak(900.0, 4.0, 4.0), Peak(740.0, 5.0, 5.0)]
        kept = sorted(round(p.freq_hz) for p in reject_harmonics(peaks))
        self.assertIn(300, kept); self.assertIn(740, kept)
        self.assertNotIn(600, kept); self.assertNotIn(900, kept)


class TestMultiTap(unittest.TestCase):
    def test_median_rejects_one_bad_tap(self):
        f = 800.0
        good1 = analyze_tap(knock_segment(f, seed=1, snr_db=35), SR)
        good2 = analyze_tap(knock_segment(f, seed=2, snr_db=35), SR)
        bad = analyze_tap(knock_segment(f * 1.5, seed=3, snr_db=35), SR)
        reading = combine_taps([good1, bad, good2])
        self.assertIsNotNone(reading.frequency_hz)
        self.assertLess(abs(reading.frequency_hz - f) / f, 0.02)

    def test_large_spread_flagged_not_ok(self):
        r = combine_taps([
            analyze_tap(knock_segment(500, seed=1, snr_db=35), SR),
            analyze_tap(knock_segment(900, seed=2, snr_db=35), SR),
            analyze_tap(knock_segment(1500, seed=3, snr_db=35), SR),
        ])
        self.assertFalse(r.ok)


class TestCalibration(unittest.TestCase):
    def _engine_full_empty(self):
        eng = CalibrationEngine()
        eng.add_anchor(CalibrationPoint(0.0, 900.0, 100.0))
        eng.add_anchor(CalibrationPoint(1.0, 500.0, 0.0))
        return eng

    def test_linear_interpolation_midpoint(self):
        est = self._engine_full_empty().estimate_level(700.0)
        self.assertAlmostEqual(est.percent, 50.0, delta=0.1)
        self.assertFalse(est.extrapolated)
        self.assertEqual(est.confidence, Confidence.ROUGH)

    def test_extrapolation_clamped_and_flagged(self):
        eng = self._engine_full_empty()
        hi = eng.estimate_level(1000.0)
        self.assertEqual(hi.percent, 100.0); self.assertTrue(hi.extrapolated)
        lo = eng.estimate_level(400.0)
        self.assertEqual(lo.percent, 0.0); self.assertTrue(lo.extrapolated)

    def test_monotonicity_enforced(self):
        eng = self._engine_full_empty()
        check = eng.add_anchor(CalibrationPoint(2.0, 1200.0, 50.0), enforce_monotonic=True)
        self.assertFalse(check.ok)
        self.assertEqual(eng.anchor_count(), 2)
        check2 = eng.add_anchor(CalibrationPoint(3.0, 700.0, 50.0), enforce_monotonic=True)
        self.assertTrue(check2.ok)
        self.assertEqual(eng.anchor_count(), 3)
        self.assertEqual(eng.confidence(), Confidence.MEDIUM)

    def test_confidence_levels(self):
        eng = CalibrationEngine()
        self.assertEqual(eng.confidence(), Confidence.UNCALIBRATED)
        eng.add_anchor(CalibrationPoint(0, 900, 100))
        self.assertEqual(eng.confidence(), Confidence.UNCALIBRATED)
        eng.add_anchor(CalibrationPoint(1, 500, 0))
        self.assertEqual(eng.confidence(), Confidence.ROUGH)
        eng.add_anchor(CalibrationPoint(2, 700, 50))
        self.assertEqual(eng.confidence(), Confidence.MEDIUM)
        eng.add_anchor(CalibrationPoint(3, 800, 75))
        self.assertEqual(eng.confidence(), Confidence.HIGH)

    def test_drift_detection(self):
        eng = self._engine_full_empty()
        self.assertTrue(eng.check_drift(960.0).drifted)
        self.assertFalse(eng.check_drift(905.0).drifted)

    def test_days_until_empty(self):
        day = 86400.0
        hist = [(0 * day, 100.0), (1 * day, 90.0), (2 * day, 80.0), (3 * day, 70.0)]
        d = days_until_empty(hist)
        self.assertIsNotNone(d)
        self.assertAlmostEqual(d, 7.0, delta=0.5)
        self.assertIsNone(days_until_empty([(0, 50.0), (day, 60.0)]))


class TestEndToEndSimulation(unittest.TestCase):
    @staticmethod
    def true_freq_for_level(pct):
        return 480.0 + 460.0 * (pct / 100.0) ** 0.85

    def _measure(self, pct, seed):
        f = self.true_freq_for_level(pct)
        taps = [analyze_tap(knock_segment(f, seed=seed + i, snr_db=22.0), SR) for i in range(3)]
        return combine_taps(taps)

    def test_curve_tracks_truth_within_10pct(self):
        eng = CalibrationEngine()
        for i, pct in enumerate((0.0, 40.0, 70.0, 100.0)):
            r = self._measure(pct, seed=100 + i * 7)
            self.assertTrue(r.ok, f"calibration tap at {pct}% was not ok")
            check = eng.add_anchor(CalibrationPoint(float(i), r.frequency_hz, pct))
            self.assertTrue(check.ok, f"anchor {pct}% rejected: {check.reason}")
        self.assertEqual(eng.confidence(), Confidence.HIGH)

        max_err = 0.0
        for j, true_pct in enumerate((15.0, 30.0, 55.0, 85.0)):
            r = self._measure(true_pct, seed=500 + j * 11)
            est = eng.estimate_level(r.frequency_hz)
            self.assertIsNotNone(est.percent)
            err = abs(est.percent - true_pct)
            max_err = max(max_err, err)
            self.assertLessEqual(err, 10.0,
                                 f"at true {true_pct}% estimated {est.percent:.1f}% (err {err:.1f})")
        print(f"\n[E2E] worst intermediate error: {max_err:.2f}% (bar: 10%)")


if __name__ == "__main__":
    unittest.main(verbosity=2)
