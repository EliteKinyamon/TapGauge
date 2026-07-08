# TapGauge

Estimate how full a tank is by **tapping it with your phone** — no attached
sensor, no cloud, no account. Just the microphone already in your pocket.

> **Estimate, not a certified gauge.** TapGauge is a convenience tool. Do not
> rely on it for safety-critical decisions, especially with propane.

---

## The gap this fills

Tapping a tank makes the air space above the liquid resonate — the same reason
blowing across a bottle gives a note that rises as the bottle empties. That
physics is well established, and there are already **hardware** products
(Mopeka Tank Check, Gobius Pro) that stick a Bluetooth/ultrasonic/accelerometer
sensor on the tank and talk to an app.

Nobody ships the **software-only** version: microphone in, fill-percentage out,
with an automatic per-tank calibration workflow. It doesn't exist because it's
genuinely hard, not merely neglected:

- Real tanks aren't clean cylinders (domed ends, baffles, ribs, dents, varying
  wall material), so there is **no closed-form frequency→level formula**. It has
  to be **learned per tank** through calibration.
- A human can't do that learning by ear or by hand: pulling a clean resonant
  peak out of a noisy knock needs an **FFT**, and fitting a frequency→level
  curve needs **regression** — trivial for a phone, impossible on paper.

TapGauge closes that gap with (1) a robust DSP core and (2) a calibration
workflow anchored to events that already happen ("just filled", "just ran out"),
so it never feels like a chore.

---

## Physics rationale (plain language)

The air column above the liquid behaves roughly like a **closed–open acoustic
resonator**: closed at the liquid surface, open/lossy at the walls and any vent.
As the liquid drops, the air column effectively lengthens and the resonant pitch
**falls** — so, over a calibrated tank, **higher pitch = more full**.

Two consequences the code takes seriously:

- **Calibration must be per-tank.** Secondary resonances from caps, baffles, and
  wall material change the fundamental's relationship to fill height, so a
  universal physics model isn't a v1 feature — we learn each tank instead.
- **Drift happens.** The speed of sound shifts ~0.6 m/s per °C, so a cold-morning
  "full" reads slightly differently than a warm-afternoon one. We handle this
  with re-calibration nudges (see `CalibrationEngine.checkDrift`), not by trying
  to model temperature.

---

## What was actually validated (and how)

Because the DSP + calibration math is the entire value proposition, it was
prototyped and **proven in Python first** (runnable, no Android needed), then
ported line-for-line into Kotlin.

`python_prototype/` — run it yourself:

```bash
cd python_prototype
python3 test_tapgauge.py      # 19 tests
```

Proven there (and mirrored by the Kotlin JVM tests):

| Property | Result |
|---|---|
| Hand-rolled radix-2 FFT vs naive DFT **and** numpy | matches to float precision |
| Known-frequency recovery, clean signal | within **1%** |
| Known-frequency recovery, 15 dB SNR | median error **< 2%** |
| Parabolic sub-bin interpolation vs raw-bin reading | strictly closer to truth |
| 2×/3× overtones present | fundamental still picked |
| One stray tap among three | rejected by the median |
| **End-to-end**: calibrate 4 anchors, estimate 4 unseen levels | worst error **2.38%** (bar: ±10%) |

The Kotlin unit tests under `app/src/test/` re-assert every one of these against
the shipping code.

---

## Architecture

Kotlin · Jetpack Compose · MVVM · Coroutines/Flow · Room. **No network layer.**

