package com.tapgauge.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic knock generators, mirroring the validated Python test harness. */
object TestSignals {
    const val SR = 44100

    fun decayingSinusoid(
        freq: Double, sr: Int, durS: Double,
        decayTauS: Double = 0.03, amp: Double = 1.0, phase: Double = 0.0,
    ): DoubleArray {
        val n = (sr * durS).toInt()
        return DoubleArray(n) { i ->
            val t = i.toDouble() / sr
            amp * exp(-t / decayTauS) * sin(2 * PI * freq * t + phase)
        }
    }

    fun addNoise(sig: DoubleArray, snrDb: Double, rng: Random): DoubleArray {
        var power = 0.0
        for (x in sig) power += x * x
        power /= sig.size.coerceAtLeast(1)
        if (power <= 0) return sig.copyOf()
        val noisePower = power / Math.pow(10.0, snrDb / 10.0)
        val sigma = Math.sqrt(noisePower)
        return DoubleArray(sig.size) { sig[it] + rng.nextGaussian(0.0, sigma) }
    }

    fun knock(
        freq: Double, sr: Int = SR, durS: Double = 0.10, snrDb: Double = 20.0,
        seed: Int = 0, overtones: List<Pair<Double, Double>> = emptyList(),
        decayTauS: Double = 0.03,
    ): DoubleArray {
        val rng = Random(seed)
        val sig = decayingSinusoid(freq, sr, durS, decayTauS, 1.0)
        for ((mult, a) in overtones) {
            val ov = decayingSinusoid(freq * mult, sr, durS, decayTauS * 0.7, a,
                rng.nextDouble(0.0, 2 * PI))
            for (i in sig.indices) sig[i] += ov[i]
        }
        val nAttack = (sr * 0.002).toInt()
        for (i in 0 until minOf(nAttack, sig.size)) sig[i] += rng.nextDouble(-0.8, 0.8)
        return addNoise(sig, snrDb, rng)
    }
}

/** Box-Muller gaussian for kotlin.random.Random (no stdlib gaussian). */
fun Random.nextGaussian(mean: Double, sd: Double): Double {
    var u1: Double
    do { u1 = nextDouble() } while (u1 <= 1e-12)
    val u2 = nextDouble()
    val z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2)
    return mean + sd * z
}
