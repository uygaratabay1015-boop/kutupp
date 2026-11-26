package com.kutup.navigasyon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kutup.navigasyon.TiltSimulation as Sim

class TiltEstimatorTest {

    private fun errs(
        tremor: Double, handAccel: Double, seconds: Double = 40.0, n: Int = 40,
        withGyro: Boolean = true, bias: Double = 0.01, select: (Sim.Result) -> Double
    ): List<Double> = (1..n).map {
        select(Sim.run(it.toLong(), tremorDeg = tremor, handAccelMps2 = handAccel,
            seconds = seconds, withGyro = withGyro, gyroBiasRadPerSec = bias))
    }

    @Test
    fun tripod_isNearlyExact() {
        val e = errs(0.02, 0.02) { it.windowFusedErrDeg }
        assertTrue("medyan ${Sim.median(e)}", Sim.median(e) < 0.05)
        assertTrue("p95 ${Sim.p95(e)}", Sim.p95(e) < 0.1)
    }

    @Test
    fun carefulHand_fusionBeatsAccelerometerByLargeMargin() {
        val fused = errs(0.3, 0.3) { it.windowFusedErrDeg }
        val ema = errs(0.3, 0.3) { it.emaAccelErrDeg }
        assertTrue("fuzyon ${Sim.median(fused)}", Sim.median(fused) < 0.2)
        assertTrue(Sim.median(fused) < 0.2 * Sim.median(ema))
    }

    @Test
    fun typicalHand_errorBelowHalfDegree() {
        val fused = errs(0.7, 0.7) { it.windowFusedErrDeg }
        val ema = errs(0.7, 0.7) { it.emaAccelErrDeg }
        assertTrue("fuzyon ${Sim.median(fused)}", Sim.median(fused) < 0.6)
        assertTrue("p95 ${Sim.p95(fused)}", Sim.p95(fused) < 1.5)
        assertTrue(Sim.median(fused) < 0.25 * Sim.median(ema))
    }

    @Test
    fun shakyHand_doesNotDiverge() {
        // Eski rateScale=0.5 ile burada ~8 derece sapiyordu (ivmeolcer devre disi kaliyordu).
        val fused = errs(1.5, 1.5) { it.windowFusedErrDeg }
        val ema = errs(1.5, 1.5) { it.emaAccelErrDeg }
        assertTrue("fuzyon ${Sim.median(fused)}", Sim.median(fused) < 2.5)
        assertTrue(Sim.median(fused) < 0.4 * Sim.median(ema))
    }

    @Test
    fun stationaryPhone_learnsLargeGyroBias() {
        val e = errs(0.0, 0.0, seconds = 30.0, n = 20, bias = 0.03) { it.windowFusedErrDeg }
        assertTrue("medyan ${Sim.median(e)}", Sim.median(e) < 0.05)
    }

    @Test
    fun withoutGyro_fallsBackToSmoothedAccelerometer() {
        val e = errs(0.7, 0.7, withGyro = false) { it.windowFusedErrDeg }
        assertTrue("medyan ${Sim.median(e)}", Sim.median(e) < 1.5)
    }

    @Test
    fun shakeIsReportedThroughSpreadAndQuality() {
        val still = Sim.run(1, tremorDeg = 0.02, handAccelMps2 = 0.02, seconds = 20.0)
        val shaky = Sim.run(1, tremorDeg = 1.5, handAccelMps2 = 1.5, seconds = 20.0)
        assertTrue(still.quality > 0.9)
        assertTrue(shaky.spreadDeg > 5 * still.spreadDeg)
        assertTrue(shaky.quality < still.quality)
    }

