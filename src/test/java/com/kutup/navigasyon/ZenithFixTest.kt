package com.kutup.navigasyon

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Referans degerler astro_nav/zenith_fix.py (500 rastgele konumla dogrulanmis) ile uretildi.
 */
class ZenithFixTest {

    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private class Case(
        val name: String, val iso: String,
        val ra: Double, val dec: Double, val roll: Double,
        val up: DoubleArray, val lat: Double, val lon: Double
    )

    private val cases = listOf(
        Case("Istanbul", "2026-11-20T21:05:00Z", 65.479385923, 0.043455198, -30.303439317,
            doubleArrayOf(-0.098410243448, -0.700225266600, 0.707106781187), 41.0082, 28.9784),
        Case("Sydney", "2026-06-21T14:30:00Z", 214.737437790, -72.802373507, 50.560925728,
            doubleArrayOf(-0.323744370967, -0.694272044015, 0.642787609687), -33.8688, 151.2093),
        Case("Fiji/tarih cizgisi", "2026-01-03T09:00:00Z", 62.631792761, 11.807539826, -130.398748675,
            doubleArrayOf(0.321393804843, -0.383022221559, 0.866025403784), -17.7, 179.9),
        Case("Reykjavik", "2026-12-01T02:00:00Z", 338.285536545, 43.870623933, 31.761723911,
            doubleArrayOf(0.0, -0.819152044289, 0.573576436351), 64.14, -21.9)
    )

    @Test
    fun solvePosition_matchesPythonReference_worldwide() {
        for (c in cases) {
            val r = ZenithFix.rotationFromRadecRoll(c.ra, c.dec, c.roll)
            val fix = ZenithFix.solvePosition(r, c.up, millis(c.iso))
            val km = ZenithFix.greatCircleKm(c.lat, c.lon, fix.latitudeDeg, fix.longitudeDeg)
            assertTrue("${c.name}: ${km} km sapma", km < 0.05)
            assertEquals("${c.name} enlem", c.lat, fix.latitudeDeg, 0.001)
            assertEquals("${c.name} boylam", c.lon, fix.longitudeDeg, 0.001)
        }
    }

    @Test
    fun julianDateAndSiderealTime_matchReference() {
        val jd = ZenithFix.julianDateUtc(millis("2026-11-20T21:05:00Z"))
        assertEquals(2461365.378472222, jd, 1e-6)
        assertEquals(2451545.0, ZenithFix.julianDateUtc(millis("2000-01-01T12:00:00Z")), 1e-9)
        val gastDeg = Math.toDegrees(ZenithFix.greenwichApparentSiderealTime(jd))
        assertEquals(16.14294749, gastDeg, 1e-4)
    }

    @Test
    fun upFromTilt_geometry() {
        val level = ZenithFix.upFromTilt(0.0, 0.0)
        assertEquals(0.0, level[0], 1e-12)
        assertEquals(-1.0, level[1], 1e-12)
        assertEquals(0.0, level[2], 1e-12)

        val zenith = ZenithFix.upFromTilt(90.0, 0.0)
        assertEquals(1.0, zenith[2], 1e-12)

        val tilted = ZenithFix.upFromTilt(30.0, 20.0)
        assertEquals(-0.29619813, tilted[0], 1e-6)
        assertEquals(-0.81379768, tilted[1], 1e-6)
        assertEquals(0.5, tilted[2], 1e-6)
        assertEquals(30.0, ZenithFix.boresightAltitudeDeg(tilted), 1e-6)
    }

    @Test
    fun upFromGravity_respectsSensorSign() {
        val a = ZenithFix.upFromGravity(0.0, -9.81, 0.0)
        assertEquals(-1.0, a[1], 1e-12)
        val b = ZenithFix.upFromGravity(0.0, 9.81, 0.0, sensorPointsUp = false)
        assertEquals(-1.0, b[1], 1e-12)
    }
}