```
app/src/main/java/com/tapgauge/
  dsp/
    Fft.kt                  hand-rolled radix-2 Cooley–Tukey FFT (no deps)
    SpectralAnalyzer.kt     Hann window, magnitude spectrum, band-limited peak
                            picking, parabolic sub-bin refine, harmonic
                            rejection, median-of-taps  (§5.3–5.5)
    KnockDetector.kt        onset detection: energy jump + sharp attack (§5.2)
    AudioCaptureEngine.kt   AudioRecord -> Flow<PCM frame>, mic open only while
                            collected; raw audio never written to disk (§5.1)
  calibration/
    CalibrationEngine.kt    monotone linear-interp curve, confidence, drift,
                            days-until-empty  (§6)
  data/                     Room entities, DAOs, DB, repository (§4)
  audio/MeasurementSession.kt  ties capture -> detector -> analyzer into a
                               3-tap session emitting UI events
  ui/                       Compose screens + ViewModels, one per flow (§3)
    screens/  Onboarding, Home, AddTank, Measure, Calibrate, History,
              Settings, Diagnostics
```

**Calibration = linear interpolation between anchor points**, deliberately. It's
easy to explain in-app, robust to weird geometry without a physics model, and
already tracks truth to ~2.4% in simulation. Fancier fits (isotonic, splines,
physics-assisted) are explicit stretch goals — not to be added before the simple
version is shown to need it against real recordings.

### Privacy stance

Raw audio is processed in memory and discarded immediately after the peak
frequency + amplitude are extracted. It is **never written to disk, never leaves
the device**, and there is **no account or network connection** — the manifest
requests only `RECORD_AUDIO`, with **no `INTERNET` permission at all**. The Room
database is also excluded from cloud backup and device transfer.

---

## Building

Requires Android Studio (Koala or newer) with the Android SDK. `minSdk 26`
(Android 8.0), `compileSdk 34`.

```bash
# Open android/ in Android Studio and Run, OR from the command line:
cd android
gradle wrapper          # one-time: generates the gradle-wrapper.jar
./gradlew assembleDebug  # build the debug APK
./gradlew test           # run the JVM unit tests (DSP + calibration)
```

> This project ships the Gradle wrapper *scripts* and `gradle-wrapper.properties`
> but not the binary `gradle-wrapper.jar`. Android Studio regenerates it on
> import, or run `gradle wrapper` once (needs a local Gradle ≥ 8.9).

The **Diagnostics** screen (live spectrogram + detected-peak readout, used to
tune onset thresholds and the resonance band) is compiled into the app but gated
behind `BuildConfig.DIAGNOSTICS_ENABLED` — **on** in debug builds, **off** in
release.

---

## Manual validation protocol (§9)

Reproduce the acceptance test with a real container:

1. Mark a 5-gallon jug at 0 / 25 / 50 / 75 / 100% (by measured water volume).
2. Create a tank profile. At each level, tap and log an anchor (use "Existing
   gauge cross-check" to enter the known %).
3. Between marks, take normal measurements and confirm the estimate stays within
   ±10% of the true level. Tighten expectations once you gather real data.

Record your results here once run on a device:

> _E2E on-device result: (fill in — expected within ±10%)._

---

## Known limitations

- **Needs calibration.** Zero-calibration universal readings are a research
  problem, not v1. Confidence is shown honestly: `uncalibrated` (0 anchors) →
  `rough` (2) → `medium` (3) → `high` (4+).
- **Temperature drift.** Big temperature swings shift the "full" frequency;
  TapGauge nudges you to recalibrate rather than modeling temperature.
- **Tank-shape edge cases.** Heavy baffling or unusual materials can produce
  multiple strong resonances; the per-profile search band and harmonic rejection
  help, but a very noisy trend line in History is the tell that a tank needs
  re-calibration or was moved.
- **Extrapolation.** Readings outside the calibrated frequency range are clamped
  and clearly flagged as lower-confidence.

---

## Definition-of-done status

| Item | Status |
|---|---|
| Create profile + calibrate with only Full/Empty, no hardware | ✅ implemented |
| 3-tap measurement → % + confidence badge | ✅ implemented (validate on device) |
| Onset detector rejects ambient noise | ✅ implemented + unit-tested |
| Calibration updates monotonically | ✅ implemented + unit-tested |
| DSP unit tests pass | ✅ Python 19/19; Kotlin mirror included |
| Manual protocol run on device | ⬜ run once, record above |
| No network permission in manifest | ✅ verified — `RECORD_AUDIO` only |
