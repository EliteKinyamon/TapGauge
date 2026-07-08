package com.tapgauge.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest {
    private fun naiveDft(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0; var si = 0.0
            for (t in 0 until n) {
                val ang = -2.0 * Math.PI * k * t / n
                sr += x[t] * cos(ang); si += x[t] * sin(ang)
            }
            re[k] = sr; im[k] = si
        }
        return re to im
    }

    @Test fun `fft matches naive dft`() {
        val rng = Random(1)
        val x = DoubleArray(64) { rng.nextDouble(-1.0, 1.0) }
        val re = x.copyOf(); val im = DoubleArray(64)
        Fft.transform(re, im)
        val (nr, ni) = naiveDft(x)
        for (k in 0 until 64) {
            assertEquals(nr[k], re[k], 1e-6)
            assertEquals(ni[k], im[k], 1e-6)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non power of two`() {
        Fft.transform(DoubleArray(3), DoubleArray(3))
    }

    @Test fun `next pow2`() {
        assertEquals(4096, Fft.nextPow2(3528))
        assertEquals(4096, Fft.nextPow2(4096))
        assertEquals(8192, Fft.nextPow2(4097))
    }
}
