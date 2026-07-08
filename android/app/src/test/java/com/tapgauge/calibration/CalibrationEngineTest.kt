package com.tapgauge.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationEngineTest {
    private fun fullEmpty(): CalibrationEngine {
        val e = CalibrationEngine()
        e.addAnchor(CalibrationPoint(0, 900.0, 100.0))
        e.addAnchor(CalibrationPoint(1, 500.0, 0.0))
        return e
    }

    @Test fun `linear interpolation midpoint`() {
        val est = fullEmpty().estimateLevel(700.0)
        assertEquals(50.0, est.percent!!, 0.1)
        assertFalse(est.extrapolated)
        assertEquals(Confidence.ROUGH, est.confidence)
    }

    @Test fun `extrapolation clamped and flagged`() {
        val e = fullEmpty()
        val hi = e.estimateLevel(1000.0)
        assertEquals(100.0, hi.percent!!, 1e-9); assertTrue(hi.extrapolated)
        val lo = e.estimateLevel(400.0)
        assertEquals(0.0, lo.percent!!, 1e-9); assertTrue(lo.extrapolated)
    }

    @Test fun `monotonicity enforced`() {
        val e = fullEmpty()
        val bad = e.addAnchor(CalibrationPoint(2, 1200.0, 50.0))
        assertFalse(bad.ok); assertEquals(2, e.anchorCount())
        val good = e.addAnchor(CalibrationPoint(3, 700.0, 50.0))
        assertTrue(good.ok); assertEquals(3, e.anchorCount())
        assertEquals(Confidence.MEDIUM, e.confidence())
    }

    @Test fun `confidence levels`() {
        val e = CalibrationEngine()
        assertEquals(Confidence.UNCALIBRATED, e.confidence())
        e.addAnchor(CalibrationPoint(0, 900.0, 100.0))
        assertEquals(Confidence.UNCALIBRATED, e.confidence())
        e.addAnchor(CalibrationPoint(1, 500.0, 0.0))
        assertEquals(Confidence.ROUGH, e.confidence())
        e.addAnchor(CalibrationPoint(2, 700.0, 50.0))
        assertEquals(Confidence.MEDIUM, e.confidence())
        e.addAnchor(CalibrationPoint(3, 800.0, 75.0))
        assertEquals(Confidence.HIGH, e.confidence())
    }

    @Test fun `drift detection`() {
        val e = fullEmpty()
        assertTrue(e.checkDrift(960.0).drifted)
        assertFalse(e.checkDrift(905.0).drifted)
    }

    @Test fun `days until empty`() {
        val day = 86400.0
        val hist = listOf(0.0 to 100.0, day to 90.0, 2 * day to 80.0, 3 * day to 70.0)
        val d = CalibrationEngine.daysUntilEmpty(hist)
        assertNotNull(d); assertEquals(7.0, d!!, 0.5)
        assertNull(CalibrationEngine.daysUntilEmpty(listOf(0.0 to 50.0, day to 60.0)))
    }
}
