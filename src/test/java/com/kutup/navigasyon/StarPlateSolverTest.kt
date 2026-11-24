package com.kutup.navigasyon

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.*

class StarPlateSolverTest {
    companion object {
        val catalog: StarCatalog by lazy { StarCatalog.load(File("src/main/assets/star_catalog_j2000.bin").inputStream()) }
        val solver: StarPlateSolver by lazy { StarPlateSolver(catalog) }
    }

    private fun randomRotation(rng: Random): Array<DoubleArray> {
        // rastgele birim kuaterniyon
        var q = DoubleArray(4) { rng.nextGaussian() }
        val n = sqrt(q.sumOf { it * it }); q = DoubleArray(4) { q[it] / n }
        val (x, y, z, w) = q.toList()
        return arrayOf(
            doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
            doubleArrayOf(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
            doubleArrayOf(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y))
        )
    }

    /** Sentetik kare: gercek R, gercek fov. Tespit siniri, dusme ve yalanci yildiz. */
    private fun synth(rng: Random, r: Array<DoubleArray>, fovDeg: Double, w: Int, h: Int, detMag: Float,
                      noisePx: Double, dropFrac: Double, falseCount: Int): List<Star> {
        val f = (max(w, h) / 2.0) / tan(Math.toRadians(fovDeg) / 2)
        val out = ArrayList<Star>()
        for (i in 0 until catalog.size) {
            if (catalog.mag[i] > detMag || rng.nextDouble() < dropFrac) continue
            val v = doubleArrayOf(catalog.vec[3 * i], catalog.vec[3 * i + 1], catalog.vec[3 * i + 2])
            // cam = R^T cel
            val cam = DoubleArray(3) { k -> r[0][k] * v[0] + r[1][k] * v[1] + r[2][k] * v[2] }
            if (cam[2] <= 0.05) continue
            val x = w / 2.0 + f * cam[0] / cam[2] + rng.nextGaussian() * noisePx
            val y = h / 2.0 + f * cam[1] / cam[2] + rng.nextGaussian() * noisePx
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            out.add(Star(x.toFloat(), y.toFloat(), (10.0.pow(-0.4 * catalog.mag[i])).toFloat()))
        }
        repeat(falseCount) { out.add(Star((rng.nextDouble() * w).toFloat(), (rng.nextDouble() * h).toFloat(), (rng.nextDouble() * 0.05).toFloat())) }
        return out
    }

    private fun angleDeg(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        // iki donusum arasindaki aci
        var tr = 0.0
        for (i in 0 until 3) for (j in 0 until 3) tr += a[i][j] * b[i][j]
        return Math.toDegrees(acos(((tr - 1) / 2).coerceIn(-1.0, 1.0)))
    }

    @Test fun catalogLoads() {
        assertTrue(catalog.size > 2500)
        assertEquals(-1.44, catalog.mag[0].toDouble(), 0.01) // Sirius
    }

    @Test fun solvesRandomSkiesWithFovError() {
        val rng = Random(42)
        val times = ArrayList<Double>(); var ok = 0; var total = 0; var worst = 0.0; val rms = ArrayList<Double>()
        for (trial in 0 until 16) {
            val r = randomRotation(rng)
            val fov = 50.0 + rng.nextDouble() * 30.0
            val w = 4000; val h = 3000
            val stars = synth(rng, r, fov, w, h, detMag = 4.8f, noisePx = 0.5, dropFrac = 0.2, falseCount = 6)
            if (stars.count { it.brightness > 0.06f } < 6) continue // bos gokyuzu bolgesi: test disi
            total++
            val guess = fov * (1.0 + (rng.nextDouble() - 0.5) * 0.08) // +-%4
            val t0 = System.nanoTime()
            val sol = solver.solve(stars, w, h, guess)
            times.add((System.nanoTime() - t0) / 1e6)
            if (sol != null) {
                val err = angleDeg(sol.celFromCam, r)
                if (err < 0.1) ok++
                worst = max(worst, err); rms.add(sol.rmsDeg)
                assertEquals(fov, sol.longSideFovDeg, fov * 0.01)
            }
        }
        println("sure ms: medyan ${times.sorted()[times.size/2].toInt()}, maks ${times.max().toInt()}")
        println("COZUM: $ok/$total basarili, en kotu aci hatasi ${"%.4f".format(worst)} derece, medyan rms ${"%.4f".format(rms.sorted()[rms.size / 2])}")
        assertTrue("basari orani dusuk: $ok/$total", ok >= total * 0.9)
        assertTrue("yanlis cozum", worst < 0.15)
    }

