package com.tapgauge.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpectralAnalyzerTest {
    private val sr = TestSignals.SR
    private val analyzer = SpectralAnalyzer(sr)

    @Test fun `recovers known frequency clean`() {
        for (f in listOf(180.0, 437.0, 512.5, 1234.0, 1997.3)) {
            val seg = TestSignals.knock(f, snrDb = 40.0, seed = f.toInt())
            val res = analyzer.analyzeTap(seg)
            assertNotNull("no peak for $f Hz", res.fundamentalHz)
            assertTrue("got ${res.fundamentalHz} for true $f",
                abs(res.fundamentalHz!! - f) / f < 0.01)
        }
    }

    @Test fun `recovers under noise`() {
        val f = 623.0
        val errs = ArrayList<Double>()
        for (seed in 0 until 15) {
            val seg = TestSignals.knock(f, snrDb = 15.0, seed = seed)
            val res = analyzer.analyzeTap(seg)
            assertNotNull(res.fundamentalHz)
            errs.add(abs(res.fundamentalHz!! - f) / f)
        }
        errs.sort()
        assertTrue("median err ${errs[errs.size / 2]}", errs[errs.size / 2] < 0.02)
    }

    @Test fun `parabolic beats raw bin`() {
        val f = 517.3
        val seg = TestSignals.knock(f, snrDb = 45.0, seed = 3)
        val mags = analyzer.magnitudeSpectrum(seg)
        val nfft = (mags.size - 1) * 2
        var k = 1
        for (i in 1 until mags.size - 1) if (mags[i] > mags[k]) k = i
        val rawHz = analyzer.binToHz(k.toDouble(), nfft)
        val (refBin, _) = analyzer.parabolicInterpolation(mags, k)
        val refHz = analyzer.binToHz(refBin, nfft)
        assertTrue("parabolic ($refHz) should beat raw ($rawHz) for $f",
            abs(refHz - f) < abs(rawHz - f))
        assertTrue(abs(refHz - f) / f < 0.01)
    }

    @Test fun `fundamental wins over overtones`() {
        val f = 300.0
        val seg = TestSignals.knock(f, snrDb = 35.0, seed = 9,
            overtones = listOf(2.0 to 0.7, 3.0 to 0.5))
        val res = analyzer.analyzeTap(seg)
        assertNotNull(res.fundamentalHz)
        assertTrue("picked ${res.fundamentalHz} not $f",
            abs(res.fundamentalHz!! - f) / f < 0.02)
    }

    @Test fun `reject harmonics drops multiples`() {
        val peaks = listOf(
            Peak(300.0, 10.0, 10.0),
            Peak(600.0, 6.0, 6.0),
            Peak(900.0, 4.0, 4.0),
            Peak(740.0, 5.0, 5.0),
        )
        val kept = analyzer.rejectHarmonics(peaks).map { it.freqHz.toInt() }
        assertTrue(kept.contains(300)); assertTrue(kept.contains(740))
        assertFalse(kept.contains(600)); assertFalse(kept.contains(900))
    }

    @Test fun `median rejects one bad tap`() {
        val f = 800.0
        val good1 = analyzer.analyzeTap(TestSignals.knock(f, seed = 1, snrDb = 35.0))
        val good2 = analyzer.analyzeTap(TestSignals.knock(f, seed = 2, snrDb = 35.0))
        val bad = analyzer.analyzeTap(TestSignals.knock(f * 1.5, seed = 3, snrDb = 35.0))
        val reading = analyzer.combineTaps(listOf(good1, bad, good2))
        assertNotNull(reading.frequencyHz)
        assertTrue(abs(reading.frequencyHz!! - f) / f < 0.02)
    }

    @Test fun `large spread flagged not ok`() {
        val r = analyzer.combineTaps(listOf(
            analyzer.analyzeTap(TestSignals.knock(500.0, seed = 1, snrDb = 35.0)),
            analyzer.analyzeTap(TestSignals.knock(900.0, seed = 2, snrDb = 35.0)),
            analyzer.analyzeTap(TestSignals.knock(1500.0, seed = 3, snrDb = 35.0)),
        ))
        assertFalse(r.ok)
    }

    @Test fun `end to end tracks truth within 10 percent`() {
        fun trueFreq(pct: Double) = 480.0 + 460.0 * Math.pow(pct / 100.0, 0.85)
        fun measure(pct: Double, seed: Int): ReadingResult {
            val f = trueFreq(pct)
            val taps = (0 until 3).map {
                analyzer.analyzeTap(TestSignals.knock(f, seed = seed + it, snrDb = 22.0))
            }
            return analyzer.combineTaps(taps)
        }
        val engine = com.tapgauge.calibration.CalibrationEngine()
        val cal = listOf(0.0, 40.0, 70.0, 100.0)
        cal.forEachIndexed { i, pct ->
            val r = measure(pct, 100 + i * 7)
            assertTrue("cal tap $pct not ok", r.ok)
            val chk = engine.addAnchor(
                com.tapgauge.calibration.CalibrationPoint(i.toLong(), r.frequencyHz!!, pct))
            assertTrue("anchor $pct rejected: ${chk.reason}", chk.ok)
        }
        var maxErr = 0.0
        for ((j, truePct) in listOf(15.0, 30.0, 55.0, 85.0).withIndex()) {
            val r = measure(truePct, 500 + j * 11)
            val est = engine.estimateLevel(r.frequencyHz!!)
            assertNotNull(est.percent)
            val err = abs(est.percent!! - truePct)
            maxErr = maxOf(maxErr, err)
            assertTrue("at true $truePct est ${est.percent} err $err", err <= 10.0)
        }
        println("[E2E-Kotlin] worst intermediate error: $maxErr% (bar 10%)")
    }
}
