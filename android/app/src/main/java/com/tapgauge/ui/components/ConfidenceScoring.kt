package com.tapgauge.ui.components

import com.tapgauge.dsp.ReadingResult

/** Displayed per-reading confidence (spec section 5.6), distinct from the tank's
 *  overall calibration confidence. */
enum class ReadingConfidence { LOW, MEDIUM, HIGH }

/**
 * Combine the three signals from spec section 5.6 into one badge:
 *   1. peak prominence (how far the peak rose above the noise floor),
 *   2. tap-to-tap consistency (already folded into [ReadingResult.ok] + spread),
 *   3. whether the reading required extrapolating beyond the calibrated range.
 */
fun scoreReading(reading: ReadingResult, extrapolated: Boolean): ReadingConfidence {
    if (!reading.ok) return ReadingConfidence.LOW
    val f = reading.frequencyHz ?: return ReadingConfidence.LOW
    val spreadFrac = if (f > 0) reading.spreadHz / f else 1.0
    val prom = reading.prominence

    // Base score from prominence + consistency.
    var score = 0
    if (prom >= 8.0) score += 2 else if (prom >= 4.0) score += 1
    if (spreadFrac <= 0.01) score += 2 else if (spreadFrac <= 0.03) score += 1

    if (extrapolated) score -= 2 // extrapolation should visibly lower confidence

    return when {
        score >= 3 -> ReadingConfidence.HIGH
        score >= 1 -> ReadingConfidence.MEDIUM
        else -> ReadingConfidence.LOW
    }
}
