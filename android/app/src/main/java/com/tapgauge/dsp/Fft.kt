package com.tapgauge.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-rolled, dependency-free radix-2 Cooley-Tukey FFT (spec section 5.3).
 *
 * This is a direct port of the reference implementation that was validated in
 * Python against both a naive DFT and numpy to floating-point precision. It
 * operates in place on separate real/imaginary [DoubleArray]s (rather than a
 * Complex object array) to avoid per-element allocation on the audio hot path.
 *
 * The transform has two stages:
 *   1. Bit-reversal permutation of the input (decimation in time).
 *   2. log2(N) butterfly stages, each combining pairs with twiddle factors.
 *
 * Input length MUST be a power of two.
 */
object Fft {

    /** Smallest power of two >= [n]. FFT length must be a power of two; padding
     *  to a larger power of two also yields finer frequency-bin spacing. */
    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /**
     * In-place radix-2 FFT. [re] and [im] must have the same length, which must
     * be a power of two. On return they hold the transformed spectrum.
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(im.size == n) { "re/im length mismatch" }
        if (n == 0) return
        require(n and (n - 1) == 0) { "FFT length must be a power of two, got $n" }

        // --- 1. bit-reversal permutation ---
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        // --- 2. butterflies ---
        var length = 2
        while (length <= n) {
            // principal twiddle for this stage: exp(-2*pi*i / length)
            val ang = -2.0 * Math.PI / length
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)
            val half = length shr 1
            var start = 0
            while (start < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val iEven = start + k
                    val iOdd = start + k + half
                    // v = a[odd] * w
                    val vRe = re[iOdd] * wRe - im[iOdd] * wIm
                    val vIm = re[iOdd] * wIm + im[iOdd] * wRe
                    val uRe = re[iEven]
                    val uIm = im[iEven]
                    re[iEven] = uRe + vRe
                    im[iEven] = uIm + vIm
                    re[iOdd] = uRe - vRe
                    im[iOdd] = uIm - vIm
                    // advance twiddle: w *= wlen
                    val nextWRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextWRe
                }
                start += length
            }
            length = length shl 1
        }
    }
}
