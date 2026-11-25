package com.kutup.navigasyon

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class TiltCalibrationTest {
    private val solver get() = StarPlateSolverTest.solver
    private val catalog get() = StarPlateSolverTest.catalog

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun unit(a: DoubleArray): DoubleArray { val n = sqrt(a.sumOf { it * it }); return DoubleArray(3) { a[it] / n } }

    /** Konum/saat/yon -> kamera->ICRS donusumu (kamera: x sag, y asagi, z bakis). */
    private fun sceneRotation(lat: Double, lon: Double, epoch: Long, pitch: Double, roll: Double, az: Double): Array<DoubleArray> {
        val jd = ZenithFix.julianDateUtc(epoch)
        val lst = Math.toRadians(lon) + ZenithFix.greenwichApparentSiderealTime(jd)
        val la = Math.toRadians(lat)
        val east = doubleArrayOf(-sin(lst), cos(lst), 0.0)
        val north = doubleArrayOf(-sin(la) * cos(lst), -sin(la) * sin(lst), cos(la))
        val upW = doubleArrayOf(cos(la) * cos(lst), cos(la) * sin(lst), sin(la))
        val p = Math.toRadians(pitch); val rr = Math.toRadians(roll)
        val b = DoubleArray(3) { cos(p) * (sin(az) * east[it] + cos(az) * north[it]) + sin(p) * upW[it] }
        val w = unit(DoubleArray(3) { (upW[it] - sin(p) * b[it]) / cos(p) })
        val q = cross(b, w)
        val x = DoubleArray(3) { -sin(rr) * w[it] + cos(rr) * q[it] }
        val y = DoubleArray(3) { -cos(rr) * w[it] - sin(rr) * q[it] }
        val pn = ZenithFix.precessionNutationMatrix(jd)
        fun toIcrs(v: DoubleArray) = DoubleArray(3) { i -> pn[0][i] * v[0] + pn[1][i] * v[1] + pn[2][i] * v[2] }
        val xi = toIcrs(x); val yi = toIcrs(y); val zi = toIcrs(b)
        return Array(3) { i -> doubleArrayOf(xi[i], yi[i], zi[i]) }
    }

    private fun synth(rng: Random, r: Array<DoubleArray>, fovDeg: Double, w: Int, h: Int, zenith: DoubleArray? = null): List<Star> {
        val f = (max(w, h) / 2.0) / tan(Math.toRadians(fovDeg) / 2)
        val out = ArrayList<Star>()
        for (i in 0 until catalog.size) {
            if (catalog.mag[i] > 4.6f || rng.nextDouble() < 0.25) continue
            var v = doubleArrayOf(catalog.vec[3 * i], catalog.vec[3 * i + 1], catalog.vec[3 * i + 2])
            if (zenith != null) { // gercek atmosfer kirilmasi: gorunen konum zenite dogru kayar
                val sa = (v[0] * zenith[0] + v[1] * zenith[1] + v[2] * zenith[2]).coerceIn(-1.0, 1.0)
                val alt = Math.toDegrees(asin(sa))
                if (alt < 8.0) continue
                val rho = Math.toRadians(1.02 / tan(Math.toRadians(alt + 10.3 / (alt + 5.11))) / 60.0)
                val t = DoubleArray(3) { zenith[it] - sa * v[it] }; val tn = sqrt(t.sumOf { it * it })
                v = DoubleArray(3) { v[it] * cos(rho) + sin(rho) * t[it] / tn }
            }
            val cam = DoubleArray(3) { k -> r[0][k] * v[0] + r[1][k] * v[1] + r[2][k] * v[2] }
            if (cam[2] <= 0.05) continue
            val x = w / 2.0 + f * cam[0] / cam[2] + rng.nextGaussian() * 0.7
            val y = h / 2.0 + f * cam[1] / cam[2] + rng.nextGaussian() * 0.7
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            out.add(Star(x.toFloat(), y.toFloat(), 10.0.pow(-0.4 * catalog.mag[i]).toFloat()))
        }
        repeat(8) { out.add(Star((rng.nextDouble() * w).toFloat(), (rng.nextDouble() * h).toFloat(), (rng.nextDouble() * 0.05).toFloat())) }
        return out
    }

    @Test fun zenithIcrsConsistentWithScene() {
        val epoch = 1_795_300_000_000L
        val r = sceneRotation(41.0, 29.0, epoch, 63.0, 2.0, 1.1)
        val z = ZenithFix.zenithIcrs(41.0, 29.0, epoch)
        val cam = DoubleArray(3) { k -> r[0][k] * z[0] + r[1][k] * z[1] + r[2][k] * z[2] }
        val expect = ZenithFix.upFromTilt(63.0, 2.0)
        for (i in 0 until 3) assertEquals(expect[i], cam[i], 1e-6)
        val fix = ZenithFix.solvePosition(r, expect, epoch)
        assertEquals(41.0, fix.latitudeDeg, 1e-4); assertEquals(29.0, fix.longitudeDeg, 1e-4)
    }

    @Test fun serializationRoundTrip() {
        val c = TiltCalibration()
        c.addFrame(0, doubleArrayOf(0.0, -0.5, 0.86), doubleArrayOf(0.01, -0.5, 0.86))
        val d = TiltCalibration.deserialize(c.serialize())
        assertEquals(1, d.frameCount(0)); assertEquals(0, d.frameCount(90))
        assertEquals(0, TiltCalibration.deserialize("garbage").frameCount(0))
    }

    /** Sabit sensor ofseti (0.6°) bilinen bir yerde kalibre edilir; baska yerlerde hata duser. */
    @Test fun calibrationRemovesConstantSensorOffset() {
        val rng = Random(555)
        val locator = ZenithLocator(solver)
        val offsetDir = unit(doubleArrayOf(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()))
        val offset = DoubleArray(3) { offsetDir[it] * Math.toRadians(0.6) }
        fun measured(upTrue: DoubleArray, handSigmaDeg: Double): DoubleArray {
            val n = DoubleArray(3) { Math.toRadians(handSigmaDeg) * rng.nextGaussian() }
            return unit(DoubleArray(3) { upTrue[it] + offset[it] + n[it] })
        }
        fun frame(lat: Double, lon: Double): Triple<List<Star>, Long, DoubleArray> {
            val day0 = 1_795_000_000_000L + (rng.nextDouble() * 365 * 86400e3).toLong()
            val epoch = day0 - day0 % 86_400_000L + ((22.0 + rng.nextDouble() * 4 - lon / 15.0) * 3_600_000).toLong()
            val pitch = 58.0 + rng.nextDouble() * 14; val roll = (rng.nextDouble() - 0.5) * 4
            val r = sceneRotation(lat, lon, epoch, pitch, roll, rng.nextDouble() * 2 * PI)
            return Triple(synth(rng, r, 66.0, 4000, 3000), epoch, ZenithFix.upFromTilt(pitch, roll))
        }
        // kalibrasyon: bilinen yer (Ankara), 6 kare, el titremesi 0.1°
        val cal = TiltCalibration()
        repeat(6) {
            val (stars, epoch, upTrue) = frame(39.93, 32.86)
            val trueUp = locator.trueUpFromKnownSite(stars, 4000, 3000, 66.0, 39.93, 32.86, epoch)!!
            assertTrue(cal.addFrame(0, measured(upTrue, 0.1), trueUp))
        }
        println("KALIBRASYON: ${cal.frameCount(0)} kare, kalan yayilma ${"%.3f".format(cal.residualDeg(0))}°")
        for (hand in listOf(0.03, 0.1)) {
            val before = ArrayList<Double>(); val after = ArrayList<Double>()
            repeat(40) {
                val lat = Math.toDegrees(asin(rng.nextDouble() * 2 - 1)); val lon = rng.nextDouble() * 360 - 180
                val (stars, epoch, upTrue) = frame(lat, lon)
                val upMeas = measured(upTrue, hand)
                fun err(up: DoubleArray): Double? {
                    val o = locator.locate(stars, 4000, 3000, 66.0, up, epoch, 0.1, 1.0, 5000)
                    return if (o.ok) ZenithFix.greatCircleKm(lat, lon, o.latitudeDeg, o.longitudeDeg) else null
                }
                val b = err(upMeas); val a = err(cal.correct(0, upMeas))
                if (b != null && a != null) { before.add(b); after.add(a) }
            }
            before.sort(); after.sort()
            fun m(l: List<Double>, q: Double) = l[min(l.size - 1, (q * l.size).toInt())]
            println("KAL. SONRASI (el %.2f°): kalibrasyonsuz medyan %.1f km %%95 %.1f | kalibreli medyan %.1f km %%68 %.1f %%95 %.1f (n=%d)"
                .format(hand, m(before, .5), m(before, .95), m(after, .5), m(after, .68), m(after, .95), after.size))
            assertTrue("kalibrasyon iyilestirmedi", m(after, .5) < 0.4 * m(before, .5))
        }
    }

    /** Atmosfer kirilmasi simule edilir; duzeltme ac/kapa karsilastirmasi (sensor hatasi yok). */
    @Test fun refractionCorrectionReducesError() {
        val rng = Random(31)
        val locator = ZenithLocator(solver)
        val off = ArrayList<Double>(); val on = ArrayList<Double>()
        repeat(60) {
            val lat = Math.toDegrees(asin(rng.nextDouble() * 2 - 1)); val lon = rng.nextDouble() * 360 - 180
            val day0 = 1_795_000_000_000L + (rng.nextDouble() * 365 * 86400e3).toLong()
            val epoch = day0 - day0 % 86_400_000L + ((22.0 + rng.nextDouble() * 4 - lon / 15.0) * 3_600_000).toLong()
            val pitch = 55.0 + rng.nextDouble() * 20; val roll = (rng.nextDouble() - .5) * 4
            val r = sceneRotation(lat, lon, epoch, pitch, roll, rng.nextDouble() * 2 * PI)
            val z = ZenithFix.zenithIcrs(lat, lon, epoch)
            val stars = synth(rng, r, 66.0, 4000, 3000, zenith = z)
            val up = ZenithFix.upFromTilt(pitch, roll)
            for (flag in listOf(false, true)) {
                val o = locator.locate(stars, 4000, 3000, 66.0, up, epoch, 0.0, 0.0, 5000, correctRefraction = flag)
                if (!o.ok) return@repeat
                (if (flag) on else off).add(ZenithFix.greatCircleKm(lat, lon, o.latitudeDeg, o.longitudeDeg))
            }
        }
        off.sort(); on.sort()
        println("KIRILMA: duzeltmesiz medyan %.2f km %%95 %.2f | duzeltmeli medyan %.2f km %%95 %.2f (n=%d)".format(off[off.size / 2], off[(off.size * .95).toInt().coerceAtMost(off.size - 1)], on[on.size / 2], on[(on.size * .95).toInt().coerceAtMost(on.size - 1)], on.size))
        assertTrue(on[on.size / 2] < off[off.size / 2])
    }

    @Test fun burstAverageBeatsSingleFrame() {
        val rng = Random(77)
        val locator = ZenithLocator(solver)
        val single = ArrayList<Double>(); val burst = ArrayList<Double>()
        repeat(30) {
            val lat = Math.toDegrees(asin(rng.nextDouble() * 2 - 1)); val lon = rng.nextDouble() * 360 - 180
            val day0 = 1_795_000_000_000L + (rng.nextDouble() * 365 * 86400e3).toLong()
            val epoch = day0 - day0 % 86_400_000L + ((22.0 + rng.nextDouble() * 4 - lon / 15.0) * 3_600_000).toLong()
            val pitch = 58.0 + rng.nextDouble() * 14; val roll = (rng.nextDouble() - .5) * 4
            val r = sceneRotation(lat, lon, epoch, pitch, roll, rng.nextDouble() * 2 * PI)
            val z = ZenithFix.zenithIcrs(lat, lon, epoch)
            val up = ZenithFix.upFromTilt(pitch, roll)
            val avg = BurstAverager()
            repeat(5) { k ->
                val stars = synth(rng, r, 66.0, 4000, 3000, zenith = z)
                val noisy = unit(DoubleArray(3) { up[it] + Math.toRadians(0.1) * rng.nextGaussian() })
                val o = locator.locate(stars, 4000, 3000, 66.0, noisy, epoch + k * 2000L, 0.1, 1.0, 5000)
                if (o.ok) {
                    avg.add(o.latitudeDeg, o.longitudeDeg, 0.1, o.plate!!.rmsDeg)
                    if (k == 0) single.add(ZenithFix.greatCircleKm(lat, lon, o.latitudeDeg, o.longitudeDeg))
                }
            }
            // kareler farkli zamanda ayni sabit gokyuzu: konumlari ayni gercek yere gore karsilastir (dakikalik fark ihmal)
            avg.result(0.0, 1.0)?.let { burst.add(ZenithFix.greatCircleKm(lat, lon, it.latitudeDeg, it.longitudeDeg)) }
        }
        single.sort(); burst.sort()
        println("SERI: tek kare medyan %.1f km | 5 kare ortalama medyan %.1f km (n=%d)".format(single[single.size / 2], burst[burst.size / 2], burst.size))
        assertTrue(burst[burst.size / 2] < 0.7 * single[single.size / 2])
    }

    @Test fun burstRejectsOutlier() {
        val a = BurstAverager()
        a.add(41.0, 29.0, 0.1); a.add(41.02, 29.01, 0.1); a.add(40.99, 28.99, 0.1); a.add(45.0, 35.0, 0.1)
        val r = a.result(0.0, 1.0)!!
        assertEquals(3, r.framesUsed); assertEquals(41.0, r.latitudeDeg, 0.05)
    }
}
