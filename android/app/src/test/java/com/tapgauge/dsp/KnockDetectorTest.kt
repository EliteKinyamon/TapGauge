package com.tapgauge.dsp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.random.Random
import org.junit.Test

class KnockDetectorTest {
    private val sr = TestSignals.SR
    private val frameSize = sr * 20 / 1000

    private fun quietFrame(rng: Random, amp: Double = 0.002) =
        DoubleArray(frameSize) { rng.nextGaussian(0.0, amp) }

    @Test fun `detects a sharp knock after ambient`() {
        val det = KnockDetector(sr)
        val rng = Random(5)
        // feed ambient quiet frames
        repeat(30) { det.accept(quietFrame(rng)) }
        // now a loud knock frame (sharp attack)
        val knock = DoubleArray(frameSize) { rng.nextDouble(-0.5, 0.5) }
        assertTrue("should detect sharp knock", det.accept(knock))
    }

    @Test fun `ignores gradual ambient rise`() {
        val det = KnockDetector(sr)
        val rng = Random(7)
        var detected = false
        // slowly ramp amplitude (like approaching noise / talking) -> no sharp attack
        for (i in 0 until 60) {
            val amp = 0.002 + i * 0.002
            if (det.accept(quietFrame(rng, amp))) detected = true
        }
        assertFalse("gradual rise should not trigger onset", detected)
    }
}
