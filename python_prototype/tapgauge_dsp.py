"""
TapGauge DSP core (reference implementation, pure Python / stdlib only).

This module is the *validation prototype* for the Android app's SpectralAnalyzer
(spec sections 5.1-5.6). It is deliberately written with NO third-party
dependencies (only the standard library) so that every routine here maps
one-to-one onto the dependency-free Kotlin port that ships in the app
(app/src/main/java/com/tapgauge/dsp/).

The whole point of this file: prove, with runnable tests, that a single
"knock" recording can be turned into a trustworthy fundamental-resonance
frequency. That is the piece the spec (section 0) calls "impossible as a
pen-and-paper exercise" -- an FFT plus sub-bin peak refinement.

Pipeline (per tap):
    raw PCM segment
      -> Hann window            (reduce spectral leakage, section 5.3)
      -> zero-pad to pow2       (FFT length + finer bin spacing)
      -> radix-2 FFT            (hand-rolled Cooley-Tukey, section 5.3)
      -> magnitude spectrum
      -> band-limited peak pick (section 5.4)
      -> parabolic sub-bin refine each peak (section 5.4)
      -> harmonic rejection     (drop ~2x,3x of a stronger lower peak)
      -> strongest survivor = fundamental
Per reading (3 taps): median of the three refined fundamentals (section 5.5).
"""

from __future__ import annotations

import cmath
import math
from dataclasses import dataclass
from statistics import median
from typing import Optional


# ---------------------------------------------------------------------------
# 5.3  Windowing
# ---------------------------------------------------------------------------
def hann_window(n: int) -> list[float]:
    """Periodic Hann window of length n.

    Rationale (section 5.3): a raw rectangular cut of the knock segment has
    hard edges, which smear energy across many FFT bins (spectral leakage) and
    can bury the resonant peak. The Hann taper forces the segment smoothly to
    zero at both ends, concentrating energy in the true peak's bin and its
    immediate neighbours -- which is exactly what the parabolic interpolation
    below assumes (a clean, roughly-parabolic peak in log-magnitude).

    We use the periodic form (divide by n, not n-1) because that is the
    correct convention for spectral analysis via the FFT.
    """
    if n <= 1:
        return [1.0] * max(n, 0)
    return [0.5 - 0.5 * math.cos(2.0 * math.pi * i / n) for i in range(n)]


def next_pow2(n: int) -> int:
    """Smallest power of two >= n. FFT length must be a power of two for the
    radix-2 routine, and zero-padding to a larger power of two also gives us
    finer frequency-bin spacing (interpolated spectrum), which helps peak
    picking on short knock segments."""
    p = 1
    while p < n:
        p <<= 1
    return p


# ---------------------------------------------------------------------------
# 5.3  FFT  (iterative radix-2 Cooley-Tukey)
# ---------------------------------------------------------------------------
def fft(signal: list[complex]) -> list[complex]:
    """In-order iterative radix-2 Cooley-Tukey FFT.

    len(signal) MUST be a power of two. This mirrors the Kotlin implementation
    we ship (no library), so if this is correct the port is trivial.

    Two stages:
      1. Bit-reversal permutation of the input (decimation in time).
      2. log2(N) butterfly stages, each combining pairs with twiddle factors.
    """
    n = len(signal)
    if n == 0:
        return []
    if n & (n - 1) != 0:
        raise ValueError(f"FFT length must be a power of two, got {n}")

    a = list(signal)

    # --- 1. bit-reversal permutation ---
    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j |= bit
        if i < j:
            a[i], a[j] = a[j], a[i]

    # --- 2. butterflies ---
    length = 2
    while length <= n:
        # principal twiddle for this stage: exp(-2*pi*i/length)
        ang = -2.0 * math.pi / length
        wlen = cmath.rect(1.0, ang)
        half = length >> 1
        for start in range(0, n, length):
            w = 1 + 0j
            for k in range(half):
                u = a[start + k]
                v = a[start + k + half] * w
                a[start + k] = u + v
                a[start + k + half] = u - v
                w *= wlen
        length <<= 1
    return a


