package com.tapgauge.calibration

import kotlin.math.abs

/**
 * A single stored calibration/measurement sample (spec section 6.1).
 *
 * [knownLevelPercent] is null for ordinary measurement taps (the "silent"
 * history of spec section 6.3) and populated only when the user logs an explicit
 * anchor (Full / Empty / weigh-in / gauge-check / known-volume).
 */
data class CalibrationPoint(
    val timestamp: Long,
    val frequencyHz: Double,
    val knownLevelPercent: Double? = null,
) {
    val isAnchor: Boolean get() = knownLevelPercent != null
}

/** Displayed confidence tier (spec section 3.3 / 5.6). */
enum class Confidence { UNCALIBRATED, ROUGH, MEDIUM, HIGH }

data class LevelEstimate(
    val percent: Double?,
    val confidence: Confidence,
    /** true if the reading fell outside the calibrated frequency range. */
    val extrapolated: Boolean,
    val note: String = "",
)

data class MonotonicityCheck(val ok: Boolean, val reason: String = "")
data class DriftCheck(val drifted: Boolean, val deltaHz: Double = 0.0, val reason: String = "")

/**
 * Turns a measured frequency into a fill percentage for ONE tank via monotone
 * linear interpolation between anchor points. Direct port of the Python
 * reference validated in tests (interpolation, monotonicity enforcement,
 * extrapolation clamping, confidence, drift detection).
 *
 * v1 curve = LINEAR INTERPOLATION (spec section 6.2). It is easy to explain to the
 * user, robust to irregular tank geometry without a physics model, and was
 * shown in simulation to track true levels within ~2.4%. Do NOT reach for
 * splines / isotonic / a physics model until the linear version is shown
 * insufficient against real recordings (spec section 6.2 / 11).
 *
 * On direction: physics (spec section 2) says resonant frequency FALLS as a tank
 * empties, so higher frequency = more full. We do not hard-code that sign; we
 * detect the monotonic direction from the anchors themselves, so calibration
 * follows the tank's actual measured behaviour.
 */
