#!/usr/bin/env python3
"""
TapGauge WAV harness -- closes Gap B (spec section 9 golden-file / real-recording test).

Runs REAL tap recordings through the SAME DSP + calibration pipeline the app
ships, so you can confirm real-world accuracy WITHOUT building the Android app.

Dependency-free (stdlib `wave` only). Works with mono or stereo 16-bit PCM WAV.

Usage:
  # 1) Sanity-check a single recording -> prints detected fundamental(s):
  python3 wav_harness.py analyze mytap.wav

  # 2) Full accuracy test from a manifest of labeled recordings:
  python3 wav_harness.py eval manifest.csv
     manifest.csv columns:  path,percent,role
       role = "anchor"  -> used to build the calibration curve
       role = "test"    -> held out; estimated % is compared to its true percent
     Reports per-file error and the worst-case error vs the +/-10% bar.

  # 3) Prove the harness itself works (synthesizes WAVs, no mic needed):
  python3 wav_harness.py selftest
"""
from __future__ import annotations

import csv
import math
import os
import struct
import sys
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tapgauge_dsp import analyze_tap, combine_taps
from tapgauge_calibration import CalibrationEngine, CalibrationPoint
from knock_detector import KnockDetector


# --------------------------------------------------------------------------- #
def load_wav(path):
    """Load a WAV as (mono_float_samples, sample_rate). Handles 16-bit PCM,
    mono or stereo (channels averaged)."""
    with wave.open(path, "rb") as w:
        n_ch = w.getnchannels()
        sw = w.getsampwidth()
        sr = w.getframerate()
        n = w.getnframes()
        raw = w.readframes(n)
    if sw != 2:
        raise ValueError(f"{path}: only 16-bit PCM WAV supported (got {sw*8}-bit)")
    ints = struct.unpack("<%dh" % (len(raw) // 2), raw)
    if n_ch == 1:
        samples = [s / 32768.0 for s in ints]
    else:
        # average channels to mono
        samples = []
        for i in range(0, len(ints), n_ch):
            samples.append(sum(ints[i:i + n_ch]) / (n_ch * 32768.0))
    return samples, sr


def extract_knocks(samples, sr, window_ms=100, frame_ms=20, max_knocks=8):
    """Segment a recording into individual knock windows using the shipped
    onset detector (spec section 5.2). Returns a list of DoubleArray-like segments."""
    frame = int(sr * frame_ms / 1000)
    win = int(sr * window_ms / 1000)
    det = KnockDetector(sr)
    segments = []
    i = 0
    while i + frame <= len(samples) and len(segments) < max_knocks:
        if det.accept(samples[i:i + frame]):
            seg = samples[i:i + win]
            if len(seg) >= win // 2:
                segments.append(seg)
            i += win  # skip past this knock's window
        else:
            i += frame
    # Fallback: if no onset fired (very clean/short clip), use the loudest window.
    if not segments and len(samples) >= win:
        peak = max(range(len(samples)), key=lambda k: abs(samples[k]))
        start = max(0, peak - frame)
        segments.append(samples[start:start + win])
    return segments


def analyze_file(path, f_lo=60.0, f_hi=3000.0):
    """Return the combined reading (median of detected knocks) for one recording."""
    samples, sr = load_wav(path)
    segs = extract_knocks(samples, sr)
    taps = [analyze_tap(seg, sr, f_lo, f_hi) for seg in segs]
    reading = combine_taps(taps) if taps else None
    return reading, [t.fundamental_hz for t in taps], sr


# --------------------------------------------------------------------------- #
def cmd_analyze(path):
    reading, taps, sr = analyze_file(path)
    print(f"file: {path}  (sr={sr} Hz)")
    print(f"  detected knocks: {len(taps)}")
    for i, f in enumerate(taps):
        print(f"    knock {i+1}: {f:.1f} Hz" if f else f"    knock {i+1}: (no peak)")
    if reading and reading.frequency_hz:
        print(f"  COMBINED: {reading.frequency_hz:.1f} Hz  "
              f"(spread {reading.spread_hz:.1f} Hz, ok={reading.ok})")
    else:
        print("  COMBINED: no usable reading")


def cmd_eval(manifest_path):
    base = os.path.dirname(os.path.abspath(manifest_path))
    anchors, tests = [], []
    with open(manifest_path, newline="") as fh:
        for row in csv.DictReader(fh):
            path = row["path"]
            if not os.path.isabs(path):
                path = os.path.join(base, path)
            pct = float(row["percent"])
            role = (row.get("role") or "test").strip().lower()
            reading, _, _ = analyze_file(path)
            if not reading or reading.frequency_hz is None:
                print(f"  WARN: no reading from {path}; skipping")
                continue
            (anchors if role == "anchor" else tests).append((path, pct, reading.frequency_hz))

    if len(anchors) < 2:
        print(f"Need >=2 anchor rows to build a curve (got {len(anchors)}).")
        return

    engine = CalibrationEngine()
    print("Calibration anchors:")
    for path, pct, hz in sorted(anchors, key=lambda x: x[2]):
        chk = engine.add_anchor(CalibrationPoint(0.0, hz, pct))
        flag = "" if chk.ok else f"  [REJECTED: {chk.reason}]"
        print(f"  {hz:8.1f} Hz -> {pct:5.1f}%{flag}")
    print(f"Confidence: {engine.confidence().value}\n")

    if not tests:
        print("No test rows to evaluate. Add rows with role=test.")
        return

    print("Held-out evaluation:")
    max_err = 0.0
    errs = []
    for path, true_pct, hz in tests:
        est = engine.estimate_level(hz)
        if est.percent is None:
            print(f"  {os.path.basename(path):24s} {hz:8.1f} Hz -> (uncalibrated)")
            continue
        err = abs(est.percent - true_pct)
        errs.append(err)
        max_err = max(max_err, err)
        mark = "OK " if err <= 10 else "!! "
        extra = " [extrapolated]" if est.extrapolated else ""
        print(f"  {mark}{os.path.basename(path):24s} {hz:8.1f} Hz -> "
              f"est {est.percent:5.1f}% | true {true_pct:5.1f}% | err {err:4.1f}%{extra}")
    if errs:
        mean = sum(errs) / len(errs)
        print(f"\nRESULT: worst error {max_err:.1f}%, mean {mean:.1f}%  (bar: 10%)")
        print("VERDICT:", "PASS -- gap B closed on this data" if max_err <= 10
              else "FAIL -- see notes below")


def _write_wav(path, samples, sr=44100):
    ints = [max(-32768, min(32767, int(s * 32767))) for s in samples]
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes(struct.pack("<%dh" % len(ints), *ints))


def cmd_selftest():
    """Synthesize labeled 'recordings' and run the full eval path, proving the
    harness (WAV IO + segmentation + calibration) works end-to-end. When this
    passes, real recordings dropped into a manifest will 'just work'."""
    import random, tempfile
    sr = 44100

    def synth_knock(freq, seed):
        rng = random.Random(seed)
        n = int(sr * 0.12)
        sig = []
        for i in range(n):
            t = i / sr
            sig.append(math.exp(-t / 0.03) * math.sin(2 * math.pi * freq * t))
        for i in range(int(sr * 0.002)):
            sig[i] += rng.uniform(-0.8, 0.8)
        # prepend 150 ms of quiet ambient so the onset detector has a floor
        ambient = [rng.gauss(0, 0.002) for _ in range(int(sr * 0.15))]
        return ambient + [s + rng.gauss(0, 0.02) for s in sig]

    def true_freq(pct):
        return 480.0 + 460.0 * (pct / 100.0) ** 0.85

    d = tempfile.mkdtemp(prefix="tapgauge_selftest_")
    rows = []
    for i, pct in enumerate((0.0, 40.0, 70.0, 100.0)):
        p = os.path.join(d, f"anchor_{int(pct)}.wav")
        _write_wav(p, synth_knock(true_freq(pct), 100 + i), sr)
        rows.append((p, pct, "anchor"))
    for j, pct in enumerate((15.0, 30.0, 55.0, 85.0)):
        p = os.path.join(d, f"test_{int(pct)}.wav")
        _write_wav(p, synth_knock(true_freq(pct), 500 + j), sr)
        rows.append((p, pct, "test"))
    manifest = os.path.join(d, "manifest.csv")
    with open(manifest, "w", newline="") as fh:
        wtr = csv.writer(fh); wtr.writerow(["path", "percent", "role"])
        for path, pct, role in rows:
            wtr.writerow([path, pct, role])

    print(f"selftest workspace: {d}\n")
    cmd_eval(manifest)


def main():
    if len(sys.argv) < 2:
        print(__doc__); return
    cmd = sys.argv[1]
    if cmd == "analyze" and len(sys.argv) == 3:
        cmd_analyze(sys.argv[2])
    elif cmd == "eval" and len(sys.argv) == 3:
        cmd_eval(sys.argv[2])
    elif cmd == "selftest":
        cmd_selftest()
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
