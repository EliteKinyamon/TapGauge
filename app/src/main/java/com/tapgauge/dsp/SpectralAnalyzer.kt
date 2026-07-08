package com.tapgauge.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

/** A refined spectral peak. [prominence] is the linear ratio of the peak
 *  height above the local noise floor (used for confidence, spec section 5.6). */
data class Peak(val freqHz: Double, val magnitude: Double, val prominence: Double)

/** Result of analysing a single tap (spec section 5.4). */
data class TapResult(
    val fundamentalHz: Double?,
    val prominence: Double,
    val peaks: List<Peak>,
)

/** Result of combining multiple taps into one reading (spec section 5.5). */
data class ReadingResult(
    val frequencyHz: Double?,
    val spreadHz: Double,
    val prominence: Double,
    val tapFrequencies: List<Double>,
    /** false -> ask the user to re-tap the same spot (low confidence). */
    val ok: Boolean,
)

/**
 * Turns an isolated knock waveform into a fundamental resonance frequency.
 *
 * Direct port of the Python reference validated in tests: recovered known
 * frequencies within 1% (clean) / <2% (15 dB SNR), rejected 2x/3x overtones,
 * and beat raw-bin reading via parabolic interpolation.
 *
 * Pipeline (spec sections 5.3-5.4):
 *   windowed segment -> zero-pad to pow2 -> FFT -> magnitude ->
 *   band-limited local maxima -> parabolic sub-bin refine ->
 *   harmonic rejection -> strongest survivor = fundamental.
 */
