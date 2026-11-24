package com.kutup.navigasyon

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.*

class SkyPlateSolverTest {
    companion object {
        val catalog: StarCatalog by lazy {
            val f = listOf("src/main/assets/star_catalog.csv", "../src/main/assets/star_catalog.csv").map(::File).first { it.exists() }
            f.inputStream().use { StarCatalog.parse(it) }
        }
        val solver by lazy { SkyPlateSolver(catalog) }

        fun randomRotation(rng: Random): Array<DoubleArray> {
            // rastgele donus: Gram-Schmidt
            var a = doubleArrayOf(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()); a = unit(a)
            var b = doubleArrayOf(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian())
            val d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
            b = unit(doubleArrayOf(b[0] - d * a[0], b[1] - d * a[1], b[2] - d * a[2]))
            val c = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
            // sutunlar = kamera eksenlerinin gokyuzu karsiligi (x,y,z=a x b => sag-el)
            return arrayOf(doubleArrayOf(a[0], b[0], c[0]), doubleArrayOf(a[1], b[1], c[1]), doubleArrayOf(a[2], b[2], c[2]))
        }
        fun unit(v: DoubleArray): DoubleArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) }

        fun rotAngleDeg(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
            // a^T b izi
            var tr = 0.0
            for (i in 0 until 3) for (j in 0 until 3) tr += a[j][i] * b[j][i]
            return Math.toDegrees(acos(((tr - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
        }

        /** Sentetik kare: katalogdaki yildizlari mercege yansit; gurultu, eksik yildiz, sahte tespit. */
        fun synth(rot: Array<DoubleArray>, f: Double, w: Int, h: Int, limitMag: Double, rng: Random,
                  noisePx: Double = 0.7, dropFrac: Double = 0.2, falseCount: Int = 3): List<Star> {
            val out = ArrayList<Star>()
            for (i in 0 until catalog.countUpTo(limitMag)) {
                val c = doubleArrayOf(catalog.xyz[3 * i], catalog.xyz[3 * i + 1], catalog.xyz[3 * i + 2])
                // cam = R^T cel
                val x = rot[0][0] * c[0] + rot[1][0] * c[1] + rot[2][0] * c[2]
                val y = rot[0][1] * c[0] + rot[1][1] * c[1] + rot[2][1] * c[2]
                val z = rot[0][2] * c[0] + rot[1][2] * c[1] + rot[2][2] * c[2]
                if (z <= 0.05) continue
                val px = f * x / z + w / 2.0 + rng.nextGaussian() * noisePx
                val py = f * y / z + h / 2.0 + rng.nextGaussian() * noisePx
                if (px < 0 || py < 0 || px >= w || py >= h) continue
                if (rng.nextDouble() < dropFrac) continue
                out.add(Star(px.toFloat(), py.toFloat(), (10.0 - catalog.mag[i]).toFloat()))
            }
            repeat(falseCount) { out.add(Star((rng.nextDouble() * w).toFloat(), (rng.nextDouble() * h).toFloat(), (3.0 + rng.nextDouble() * 3).toFloat())) }
            return out
        }
    }

    @Test fun catalogLoads() {
        assertTrue(catalog.size > 2800)
        assertEquals("Sirius", -1.44, catalog.mag[0], 1e-9)
    }

    @Test fun wahbaRecoversRotation() {
        val rng = Random(5)
        repeat(20) {
            val r = randomRotation(rng)
            val cam = Array(5) { unit(doubleArrayOf(rng.nextGaussian() * 0.2, rng.nextGaussian() * 0.2, 1.0)) }
            val cel = Array(5) { k -> doubleArrayOf(
                r[0][0] * cam[k][0] + r[0][1] * cam[k][1] + r[0][2] * cam[k][2],
                r[1][0] * cam[k][0] + r[1][1] * cam[k][1] + r[1][2] * cam[k][2],
                r[2][0] * cam[k][0] + r[2][1] * cam[k][1] + r[2][2] * cam[k][2]) }
            val est = SkyPlateSolver.wahba(cam, cel, DoubleArray(5) { 1.0 })!!
            assertTrue(rotAngleDeg(r, est) < 1e-6)
        }
    }

    @Test fun blindSolveManyRandomSkies() {
        val rng = Random(11)
        val w = 4000; val h = 3000
        val fTrue0 = (w / 2.0) / tan(Math.toRadians(70.0) / 2)   // 70 deg uzun kenar
        var tried = 0; var solved = 0; var good = 0
        val errs = ArrayList<Double>()
        val t0 = System.nanoTime()
        repeat(40) {
            val rot = randomRotation(rng)
            val f = fTrue0 * (0.95 + 0.1 * rng.nextDouble())
            val stars = synth(rot, f, w, h, 4.6, rng)
            if (stars.size < 12) return@repeat
            tried++
            // yaklasik FOV tahmini %12 yanlis
            val guess = 70.0 * (0.88 + 0.24 * rng.nextDouble())
            val sol = solver.solve(stars, w, h, guess)
            if (sol != null) {
                solved++
                val e = rotAngleDeg(rot, sol.celFromCam); errs.add(e)
                if (e < 0.1) good++
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        println("tried=$tried solved=$solved good=$good errs(max)=${errs.maxOrNull()} median=${errs.sorted()[errs.size / 2]} time=${"%.1f".format(secs)}s")
        assertTrue("cozum orani dusuk: $solved/$tried", solved >= tried * 0.9)
        assertTrue("yanlis cozum var", good == solved)
        assertTrue("dogruluk kotu (max ${errs.maxOrNull()})", errs.maxOrNull()!! < 0.1)
    }

    @Test fun noStarsNoFalsePositive() {
        val rng = Random(3)
        val stars = (0 until 30).map { Star((rng.nextDouble() * 4000).toFloat(), (rng.nextDouble() * 3000).toFloat(), (rng.nextDouble() * 5 + 1).toFloat()) }
        assertNull(solver.solve(stars, 4000, 3000, 70.0))
    }

    @Test fun endToEndPositionFromSyntheticFrame() {
        val rng = Random(21)
        val w = 4000; val h = 3000
        val f = (w / 2.0) / tan(Math.toRadians(70.0) / 2)
        var ok = 0; var tried = 0
        val epoch = 1795000000000L
        repeat(15) {
            val rot = randomRotation(rng)
            val pitch = 55 + 20 * rng.nextDouble(); val roll = -3 + 6 * rng.nextDouble()
            val up = ZenithFix.upFromTilt(pitch, roll)
            val truth = ZenithFix.solvePosition(rot, up, epoch)
            val stars = synth(rot, f, w, h, 4.6, rng)
            if (stars.size < 12) return@repeat
            tried++
            val sol = solver.solve(stars, w, h, 66.0) ?: return@repeat
            val est = ZenithFix.solvePosition(sol.celFromCam, up, epoch)
            val km = ZenithFix.greatCircleKm(truth.latitudeDeg, truth.longitudeDeg, est.latitudeDeg, est.longitudeDeg)
            if (km < 10.0) ok++
        }
        println("endToEnd ok=$ok tried=$tried")
        assertTrue(ok >= tried - 1)
    }
}