class CalibrationEngine(
    private val fullDriftToleranceFrac: Double = 0.05,
) {
    private val anchorList = mutableListOf<CalibrationPoint>()

    /** Anchors sorted by frequency ascending (the x-axis of the curve). */
    fun anchors(): List<CalibrationPoint> = anchorList.sortedBy { it.frequencyHz }

    fun anchorCount(): Int = anchorList.size

    /** Replace all anchors (e.g. when loading a tank from the repository). */
    fun setAnchors(points: List<CalibrationPoint>) {
        anchorList.clear()
        anchorList.addAll(points.filter { it.isAnchor })
    }

    private fun direction(sorted: List<CalibrationPoint>): Int {
        if (sorted.size < 2) return 1
        val lo = sorted.first(); val hi = sorted.last()
        return if ((hi.knownLevelPercent!! - lo.knownLevelPercent!!) >= 0) 1 else -1
    }

    /**
     * Would adding [candidate] keep the (frequency -> percent) relation
     * monotonic (spec section 6.2)? With <2 existing anchors any point is accepted.
     * Otherwise percent must move consistently in one direction as frequency
     * increases, matching the direction the existing anchors already establish.
     */
    fun checkMonotonic(candidate: CalibrationPoint): MonotonicityCheck {
        if (candidate.knownLevelPercent == null) {
            return MonotonicityCheck(false, "candidate is not an anchor")
        }
        val existing = anchors()
        if (existing.size < 2) return MonotonicityCheck(true)

        val dir = direction(existing)
        val merged = (existing + candidate).sortedBy { it.frequencyHz }
        for (i in 0 until merged.size - 1) {
            val a = merged[i]; val b = merged[i + 1]
            if (abs(a.frequencyHz - b.frequencyHz) < 1e-9) {
                if (abs(a.knownLevelPercent!! - b.knownLevelPercent!!) > 1e-6) {
                    return MonotonicityCheck(
                        false,
                        "two anchors at ~same frequency but different % " +
                            "(likely a bad tap or moved tank)",
                    )
                }
                continue
            }
            val step = b.knownLevelPercent!! - a.knownLevelPercent!!
            if (dir > 0 && step < -1e-6) {
                return MonotonicityCheck(
                    false, "new point breaks increasing freq->% trend (bad tap or tank moved?)",
                )
            }
            if (dir < 0 && step > 1e-6) {
                return MonotonicityCheck(
                    false, "new point breaks decreasing freq->% trend (bad tap or tank moved?)",
                )
            }
        }
        return MonotonicityCheck(true)
    }

    /**
     * Compare a fresh "just filled" (100%) tap against the existing 100% anchor
     * (spec section 6.5). If they disagree by more than the tolerance, flag it so the
     * UI can ask "temperature change, or did the tank move?" rather than
     * silently blending the new point in.
     */
    fun checkDrift(newFullFreq: Double): DriftCheck {
        val fulls = anchorList.filter {
            it.knownLevelPercent != null && abs(it.knownLevelPercent - 100.0) < 1e-6
        }
        if (fulls.isEmpty()) return DriftCheck(false)
        val prev = fulls.maxBy { it.timestamp }
        if (prev.frequencyHz <= 0) return DriftCheck(false)
        val delta = newFullFreq - prev.frequencyHz
        val frac = abs(delta) / prev.frequencyHz
        return if (frac > fullDriftToleranceFrac) {
            DriftCheck(true, delta,
                "full-tank frequency shifted by ${"%+.1f".format(delta)} Hz " +
                    "(${"%.1f".format(frac * 100)}%) vs previous calibration")
        } else {
            DriftCheck(false, delta)
        }
    }

    /**
     * Add an anchor. Returns the monotonicity check; when [enforceMonotonic] is
     * true and the check fails, the point is NOT added and the caller should
     * surface the reason to the user.
     */
    fun addAnchor(point: CalibrationPoint, enforceMonotonic: Boolean = true): MonotonicityCheck {
        val check = checkMonotonic(point)
        if (enforceMonotonic && !check.ok) return check
        anchorList.add(point)
        return check
    }

    /**
     * Map a measured frequency to a fill percentage via piecewise-linear
     * interpolation between the two bracketing anchors. Outside the anchor range
     * we CLAMP to the nearest endpoint and mark the result [extrapolated]
     * (lower confidence, spec section 5.6), since we have no evidence past the ends.
     */
    fun estimateLevel(frequencyHz: Double): LevelEstimate {
        val a = anchors()
        val conf = confidence()
        if (a.isEmpty()) return LevelEstimate(null, conf, false, "no calibration points yet")
        if (a.size == 1) {
            val only = a[0]
            return if (abs(frequencyHz - only.frequencyHz) < 1e-6) {
                LevelEstimate(only.knownLevelPercent, conf, false, "single anchor exact match")
            } else {
                LevelEstimate(null, conf, true, "only one calibration point; need at least two")
            }
        }
        val fLo = a.first().frequencyHz
        val fHi = a.last().frequencyHz
        if (frequencyHz <= fLo) {
            return LevelEstimate(a.first().knownLevelPercent, conf,
                frequencyHz < fLo - 1e-9, "below calibrated range (clamped)")
        }
        if (frequencyHz >= fHi) {
            return LevelEstimate(a.last().knownLevelPercent, conf,
                frequencyHz > fHi + 1e-9, "above calibrated range (clamped)")
        }
        for (i in 0 until a.size - 1) {
            val lo = a[i]; val hi = a[i + 1]
            if (frequencyHz in lo.frequencyHz..hi.frequencyHz) {
                val span = hi.frequencyHz - lo.frequencyHz
                val pct = if (span < 1e-9) {
                    (lo.knownLevelPercent!! + hi.knownLevelPercent!!) / 2.0
                } else {
                    val t = (frequencyHz - lo.frequencyHz) / span
                    lo.knownLevelPercent!! + t * (hi.knownLevelPercent!! - lo.knownLevelPercent!!)
                }
                return LevelEstimate(pct.coerceIn(0.0, 100.0), conf, false, "interpolated")
            }
        }
        return LevelEstimate(null, conf, true, "unbracketed")
    }

    fun confidence(): Confidence = when (anchorCount()) {
        0 -> Confidence.UNCALIBRATED
        1 -> Confidence.UNCALIBRATED
        2 -> Confidence.ROUGH
        3 -> Confidence.MEDIUM
        else -> Confidence.HIGH
    }

    /**
     * After re-fitting, check that recent UNLABELED taps don't map to wildly
     * implausible levels (spec section 6.3). Returns human-readable warnings only;
     * it does NOT auto-correct (unsupervised recalibration is a stretch goal,
     * not v1). Heuristic: two taps < 1 h apart can't jump more than
     * [maxJumpPercent]; a bigger jump suggests the new curve is off.
     */
    fun sanityCheckAgainstHistory(
        history: List<CalibrationPoint>,
        maxJumpPercent: Double = 40.0,
    ): List<String> {
        val warnings = mutableListOf<String>()
        val unlabeled = history.filter { it.knownLevelPercent == null }.sortedBy { it.timestamp }
        var prevPct: Double? = null
        var prevTs: Long? = null
        for (h in unlabeled) {
            val est = estimateLevel(h.frequencyHz)
            val pct = est.percent ?: continue
            if (prevPct != null && prevTs != null) {
                val dt = h.timestamp - prevTs!!
                if (dt in 0 until 3_600_000L && abs(pct - prevPct!!) > maxJumpPercent) {
                    warnings.add(
                        "implausible ${"%.0f".format(abs(pct - prevPct!!))}% jump between two " +
                            "taps ${dt / 1000}s apart -- new curve may be off",
                    )
                }
            }
            prevPct = pct; prevTs = h.timestamp
        }
        return warnings
    }

    companion object {
        /**
         * Ingestion helper for Driveway Calibration (RV re-scope section 3.3): convert a
         * cumulative added-volume + tank capacity into a known-level percent, which
         * is then handed to the EXISTING [addAnchor]/fit path unchanged. This is new
         * plumbing, NOT new curve math -- the monotonic linear-interp fit is untouched.
         * Returns null if capacity is unusable.
         */
        fun percentFromCumulativeGallons(cumulativeGallons: Double, capacityGallons: Double): Double? {
            if (capacityGallons <= 0.0) return null
            return (cumulativeGallons / capacityGallons * 100.0).coerceIn(0.0, 100.0)
        }

        /**
         * Days-until-empty from recent (timestampSeconds, percent) points
         * (spec section 3.4). Honest linear least-squares slope; returns null if the
         * tank isn't clearly depleting or there isn't enough data. We avoid
         * anything fancier: this is a convenience number and over-modelling a
         * noisy burn rate would imply precision we haven't earned (spec section 11).
         */
        fun daysUntilEmpty(pts: List<Pair<Double, Double>>): Double? {
            if (pts.size < 2) return null
            val sorted = pts.sortedBy { it.first }
            val n = sorted.size
            val meanT = sorted.sumOf { it.first } / n
            val meanP = sorted.sumOf { it.second } / n
            var num = 0.0; var den = 0.0
            for (p in sorted) {
                num += (p.first - meanT) * (p.second - meanP)
                den += (p.first - meanT) * (p.first - meanT)
            }
            if (abs(den) < 1e-9) return null
            val slope = num / den // percent per second
            if (slope >= -1e-12) return null // flat or refilling
            val current = sorted.last().second
            return (current / -slope) / 86400.0
        }
    }
}
