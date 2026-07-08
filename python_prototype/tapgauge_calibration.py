"""
TapGauge calibration engine (reference implementation, pure Python / stdlib).

Validation prototype for the Android CalibrationEngine (spec section 6). Mirrors
the Kotlin port (app/src/main/java/com/tapgauge/calibration/) one-to-one.

Design decisions locked by the spec:
  * 6.2  v1 curve = LINEAR INTERPOLATION between (frequency, %) anchor points.
         Simple, explainable, robust to weird tank geometry without a physics
         model. Do NOT reach for splines/isotonic/physics until the linear
         version is shown to be insufficient against real taps.
  * 6.2  MONOTONICITY is enforced. Resonant frequency should move consistently
         in one direction as level drops. A new anchor that breaks monotonicity
         is almost always a bad tap or a moved tank, so we flag it.
  * 5.6/6 CONFIDENCE grows with the number of anchor points and drops when a
         reading requires extrapolating beyond the calibrated range.
  * 6.5  DRIFT: a fresh "just filled" whose frequency disagrees with the
         historical 100% anchor is surfaced to the user, not silently blended.

Direction note: physics (section 2) says the air column lengthens as liquid
drops, so resonant frequency FALLS as the tank empties -> higher freq = more
full. We do not hard-code that sign; we detect the monotonic direction from the
anchors themselves, so calibration follows the tank's measured behaviour.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Optional


@dataclass
class CalibrationPoint:
    """A single stored sample (spec section 6.1).

    known_level_percent is None for ordinary measurement taps (the "silent"
    history of section 6.3) and populated only when the user logs an explicit
    anchor (Full / Empty / weigh-in / gauge-check / known-volume).
    """
    timestamp: float
    frequency_hz: float
    known_level_percent: Optional[float] = None  # 0..100, or None

    @property
    def is_anchor(self) -> bool:
        return self.known_level_percent is not None


class Confidence(Enum):
    UNCALIBRATED = "uncalibrated"   # 0-1 anchors
    ROUGH = "rough"                 # 2 anchors (e.g. just Full + Empty)
    MEDIUM = "medium"               # 3 anchors
    HIGH = "high"                   # 4+ anchors


@dataclass
class LevelEstimate:
    percent: Optional[float]
    confidence: Confidence
    extrapolated: bool              # reading fell outside calibrated freq range
    note: str = ""


@dataclass
class MonotonicityCheck:
    ok: bool
    reason: str = ""


@dataclass
class DriftCheck:
    drifted: bool
    delta_hz: float = 0.0
    reason: str = ""


class CalibrationEngine:
    """Holds the anchor points for ONE tank and turns a measured frequency
    into a fill percentage via monotone linear interpolation."""

    def __init__(self, full_drift_tolerance_frac: float = 0.05):
        self._anchors: list[CalibrationPoint] = []
        self._full_drift_tolerance_frac = full_drift_tolerance_frac

    # ------------------------------------------------------------------ #
    def anchors(self) -> list[CalibrationPoint]:
        """Anchors sorted by frequency ascending (the x-axis of the curve)."""
        return sorted(self._anchors, key=lambda p: p.frequency_hz)

    def anchor_count(self) -> int:
        return len(self._anchors)

    def _direction(self, anchors: list[CalibrationPoint]) -> int:
        """+1 if percent increases with frequency, -1 if it decreases."""
        if len(anchors) < 2:
            return 1
        lo, hi = anchors[0], anchors[-1]
        return 1 if (hi.known_level_percent - lo.known_level_percent) >= 0 else -1

    def check_monotonic(self, candidate: CalibrationPoint) -> MonotonicityCheck:
        """Would adding `candidate` keep the (frequency -> percent) relation
        monotonic? (section 6.2). With <2 existing anchors any point is
        acceptable; otherwise percent must move consistently in one direction
        as frequency increases, matching the established direction."""
        if candidate.known_level_percent is None:
            return MonotonicityCheck(False, "candidate is not an anchor")

        existing = self.anchors()
        if len(existing) < 2:
            return MonotonicityCheck(True)

        direction = self._direction(existing)
        merged = sorted(existing + [candidate], key=lambda p: p.frequency_hz)

        for a, b in zip(merged, merged[1:]):
            if abs(a.frequency_hz - b.frequency_hz) < 1e-9:
                if abs(a.known_level_percent - b.known_level_percent) > 1e-6:
                    return MonotonicityCheck(
                        False,
                        "two anchors at ~same frequency but different % "
                        "(likely a bad tap or moved tank)",
                    )
                continue
            step = b.known_level_percent - a.known_level_percent
            if direction > 0 and step < -1e-6:
                return MonotonicityCheck(
                    False, "new point breaks increasing freq->% trend (bad tap or tank moved?)")
            if direction < 0 and step > 1e-6:
                return MonotonicityCheck(
                    False, "new point breaks decreasing freq->% trend (bad tap or tank moved?)")
        return MonotonicityCheck(True)

    def check_drift(self, new_full_freq: float) -> DriftCheck:
        """section 6.5: compare a fresh 'just filled' (100%) tap against the
        existing 100% anchor. If they disagree by more than the tolerance, flag
        it so the UI can ask 'temperature change, or did the tank move?'"""
        full_anchors = [a for a in self._anchors
                        if a.known_level_percent is not None
                        and abs(a.known_level_percent - 100.0) < 1e-6]
        if not full_anchors:
            return DriftCheck(False)
        prev = max(full_anchors, key=lambda a: a.timestamp)
        if prev.frequency_hz <= 0:
            return DriftCheck(False)
        delta = new_full_freq - prev.frequency_hz
        frac = abs(delta) / prev.frequency_hz
        if frac > self._full_drift_tolerance_frac:
            return DriftCheck(
                True, delta,
                f"full-tank frequency shifted by {delta:+.1f} Hz "
                f"({frac*100:.1f}%) vs previous calibration")
        return DriftCheck(False, delta)

    def add_anchor(self, point: CalibrationPoint, enforce_monotonic: bool = True) -> MonotonicityCheck:
        """Add an anchor point. Returns the monotonicity check; when
        enforce_monotonic is True and the check fails, the point is NOT added."""
        check = self.check_monotonic(point)
        if enforce_monotonic and not check.ok:
            return check
        self._anchors.append(point)
        return check

    def set_anchors(self, points: list[CalibrationPoint]) -> None:
        self._anchors = [p for p in points if p.is_anchor]

    # ------------------------------------------------------------------ #
    # 6.2  The curve: monotone linear interpolation
    # ------------------------------------------------------------------ #
    def estimate_level(self, frequency_hz: float) -> LevelEstimate:
        """Map a measured frequency to a fill percentage. 0 anchors ->
        uncalibrated; 1 anchor -> only exact match; >=2 -> piecewise-linear
        interpolation, clamped+flagged outside the anchor range (section 5.6)."""
        anchors = self.anchors()
        conf = self.confidence()

        if len(anchors) == 0:
            return LevelEstimate(None, conf, False, "no calibration points yet")
        if len(anchors) == 1:
            only = anchors[0]
            if abs(frequency_hz - only.frequency_hz) < 1e-6:
                return LevelEstimate(only.known_level_percent, conf, False, "single anchor exact match")
            return LevelEstimate(None, conf, True, "only one calibration point; need at least two")

        f_lo, f_hi = anchors[0].frequency_hz, anchors[-1].frequency_hz

        if frequency_hz <= f_lo:
            return LevelEstimate(anchors[0].known_level_percent, conf,
                                 frequency_hz < f_lo - 1e-9, "below calibrated range (clamped)")
        if frequency_hz >= f_hi:
            return LevelEstimate(anchors[-1].known_level_percent, conf,
                                 frequency_hz > f_hi + 1e-9, "above calibrated range (clamped)")

        for a, b in zip(anchors, anchors[1:]):
            if a.frequency_hz <= frequency_hz <= b.frequency_hz:
                span = b.frequency_hz - a.frequency_hz
                if span < 1e-9:
                    pct = (a.known_level_percent + b.known_level_percent) / 2.0
                else:
                    t = (frequency_hz - a.frequency_hz) / span
                    pct = a.known_level_percent + t * (b.known_level_percent - a.known_level_percent)
                pct = max(0.0, min(100.0, pct))
                return LevelEstimate(pct, conf, False, "interpolated")

        return LevelEstimate(None, conf, True, "unbracketed")

    def confidence(self) -> Confidence:
        n = self.anchor_count()
        if n >= 4:
            return Confidence.HIGH
        if n == 3:
            return Confidence.MEDIUM
        if n == 2:
            return Confidence.ROUGH
        return Confidence.UNCALIBRATED

    def sanity_check_against_history(
        self,
        history: list[CalibrationPoint],
        max_jump_percent: float = 40.0,
    ) -> list[str]:
        """After re-fitting, check that recent UNLABELED taps don't map to
        wildly implausible levels (section 6.3). Returns warnings only; it does
        NOT auto-correct (unsupervised recalibration is a stretch goal)."""
        warnings: list[str] = []
        unlabeled = sorted(
            [h for h in history if h.known_level_percent is None],
            key=lambda h: h.timestamp)
        prev_pct: Optional[float] = None
        prev_ts: Optional[float] = None
        for h in unlabeled:
            est = self.estimate_level(h.frequency_hz)
            if est.percent is None:
                continue
            if prev_pct is not None and prev_ts is not None:
                dt = h.timestamp - prev_ts
                if dt < 3600 and abs(est.percent - prev_pct) > max_jump_percent:
                    warnings.append(
                        f"implausible {abs(est.percent - prev_pct):.0f}% jump "
                        f"between two taps {dt:.0f}s apart -- new curve may be off")
            prev_pct, prev_ts = est.percent, h.timestamp
        return warnings


def days_until_empty(history_pct_time: list[tuple[float, float]]) -> Optional[float]:
    """Estimate days until empty from recent (timestamp_seconds, percent) points
    (spec section 3.4). Honest linear least-squares slope; returns None if the tank
    isn't clearly depleting or there isn't enough data. Deliberately simple --
    over-modelling a noisy burn rate would imply precision we haven't earned."""
    pts = sorted(history_pct_time, key=lambda x: x[0])
    if len(pts) < 2:
        return None
    n = len(pts)
    mean_t = sum(p[0] for p in pts) / n
    mean_p = sum(p[1] for p in pts) / n
    num = sum((p[0] - mean_t) * (p[1] - mean_p) for p in pts)
    den = sum((p[0] - mean_t) ** 2 for p in pts)
    if abs(den) < 1e-9:
        return None
    slope_pct_per_sec = num / den
    if slope_pct_per_sec >= -1e-12:
        return None
    current_pct = pts[-1][1]
    return (current_pct / (-slope_pct_per_sec)) / 86400.0
