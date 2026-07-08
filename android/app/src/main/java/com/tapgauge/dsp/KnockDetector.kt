package com.tapgauge.dsp

import kotlin.math.sqrt

/**
 * Onset ("knock") detector (spec section 5.2).
 *
 * Distinguishes a sharp knock transient from gradual ambient sound (talking,
 * footsteps, wind) using two conditions that must BOTH hold:
 *   1. Short-time energy jumps above a running estimate of the ambient noise
 *      floor by a multiplicative [thresholdRatio].
 *   2. That jump is a genuine sharp ATTACK -- the energy rose sharply relative
 *      to the immediately preceding frames, not a slow swell.
 *
 * The noise floor is tracked with a slow exponential moving average over a
 * trailing window (~200-500 ms), and it is only updated while NOT in an onset,
 * so a loud knock doesn't poison the floor estimate.
 *
 * This class is frame-driven: feed it fixed-size PCM frames (e.g. ~20 ms) and
 * it tells you when a valid onset begins. It is deliberately allocation-free
 * per frame and has no Android dependencies, so it is unit-testable on the JVM.
 */
class KnockDetector(
    private val sampleRate: Int = 44100,
    /** How many times above the noise floor the frame energy must jump. */
    private val thresholdRatio: Double = 6.0,
    /** Minimum ratio of current frame energy to the trailing short average,
     *  enforcing a sharp attack rather than a gradual rise. */
    private val attackRatio: Double = 3.0,
    /** EMA smoothing for the ambient noise-floor estimate (0..1, small=slow). */
    private val floorAlpha: Double = 0.05,
    /** Refractory period after an onset, so one knock isn't detected twice. */
    private val refractoryFrames: Int = 6,
) {
    private var noiseFloor = 0.0
    private var shortAvg = 0.0        // fast-moving trailing energy (attack ref)
    private var refractory = 0
    private var initialized = false

    fun reset() {
        noiseFloor = 0.0
        shortAvg = 0.0
        refractory = 0
        initialized = false
    }

    /** RMS energy of a frame. */
    private fun frameEnergy(frame: DoubleArray): Double {
        if (frame.isEmpty()) return 0.0
        var acc = 0.0
        for (x in frame) acc += x * x
        return sqrt(acc / frame.size)
    }

    /**
     * Feed one frame. Returns true exactly on the frame where a valid onset
     * begins. The caller should then collect the following analysis window
     * (spec section 5.2 recommends ~80-150 ms) and hand it to [SpectralAnalyzer].
     */
    fun accept(frame: DoubleArray): Boolean {
        val e = frameEnergy(frame)

        if (!initialized) {
            noiseFloor = e
            shortAvg = e
            initialized = true
            return false
        }

        if (refractory > 0) {
            refractory--
            // Keep the fast average current but don't touch the slow floor.
            shortAvg = 0.5 * shortAvg + 0.5 * e
            return false
        }

        val floor = noiseFloor + 1e-9
        val aboveFloor = e > floor * thresholdRatio
        val sharpAttack = e > (shortAvg + 1e-9) * attackRatio

        val onset = aboveFloor && sharpAttack
        if (onset) {
            refractory = refractoryFrames
            // Do NOT fold this loud frame into the floor.
            shortAvg = 0.5 * shortAvg + 0.5 * e
            return true
        }

        // Quiet frame: update both the fast average and the slow noise floor.
        shortAvg = 0.7 * shortAvg + 0.3 * e
        noiseFloor = (1 - floorAlpha) * noiseFloor + floorAlpha * e
        return false
    }

    /** Current ambient noise-floor estimate (exposed for the diagnostics screen). */
    fun currentNoiseFloor(): Double = noiseFloor
}
