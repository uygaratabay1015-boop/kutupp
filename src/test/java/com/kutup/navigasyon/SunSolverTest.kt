package com.kutup.navigasyon

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Random

class SunSolverTest {
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun ephemerisMatchesKnownValues() {
        val p = SunEphemeris.position(ms(2010, 3, 3, 12))
        assertEquals(-6.75, p.decDeg, 0.1)
        val (_, lon) = SunEphemeris.subSolarPoint(ms(2010, 3, 3, 12))
        assertEquals(3.05, lon, 0.15)          // zaman denklemi -12.2 dk
        val j = SunEphemeris.position(ms(2000, 1, 1, 12))
        assertEquals(-23.03, j.decDeg, 0.05)
        assertEquals(281.28, j.raDeg, 0.1)     // 18h45m
    }

    private fun apparentFromTrue(zTrue: Double): Double {
        var za = zTrue
        repeat(20) { za = zTrue - (SunEphemeris.trueZenithDistanceDeg(za) - za) }
        return za
    }

    private fun simulate(lat: Double, lon: Double, hours: List<Int>, sigma: Double, seed: Long, day: Triple<Int, Int, Int>): Double {
        val rng = Random(seed)
        val sights = hours.map { h ->
            val t = ms(day.first, day.second, day.third, h)
            val z = SunEphemeris.zenithDistanceDeg(lat, lon, SunEphemeris.position(t))
            val app = apparentFromTrue(z) + rng.nextGaussian() * sigma
            SunSighting(t, SunEphemeris.trueZenithDistanceDeg(app), sigma)
        }
        val fix = SunSolver.solve(sights) ?: return Double.NaN
        return ZenithFix.greatCircleKm(lat, lon, fix.latDeg, fix.lonDeg)
    }

    @Test fun solvesPositionFromSunSightings() {
        // ayni gun (UTC) icinde gunduz olcumleri: yerel ~08-16
        val cases = listOf(
            Triple(51.84, -2.81, listOf(8, 10, 12, 14, 16)),
            Triple(-33.9, 151.2, listOf(21, 23, 1, 3, 5)),   // Sydney (UTC saatleri)
            Triple(0.0, 0.0, listOf(8, 10, 14, 16)),
            Triple(39.9, 32.8, listOf(6, 8, 10, 12, 14)),
            Triple(64.0, -22.0, listOf(10, 12, 14))
        )
        for (sigma in listOf(0.02, 0.05, 0.1)) {
            val errs = ArrayList<Double>()
            for ((lat, lon, hrs) in cases) for (seed in 1L..15L) {
                // Sydney: 23,1,3,5 ertesi gune tasar; basit tutmak icin ayri gunler yerine ayni gun
                val e = simulate(lat, lon, hrs.filter { it in 0..23 }, sigma, seed, Triple(2010, 3, 3))
                if (!e.isNaN()) errs.add(e)
            }
            errs.sort()
            println("SIGMA %.2f deg: n=%d medyan %.1f km, p90 %.1f km, max %.1f km".format(sigma, errs.size, errs[errs.size / 2], errs[(errs.size * 0.9).toInt()], errs.last()))
            if (sigma <= 0.05) assertTrue("medyan cok buyuk: ${errs[errs.size/2]}", errs[errs.size / 2] < 12.0)
        }
    }

    @Test fun errorEstimateIsConsistent() {
        val lat = 51.84; val lon = -2.81
        val errs = ArrayList<Double>(); val est = ArrayList<Double>()
        for (seed in 1L..60L) {
            val rng = Random(seed)
            val sights = listOf(8, 10, 12, 14, 16).map { h ->
                val t = ms(2010, 3, 3, h)
                val z = SunEphemeris.zenithDistanceDeg(lat, lon, SunEphemeris.position(t)) + rng.nextGaussian() * 0.05
                SunSighting(t, z, 0.05)
            }
            val f = SunSolver.solve(sights)!!
            errs.add(ZenithFix.greatCircleKm(lat, lon, f.latDeg, f.lonDeg)); est.add(f.errorKm68)
        }
        println("gercek medyan %.1f km, tahmin (68%%) medyan %.1f km".format(errs.sorted()[30], est.sorted()[30]))
        assertTrue(est.sorted()[30] > errs.sorted()[30] * 0.4)
    }

    @Test fun ambiguityResolvedByCompass() {
        val lat = 51.84; val lon = -2.81
        val ts = listOf(ms(2010, 3, 3, 9), ms(2010, 3, 3, 11))
        val sights = ts.map { t ->
            val p = SunEphemeris.position(t)
            SunSighting(t, SunEphemeris.zenithDistanceDeg(lat, lon, p), 0.03, SunEphemeris.azimuthDeg(lat, lon, p) + 8.0)
        }
        val f = SunSolver.solve(sights)!!
        assertTrue("yanlis aday secildi: ${f.latDeg},${f.lonDeg}", ZenithFix.greatCircleKm(lat, lon, f.latDeg, f.lonDeg) < 20.0)
    }

    @Test fun detectsSunCentroid() {
        val w = 1200; val h = 900
        val rng = Random(5)
        for (t in 0 until 10) {
            val cx = 400 + rng.nextDouble() * 400; val cy = 300 + rng.nextDouble() * 300
            val g = IntArray(w * h) { 25 + rng.nextInt(6) }
            for (y in 0 until h) for (x in 0 until w) {
                val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                val v = 255.0 * Math.exp(-d2 / (2 * 14.0 * 14.0)) * 2.5   // doymus cekirdek + hale
                if (v > 25) g[y * w + x] = minOf(255, v.toInt())
            }
            val d = SunDetector.detect(g, w, h)
            assertTrue(d.reason, d.ok)
            assertEquals(cx, d.x, 0.3); assertEquals(cy, d.y, 0.3)
        }
    }
}