    @Test fun rejectsRandomNoise() {
        val rng = Random(7)
        val w = 4000; val h = 3000
        var wrong = 0
        repeat(3) {
            val stars = List(25) { Star((rng.nextDouble() * w).toFloat(), (rng.nextDouble() * h).toFloat(), rng.nextFloat()) }
            val t0 = System.nanoTime(); if (solver.solve(stars, w, h, 65.0, maxMillis = 6000) != null) wrong++
            println("gurultu cozumu ms: ${(System.nanoTime() - t0) / 1e6}")
        }
        assertEquals("gurultu yanlis cozulmemeli", 0, wrong)
    }

    /** Zor kosullar: az yildiz, yuksek centroid gurultusu, cok yalanci yildiz, FOV hatasi. Yanlis cozum olmamali. */
    @Test fun harshConditionsNeverWrong() {
        val rng = Random(2026)
        data class Case(val detMag: Float, val noise: Double, val drop: Double, val falseN: Int, val fovErr: Double)
        val cases = listOf(Case(4.2f, 0.5, 0.3, 5, 0.04), Case(4.0f, 1.5, 0.3, 15, 0.05), Case(3.8f, 1.0, 0.2, 10, 0.05), Case(5.2f, 1.0, 0.4, 30, 0.04))
        for (c in cases) {
            var ok = 0; var wrong = 0; var none = 0; var n = 0
            for (trial in 0 until 12) {
                val r = randomRotation(rng); val fov = 55.0 + rng.nextDouble() * 20
                val stars = synth(rng, r, fov, 4000, 3000, c.detMag, c.noise, c.drop, c.falseN)
                n++
                val sol = solver.solve(stars, 4000, 3000, fov * (1 + (rng.nextDouble() - 0.5) * 2 * c.fovErr), maxMillis = 4000)
                if (sol == null) none++ else if (angleDeg(sol.celFromCam, r) < 0.1) ok++ else wrong++
            }
            println("ZOR $c -> dogru $ok, yanlis $wrong, cozumsuz $none / $n")
            assertEquals("yanlis cozum: $c", 0, wrong)
        }
    }

    /** Uctan uca: bilinen konum+saat -> kamera yonelimi -> sentetik yildizlar -> cozucu -> ZenithFix -> konum. */
    @Test fun endToEndLocation() {
        val rng = Random(99)
        val locator = ZenithLocator(solver)
        var worst = 0.0; var n = 0
        repeat(12) {
            val r = randomRotation(rng)
            val pitch = 50.0 + rng.nextDouble() * 25; val roll = (rng.nextDouble() - 0.5) * 6
            val up = ZenithFix.upFromTilt(pitch, roll)
            val epoch = 1_795_000_000_000L + (rng.nextDouble() * 3e7).toLong()
            val truth = ZenithFix.solvePosition(r, up, epoch)
            val fov = 62.0 + rng.nextDouble() * 10
            val stars = synth(rng, r, fov, 4000, 3000, 4.6f, 0.6, 0.25, 8)
            if (stars.count { it.brightness > 0.06f } < 9) return@repeat
            val out = locator.locate(stars, 4000, 3000, fov * 1.03, pitch, roll, epoch, 0.3, 1.0)
            n++
            assertTrue(out.message, out.ok)
            val km = ZenithFix.greatCircleKm(truth.latitudeDeg, truth.longitudeDeg, out.latitudeDeg, out.longitudeDeg)
            worst = max(worst, km)
        }
        println("UCTAN UCA: $n kare, en kotu konum hatasi ${"%.2f".format(worst)} km (sensor hatasi hariç)")
        assertTrue(n >= 6); assertTrue("fazla hata $worst km", worst < 3.0)
    }

    @Test fun errorEstimateMatchesBudget() {
        val (e68, e95) = ZenithLocator.estimateErrorKm(41.0, 0.3, 1.0, 0.02)
        assertTrue("e68=$e68", e68 in 40.0..70.0)
        assertTrue(e95 > e68)
    }
}