def magnitude_spectrum(segment: list[float]) -> list[float]:
    """Window -> zero-pad -> FFT -> magnitude of the first N/2+1 bins.

    Only the lower half is returned because the input is real, so the spectrum
    is Hermitian-symmetric and the upper half carries no new information.
    """
    n = len(segment)
    if n == 0:
        return []
    win = hann_window(n)
    windowed = [segment[i] * win[i] for i in range(n)]
    nfft = next_pow2(n)
    padded: list[complex] = [complex(x, 0.0) for x in windowed] + [0j] * (nfft - n)
    spec = fft(padded)
    return [abs(spec[i]) for i in range(nfft // 2 + 1)]


# ---------------------------------------------------------------------------
# 5.4  Sub-bin peak refinement (parabolic / quadratic interpolation)
# ---------------------------------------------------------------------------
def parabolic_interpolation(mags: list[float], k: int) -> tuple[float, float]:
    """Refine an integer peak bin k to sub-bin resolution.

    Standard log-magnitude parabolic interpolation (see Julius O. Smith,
    "Spectral Audio Signal Processing"). We fit a parabola through three
    points -- the peak bin and its two neighbours -- in the LOG-magnitude
    domain, because a Gaussian-ish main lobe (which the Hann window produces)
    is close to a parabola on a dB scale, so the fit lands very near the true
    continuous peak. Reading the raw FFT bin instead would quantise frequency
    to the bin spacing (e.g. ~5-10 Hz), which is too coarse for calibration.

    Returns (refined_bin, interpolated_peak_magnitude).
    delta in [-0.5, 0.5] is the fractional offset from bin k.
    """
    if k <= 0 or k >= len(mags) - 1:
        # Can't interpolate at the very edges; return the bin as-is.
        return float(k), mags[k]

    # Guard against log(0) with a tiny floor.
    eps = 1e-12
    alpha = math.log(mags[k - 1] + eps)
    beta = math.log(mags[k] + eps)
    gamma = math.log(mags[k + 1] + eps)

    denom = (alpha - 2.0 * beta + gamma)
    if abs(denom) < 1e-18:
        return float(k), mags[k]

    delta = 0.5 * (alpha - gamma) / denom          # fractional bin offset
    # Interpolated peak height (in log domain), converted back to linear.
    peak_log = beta - 0.25 * (alpha - gamma) * delta
    return k + delta, math.exp(peak_log)


def bin_to_hz(bin_index: float, sample_rate: int, nfft: int) -> float:
    """Convert a (possibly fractional) FFT bin index to frequency in Hz."""
    return bin_index * sample_rate / nfft


# ---------------------------------------------------------------------------
# 5.4  Peak picking + harmonic rejection
# ---------------------------------------------------------------------------
@dataclass
class Peak:
    freq_hz: float
    magnitude: float
    prominence: float   # peak height above the local noise floor (linear ratio)


def _local_noise_floor(mags: list[float]) -> float:
    """Median magnitude across the searched spectrum = robust noise floor.
    Median (not mean) so a couple of strong resonant peaks don't inflate it."""
    if not mags:
        return 0.0
    return median(mags)


def find_peaks_in_band(
    mags: list[float],
    sample_rate: int,
    nfft: int,
    f_lo: float,
    f_hi: float,
    min_prominence: float = 3.0,
) -> list[Peak]:
    """Find local maxima whose refined frequency falls in [f_lo, f_hi].

    section 5.4: the search is restricted to a plausible tank-resonance band
    (default 60-3000 Hz, per-profile configurable) so we don't lock onto mains
    hum, HVAC rumble, or high-frequency clatter. Each local maximum is refined
    to sub-bin resolution and kept only if it is at least `min_prominence`x
    above the local noise floor.
    """
    if len(mags) < 3:
        return []

    floor = _local_noise_floor(mags) + 1e-12
    lo_bin = max(1, int(math.floor(f_lo * nfft / sample_rate)))
    hi_bin = min(len(mags) - 2, int(math.ceil(f_hi * nfft / sample_rate)))

    peaks: list[Peak] = []
    for k in range(lo_bin, hi_bin + 1):
        # strict local maximum
        if mags[k] > mags[k - 1] and mags[k] >= mags[k + 1]:
            refined_bin, refined_mag = parabolic_interpolation(mags, k)
            freq = bin_to_hz(refined_bin, sample_rate, nfft)
            if freq < f_lo or freq > f_hi:
                continue
            prominence = refined_mag / floor
            if prominence >= min_prominence:
                peaks.append(Peak(freq, refined_mag, prominence))
    # Strongest first.
    peaks.sort(key=lambda p: p.magnitude, reverse=True)
    return peaks


def reject_harmonics(peaks: list[Peak], tol: float = 0.06) -> list[Peak]:
    """Drop peaks that sit near an integer multiple (2x, 3x, 4x, 5x) of a
    STRONGER lower-frequency peak -- those are overtones of that stronger
    fundamental, not independent resonances (section 5.4).

    We walk peaks strongest-first; a peak is discarded if its frequency is
    within `tol` (fractional) of n * f_kept for any already-kept, stronger,
    lower peak. tol=0.06 -> +/-6%, loose enough for real, slightly-inharmonic
    tank overtones but tight enough not to eat true separate modes.
    """
    kept: list[Peak] = []
    for p in peaks:  # already sorted strongest-first
        is_harmonic = False
        for base in kept:
            if base.freq_hz <= 0 or base.magnitude <= p.magnitude:
                continue
            if p.freq_hz <= base.freq_hz:
                continue
            ratio = p.freq_hz / base.freq_hz
            nearest_int = round(ratio)
            if nearest_int >= 2 and abs(ratio - nearest_int) <= tol:
                is_harmonic = True
                break
        if not is_harmonic:
            kept.append(p)
    return kept


# ---------------------------------------------------------------------------
# 5.4  Fundamental for a single tap
# ---------------------------------------------------------------------------
@dataclass
class TapResult:
    fundamental_hz: Optional[float]
    prominence: float
    all_peaks: list[Peak]


def analyze_tap(
    segment: list[float],
    sample_rate: int,
    f_lo: float = 60.0,
    f_hi: float = 3000.0,
    min_prominence: float = 3.0,
) -> TapResult:
    """Full single-tap pipeline (sections 5.3-5.4): windowed FFT -> band peaks
    -> harmonic rejection -> strongest survivor is the fundamental."""
    mags = magnitude_spectrum(segment)
    if not mags:
        return TapResult(None, 0.0, [])
    nfft = (len(mags) - 1) * 2
    peaks = find_peaks_in_band(mags, sample_rate, nfft, f_lo, f_hi, min_prominence)
    if not peaks:
        return TapResult(None, 0.0, [])
    survivors = reject_harmonics(peaks)
    if not survivors:
        return TapResult(None, 0.0, peaks)
    # Strongest surviving peak = fundamental.
    survivors.sort(key=lambda p: p.magnitude, reverse=True)
    best = survivors[0]
    return TapResult(best.freq_hz, best.prominence, survivors)


# ---------------------------------------------------------------------------
# 5.5  Multi-tap robustness
# ---------------------------------------------------------------------------
@dataclass
class ReadingResult:
    frequency_hz: Optional[float]
    spread_hz: float               # max-min across taps (consistency signal)
    prominence: float              # median prominence across taps
    tap_frequencies: list[float]
    ok: bool                       # False -> ask user to re-tap same spot


def combine_taps(tap_results: list[TapResult], max_spread_frac: float = 0.05) -> ReadingResult:
    """Combine N taps (spec uses 3) into one reading via the MEDIAN frequency.

    Median (section 5.5) is more robust to a single bad tap than the mean --
    one stray knock that locks onto a harmonic or a clatter won't drag the
    result. We also compute tap-to-tap spread; if it exceeds `max_spread_frac`
    of the median (default 5%), we flag the reading as low-confidence so the UI
    can say "try tapping the same spot again" instead of returning a bad number.
    """
    freqs = [t.fundamental_hz for t in tap_results if t.fundamental_hz is not None]
    if not freqs:
        return ReadingResult(None, 0.0, 0.0, [], False)

    med = float(median(freqs))
    spread = max(freqs) - min(freqs)
    proms = [t.prominence for t in tap_results if t.fundamental_hz is not None]
    med_prom = float(median(proms)) if proms else 0.0

    ok = True
    # Need at least 2 concordant taps and acceptable spread.
    if len(freqs) < 2:
        ok = False
    elif med > 0 and (spread / med) > max_spread_frac:
        ok = False

    return ReadingResult(med, spread, med_prom, freqs, ok)
