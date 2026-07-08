package com.tapgauge.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the ADDITIVE Driveway Calibration helper (RV re-scope section 3.3). The
 * curve-fit math in CalibrationEngine is untouched; this only covers the
 * cumulative-gallons -> %-of-capacity conversion used by the grey/black flow.
 */
class VolumeConversionTest {
    @Test fun `converts cumulative gallons to percent of capacity`() {
        assertEquals(0.0, CalibrationEngine.percentFromCumulativeGallons(0.0, 40.0)!!, 1e-9)
        assertEquals(25.0, CalibrationEngine.percentFromCumulativeGallons(10.0, 40.0)!!, 1e-9)
        assertEquals(50.0, CalibrationEngine.percentFromCumulativeGallons(20.0, 40.0)!!, 1e-9)
        assertEquals(100.0, CalibrationEngine.percentFromCumulativeGallons(40.0, 40.0)!!, 1e-9)
    }

    @Test fun `clamps overfill to 100 percent`() {
        assertEquals(100.0, CalibrationEngine.percentFromCumulativeGallons(55.0, 40.0)!!, 1e-9)
    }

    @Test fun `null for non-positive capacity`() {
        assertNull(CalibrationEngine.percentFromCumulativeGallons(10.0, 0.0))
        assertNull(CalibrationEngine.percentFromCumulativeGallons(10.0, -5.0))
    }
}
