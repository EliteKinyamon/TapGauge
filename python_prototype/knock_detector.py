"""
Python port of the Android KnockDetector (spec section 5.2), used by the WAV harness
to segment real recordings into individual knocks. Mirrors
app/src/main/java/com/tapgauge/dsp/KnockDetector.kt exactly.
"""
from __future__ import annotations
import math


class KnockDetector:
    def __init__(self, sample_rate=44100, threshold_ratio=6.0, attack_ratio=3.0,
                 floor_alpha=0.05, refractory_frames=6):
        self.sr = sample_rate
        self.thr = threshold_ratio
        self.attack = attack_ratio
        self.fa = floor_alpha
        self.refr_n = refractory_frames
        self.nf = 0.0
        self.sa = 0.0
        self.refr = 0
        self.init = False

    def _energy(self, frame):
        if not frame:
            return 0.0
        return math.sqrt(sum(x * x for x in frame) / len(frame))

    def accept(self, frame) -> bool:
        e = self._energy(frame)
        if not self.init:
            self.nf = e; self.sa = e; self.init = True
            return False
        if self.refr > 0:
            self.refr -= 1
            self.sa = 0.5 * self.sa + 0.5 * e
            return False
        floor = self.nf + 1e-9
        above = e > floor * self.thr
        sharp = e > (self.sa + 1e-9) * self.attack
        if above and sharp:
            self.refr = self.refr_n
            self.sa = 0.5 * self.sa + 0.5 * e
            return True
        self.sa = 0.7 * self.sa + 0.3 * e
        self.nf = (1 - self.fa) * self.nf + self.fa * e
        return False