class SpectralAnalyzer(
    private val sampleRate: Int = 44100,
) {

    // ---------------------------------------------------------------- //
    // 5.3  Windowing
    // ---------------------------------------------------------------- //
    /**
     * Periodic Hann window. Rationale (spec section 5.3): a rectangular cut of the
     * knock has hard edges that smear energy across many FFT bins (spectral
     * leakage) and can bury the resonant peak. The Hann taper forces the
     * segment smoothly to zero at both ends, concentrating energy in the true
     * peak's bin and its immediate neighbours -- exactly the clean, roughly
     * parabolic (in log-magnitude) peak that the interpolation below assumes.
     *
     * We use the periodic form (divide by n, not n-1), the correct convention
     * for FFT-based spectral analysis.
     */
    fun hannWindow(n: Int): DoubleArray {
        if (n <= 1) return DoubleArray(maxOf(n, 0)) { 1.0 }
        return DoubleArray(n) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / n) }
    }

    /** Window -> zero-pad -> FFT -> magnitude of the first N/2+1 bins.
     *  Only the lower half is meaningful because the input is real. */
    fun magnitudeSpectrum(segment: DoubleArray): DoubleArray {
        val n = segment.size
        if (n == 0) return DoubleArray(0)
        val win = hannWindow(n)
        val nfft = Fft.nextPow2(n)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft) // zero
        for (i in 0 until n) re[i] = segment[i] * win[i]
        Fft.transform(re, im)
        val half = nfft / 2 + 1
        return DoubleArray(half) { i -> Math.hypot(re[i], im[i]) }
    }

    // ---------------------------------------------------------------- //
    // 5.4  Sub-bin peak refinement (parabolic / quadratic interpolation)
    // ---------------------------------------------------------------- //
    /**
     * Refine an integer peak bin [k] to sub-bin resolution using standard
     * log-magnitude parabolic interpolation (Julius O. Smith, "Spectral Audio
     * Signal Processing").
     *
     * We fit a parabola through the peak bin and its two neighbours in the
     * LOG-magnitude domain, because the Hann window's main lobe is close to a
     * parabola on a dB scale, so the vertex lands very near the true continuous
     * peak. Reading the raw FFT bin instead would quantise frequency to the bin
     * spacing (~5-10 Hz here), which is too coarse for calibration.
     *
     * Returns (refinedBinIndex, interpolatedPeakMagnitude). The fractional
     * offset delta is constrained to roughly [-0.5, 0.5].
     */
    fun parabolicInterpolation(mags: DoubleArray, k: Int): Pair<Double, Double> {
        if (k <= 0 || k >= mags.size - 1) return Pair(k.toDouble(), mags[k])
        val eps = 1e-12
        val alpha = ln(mags[k - 1] + eps)
        val beta = ln(mags[k] + eps)
        val gamma = ln(mags[k + 1] + eps)
        val denom = alpha - 2.0 * beta + gamma
        if (abs(denom) < 1e-18) return Pair(k.toDouble(), mags[k])
        val delta = 0.5 * (alpha - gamma) / denom
        val peakLog = beta - 0.25 * (alpha - gamma) * delta
        return Pair(k + delta, exp(peakLog))
    }

    fun binToHz(bin: Double, nfft: Int): Double = bin * sampleRate / nfft

    /** Median magnitude across the searched spectrum = robust noise floor.
     *  Median (not mean) so a couple of strong resonant peaks don't inflate it. */
    private fun localNoiseFloor(mags: DoubleArray): Double {
        if (mags.isEmpty()) return 0.0
        val sorted = mags.sortedArray()
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2.0
    }

    /**
     * Find local maxima whose refined frequency falls in [[fLo], [fHi]] and that
     * rise at least [minProminence]x above the local noise floor (spec section 5.4).
     * Restricting to a plausible tank-resonance band avoids locking onto mains
     * hum, HVAC rumble, or high-frequency clatter. Returned strongest-first.
     */
    fun findPeaksInBand(
        mags: DoubleArray,
        nfft: Int,
        fLo: Double,
        fHi: Double,
        minProminence: Double = 3.0,
    ): List<Peak> {
        if (mags.size < 3) return emptyList()
        val floor = localNoiseFloor(mags) + 1e-12
        val loBin = maxOf(1, floor(fLo * nfft / sampleRate).toInt())
        val hiBin = minOf(mags.size - 2, ceil(fHi * nfft / sampleRate).toInt())
        val peaks = ArrayList<Peak>()
        for (k in loBin..hiBin) {
            if (mags[k] > mags[k - 1] && mags[k] >= mags[k + 1]) {
                val (refBin, refMag) = parabolicInterpolation(mags, k)
                val freq = binToHz(refBin, nfft)
                if (freq < fLo || freq > fHi) continue
                val prominence = refMag / floor
                if (prominence >= minProminence) peaks.add(Peak(freq, refMag, prominence))
            }
        }
        peaks.sortByDescending { it.magnitude }
        return peaks
    }

    /**
     * Drop peaks that sit near an integer multiple (2x..) of a STRONGER lower
     * peak -- those are overtones, not independent resonances (spec section 5.4).
     * Walk strongest-first; discard a peak within [tol] (fractional) of
     * n*fBase for any already-kept, stronger, lower peak. tol=0.06 (+/-6%) is
     * loose enough for slightly inharmonic real overtones but tight enough not
     * to eat genuinely separate modes.
     */
    fun rejectHarmonics(peaks: List<Peak>, tol: Double = 0.06): List<Peak> {
        val kept = ArrayList<Peak>()
        for (p in peaks) { // already strongest-first
            var isHarmonic = false
            for (base in kept) {
                if (base.freqHz <= 0.0 || base.magnitude <= p.magnitude) continue
                if (p.freqHz <= base.freqHz) continue
                val ratio = p.freqHz / base.freqHz
                val nearest = ratio.roundToInt()
                if (nearest >= 2 && abs(ratio - nearest) <= tol) {
                    isHarmonic = true
                    break
                }
            }
            if (!isHarmonic) kept.add(p)
        }
        return kept
    }

    /** Full single-tap pipeline (spec sections 5.3-5.4). */
    fun analyzeTap(
        segment: DoubleArray,
        fLo: Double = 60.0,
        fHi: Double = 3000.0,
        minProminence: Double = 3.0,
    ): TapResult {
        val mags = magnitudeSpectrum(segment)
        if (mags.isEmpty()) return TapResult(null, 0.0, emptyList())
        val nfft = (mags.size - 1) * 2
        val peaks = findPeaksInBand(mags, nfft, fLo, fHi, minProminence)
        if (peaks.isEmpty()) return TapResult(null, 0.0, emptyList())
        val survivors = rejectHarmonics(peaks).sortedByDescending { it.magnitude }
        if (survivors.isEmpty()) return TapResult(null, 0.0, peaks)
        val best = survivors.first()
        return TapResult(best.freqHz, best.prominence, survivors)
    }

    // ---------------------------------------------------------------- //
    // 5.5  Multi-tap robustness
    // ---------------------------------------------------------------- //
    /**
     * Combine N taps (spec uses 3) into one reading via the MEDIAN frequency.
     * Median is more robust to a single bad tap than the mean -- one stray
     * knock that locks onto a harmonic won't drag the result. We also compute
     * tap-to-tap spread; if it exceeds [maxSpreadFrac] of the median (default
     * 5%) we flag the reading as not-ok so the UI can ask the user to re-tap
     * rather than returning a bad number.
     */
    fun combineTaps(taps: List<TapResult>, maxSpreadFrac: Double = 0.05): ReadingResult {
        val freqs = taps.mapNotNull { it.fundamentalHz }
        if (freqs.isEmpty()) return ReadingResult(null, 0.0, 0.0, emptyList(), false)
        val sorted = freqs.sorted()
        val med = median(sorted)
        val spread = sorted.last() - sorted.first()
        val proms = taps.filter { it.fundamentalHz != null }.map { it.prominence }.sorted()
        val medProm = if (proms.isEmpty()) 0.0 else median(proms)
        val ok = when {
            freqs.size < 2 -> false
            med > 0 && spread / med > maxSpreadFrac -> false
            else -> true
        }
        return ReadingResult(med, spread, medProm, freqs, ok)
    }

    private fun median(sorted: List<Double>): Double {
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2.0
    }
}