    @Test
    fun pitchAndRoll_fromStaticGravity() {
        val g = 9.80665
        val est = TiltEstimator()
        // Dik tutulan telefon, kamera ufka bakiyor: ivmeolcer (0, +g, 0)
        for (i in 0 until 50) est.addAccelerometer(i * 20_000_000L, 0.0, g, 0.0)
        val level = est.estimateLatest()!!
        assertEquals(0.0, level.pitchDeg, 1e-6)
        assertEquals(0.0, level.rollDeg, 1e-6)

        // Kamera 45 derece yukari: zenit cihazda (0, cos45, -sin45)
        val est2 = TiltEstimator()
        val p = Math.toRadians(45.0)
        for (i in 0 until 50) est2.addAccelerometer(i * 20_000_000L, 0.0, g * Math.cos(p), -g * Math.sin(p))
        val up45 = est2.estimateLatest()!!
        assertEquals(45.0, up45.pitchDeg, 1e-6)
        assertEquals(0.0, up45.rollDeg, 1e-6)

        // Saat yonunde 10 derece roll (sag taraf asagi): zenit goruntude sola kayar
        val est3 = TiltEstimator()
        val u = Sim.upDevice(30.0, 10.0)
        for (i in 0 until 50) est3.addAccelerometer(i * 20_000_000L, g * u[0], g * u[1], g * u[2])
        val rolled = est3.estimateLatest()!!
        assertEquals(30.0, rolled.pitchDeg, 1e-6)
        assertEquals(10.0, rolled.rollDeg, 1e-6)
    }

    @Test
    fun cameraAxisConversion_matchesZenithFixConvention() {
        for ((p, r) in listOf(0.0 to 0.0, 40.0 to 5.0, 60.0 to -25.0, 15.0 to 33.0)) {
            val dev = Sim.upDevice(p, r)
            val cam = TiltEstimator.upInCamera(dev, 0)
            val ref = ZenithFix.upFromTilt(p, r)
            for (i in 0..2) assertEquals("p=$p r=$r i=$i", ref[i], cam[i], 1e-12)
            val (pp, rr) = TiltEstimator.pitchRollFromCameraUp(cam)
            assertEquals(p, pp, 1e-9)
            assertEquals(r, rr, 1e-9)
        }
    }

    @Test
    fun landscapeDisplayRotation_keepsPitchAndMapsRollCorrectly() {
        // Telefon yatay (ekran 90 derece), kamera ufka bakiyor: yukari cihazda +x
        val levelLandscape = TiltEstimator.upInCamera(doubleArrayOf(1.0, 0.0, 0.0), 90)
        val (p, r) = TiltEstimator.pitchRollFromCameraUp(levelLandscape)
        assertEquals(0.0, p, 1e-9)
        assertEquals(0.0, r, 1e-9)
        // Ters yatay (270): yukari cihazda -x
        val (p2, r2) = TiltEstimator.pitchRollFromCameraUp(TiltEstimator.upInCamera(doubleArrayOf(-1.0, 0.0, 0.0), 270))
        assertEquals(0.0, p2, 1e-9)
        assertEquals(0.0, r2, 1e-9)
        // Yukseklik ekran donusunden bagimsizdir
        val dev = Sim.upDevice(35.0, 0.0)
        for (rot in listOf(0, 90, 180, 270)) {
            val (pp, _) = TiltEstimator.pitchRollFromCameraUp(TiltEstimator.upInCamera(dev, rot))
            assertEquals(35.0, pp, 1e-9)
        }
        assertEquals(90, TiltEstimator.surfaceRotationToDegrees(1))
    }

    @Test
    fun invalidInputsAreIgnoredAndTooFewSamplesGiveNull() {
        val est = TiltEstimator()
        est.addAccelerometer(0L, 0.0, 0.0, 0.0)
        est.addAccelerometer(1L, Double.NaN, 1.0, 1.0)
        est.addGyroscope(2L, Double.NaN, 0.0, 0.0)
        assertNull(est.estimateLatest())
        for (i in 0 until 3) est.addAccelerometer(i * 20_000_000L, 0.0, 9.8, 0.0)
        assertNull(est.estimateLatest())
        for (i in 3 until 12) est.addAccelerometer(i * 20_000_000L, 0.0, 9.8, 0.0)
        assertNotNull(est.estimateLatest())
    }
}
