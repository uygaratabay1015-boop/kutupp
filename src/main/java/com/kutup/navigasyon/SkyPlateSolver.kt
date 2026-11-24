package com.kutup.navigasyon

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** Plate solve sonucu: kameradan gokyuzune (ICRS/J2000) donus matrisi ve kalite olcutleri. */
data class PlateSolution(
    /** cel = celFromCam * cam; kamera: x sag, y asagi, z bakis (OpenCV). */
    val celFromCam: Array<DoubleArray>,
    val focalPx: Double,
    val fovLongSideDeg: Double,
    val matchedStars: Int,
    val detectedStars: Int,
    val rmsArcmin: Double,
    val raDeg: Double,
    val decDeg: Double
)

/**
 * Tamamen cihaz ustunde calisan "kayip-uzayda" (on bilgisiz) yildiz plakasi cozucu.
 * Yontem: uc yildizli ucgenlerin kenar oranlari (olcekten bagimsiz) ile katalog ucgen dizinini eslestir,
 * donusu Wahba/Davenport ile hesapla, tum yildizlarla dogrula, odak uzakligini ve donusu iyilestir.
 * Gorus acisi yalnizca yaklasik gereklidir (+-%25); cozum odak uzakligini da bulur.
 */
class SkyPlateSolver(private val catalog: StarCatalog, private val config: Config = Config()) {

    data class Config(
        val indexMag: Double = 4.0,
        val verifyMag: Double = 5.5,
        val maxIndexStars: Int = 1000,
        val maxSideDeg: Double = 50.0,
        val minSideDeg: Double = 3.0,
        val ratioCell: Double = 0.02,
        val sideTolDeg: Double = 0.6,
        val topStars: Int = 10,
        val minTopMatches: Int = 6,
        val minTotalMatches: Int = 7,
        val maxRmsArcmin: Double = 15.0,
        val focalLow: Double = 0.75,
        val focalHigh: Double = 1.30,
        val maxHypothesesChecked: Int = 400000
    )

    // ---- dizin (tembel olusturulur)
    private var built = false
    private lateinit var idxStar: IntArray        // dizindeki yildiz -> katalog numarasi
    private lateinit var tripleKeyIdx: LongArray  // (key shl 32) | tripleNo, siralanmis
    private lateinit var triplePacked: IntArray   // 3 x 10 bit yildiz (kanonik sira: kucuk, orta, buyuk kenarin karsisindaki kose)
    private lateinit var tripleS3: FloatArray     // en uzun kenar (derece)

    @Synchronized
    private fun ensureIndex() {
        if (built) return
        val n = min(catalog.countUpTo(config.indexMag), config.maxIndexStars)
        idxStar = IntArray(n) { it }
        val cosMax = kotlin.math.cos(Math.toRadians(config.maxSideDeg))
        val x = catalog.xyz
        val dot = Array(n) { i -> FloatArray(n) { j ->
            (x[3 * i] * x[3 * j] + x[3 * i + 1] * x[3 * j + 1] + x[3 * i + 2] * x[3 * j + 2]).toFloat() } }
        val keys = ArrayList<Long>(); val packed = ArrayList<Int>(); val s3l = ArrayList<Float>()
        val nb = IntArray(n)
        for (i in 0 until n) {
            var c = 0
            for (j in i + 1 until n) if (dot[i][j] >= cosMax) nb[c++] = j
            for (a in 0 until c) for (b in a + 1 until c) {
                val j = nb[a]; val k = nb[b]
                if (dot[j][k] < cosMax) continue
                val dij = angDeg(dot[i][j].toDouble()); val dik = angDeg(dot[i][k].toDouble()); val djk = angDeg(dot[j][k].toDouble())
                val t = canonical(i, j, k, djk, dik, dij) ?: continue   // (vertex order, sides)
                val r1 = t.s1 / t.s3; val r2 = t.s2 / t.s3
                val key = ratioKey(r1, r2)
                keys.add((key.toLong() shl 32) or keys.size.toLong())
                packed.add((t.v0 shl 20) or (t.v1 shl 10) or t.v2)
                s3l.add(t.s3.toFloat())
            }
        }
        tripleKeyIdx = keys.toLongArray().also { it.sort() }
        triplePacked = packed.toIntArray()
        tripleS3 = s3l.toFloatArray()
        built = true
    }

    private class Canon(val v0: Int, val v1: Int, val v2: Int, val s1: Double, val s2: Double, val s3: Double)

    /** Kose a,b,c; karsisindaki kenarlar sa,sb,sc. Kucuk->buyuk kenara gore siralar; dejenere ise null. */
    private fun canonical(a: Int, b: Int, c: Int, sa: Double, sb: Double, sc: Double): Canon? {
        val vs = intArrayOf(a, b, c); val ss = doubleArrayOf(sa, sb, sc)
        for (p in 0 until 2) for (q in 0 until 2 - p) if (ss[q] > ss[q + 1]) {
            val ts = ss[q]; ss[q] = ss[q + 1]; ss[q + 1] = ts
            val tv = vs[q]; vs[q] = vs[q + 1]; vs[q + 1] = tv
        }
        if (ss[2] < config.minSideDeg || ss[0] < 0.5) return null
        if ((ss[0] + ss[1] - ss[2]) / ss[2] < 0.08) return null   // neredeyse dogrusal
        return Canon(vs[0], vs[1], vs[2], ss[0], ss[1], ss[2])
    }

    private fun ratioKey(r1: Double, r2: Double): Int {
        val kx = (r1 / config.ratioCell).toInt(); val ky = (r2 / config.ratioCell).toInt()
        return kx * 1024 + ky
    }

    private fun angDeg(dot: Double): Double = Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))

    // ---- cozum

    private class Obs(val x: Double, val y: Double, val b: Double)

    fun solve(stars: List<Star>, width: Int, height: Int, fovLongSideDegGuess: Double): PlateSolution? {
        if (stars.size < 5 || width <= 0 || height <= 0) return null
        ensureIndex()
        val obs = stars.sortedByDescending { it.brightness }.take(60).map { Obs(it.x.toDouble(), it.y.toDouble(), it.brightness.toDouble()) }
        val cx = width / 2.0; val cy = height / 2.0
        val longPx = max(width, height).toDouble()
        val f0 = (longPx / 2.0) / tan(Math.toRadians(fovLongSideDegGuess.coerceIn(10.0, 170.0)) / 2.0)
        val fLo = f0 * config.focalLow; val fHi = f0 * config.focalHigh
        val nTop = min(config.topStars, obs.size)

        // Parlak yildizlarin kamera vektorleri f0'da (oran hesabi icin)
        var best: Fit? = null
        var checked = 0
        val tol = config.sideTolDeg
        search@ for (a in 0 until nTop) for (b in a + 1 until nTop) for (c in b + 1 until nTop) {
            val vA = camVec(obs[a], cx, cy, f0); val vB = camVec(obs[b], cx, cy, f0); val vC = camVec(obs[c], cx, cy, f0)
            val sa = angOf(vB, vC); val sb = angOf(vA, vC); val sc = angOf(vA, vB)
            val can = canonicalObs(a, b, c, sa, sb, sc) ?: continue
            val r1 = can.s1 / can.s3; val r2 = can.s2 / can.s3
            val kx0 = (r1 / config.ratioCell).toInt(); val ky0 = (r2 / config.ratioCell).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                val key = (kx0 + dx) * 1024 + (ky0 + dy)
                if (key < 0) continue
                var lo = lowerBound(key.toLong() shl 32)
                while (lo < tripleKeyIdx.size && (tripleKeyIdx[lo] ushr 32).toInt() == key) {
                    val tNo = (tripleKeyIdx[lo] and 0xFFFFFFFFL).toInt(); lo++
                    if (++checked > config.maxHypothesesChecked) break@search
                    val s3cat = tripleS3[tNo].toDouble()
                    val pk = triplePacked[tNo]
                    val cv = intArrayOf((pk shr 20) and 1023, (pk shr 10) and 1023, pk and 1023)
                    val perms = ArrayList<IntArray>(3)
                    perms.add(intArrayOf(can.v0, can.v1, can.v2))
                    // Esit/yakin kenarlarda kanonik sira belirsiz: komsu koseleri de dene
                    if (abs(can.s1 - can.s2) / can.s2 < 0.05) perms.add(intArrayOf(can.v1, can.v0, can.v2))
                    if (abs(can.s2 - can.s3) / can.s3 < 0.05) perms.add(intArrayOf(can.v0, can.v2, can.v1))
                    for (ov in perms) {
                        val fit = tryHypothesis(obs, ov, cv, s3cat, cx, cy, fLo, fHi, nTop, tol)
                        if (fit != null && (best == null || fit.better(best))) {
                            best = fit
                            if (fit.total >= 14 && fit.rmsArcmin < 6.0) break@search
                        }
                    }
                }
            }
        }
        val fit = best ?: return null
        return finish(obs, fit, cx, cy, width, height)
    }

    private class CanonObs(val v0: Int, val v1: Int, val v2: Int, val s1: Double, val s2: Double, val s3: Double)

    private fun canonicalObs(a: Int, b: Int, c: Int, sa: Double, sb: Double, sc: Double): CanonObs? {
        val vs = intArrayOf(a, b, c); val ss = doubleArrayOf(sa, sb, sc)
        for (p in 0 until 2) for (q in 0 until 2 - p) if (ss[q] > ss[q + 1]) {
            val ts = ss[q]; ss[q] = ss[q + 1]; ss[q + 1] = ts
            val tv = vs[q]; vs[q] = vs[q + 1]; vs[q + 1] = tv
        }
        if (ss[2] < 0.5 || (ss[0] + ss[1] - ss[2]) / ss[2] < 0.08) return null
        return CanonObs(vs[0], vs[1], vs[2], ss[0], ss[1], ss[2])
    }

    private fun lowerBound(target: Long): Int {
        var lo = 0; var hi = tripleKeyIdx.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (tripleKeyIdx[mid] < target) lo = mid + 1 else hi = mid }
        return lo
    }

    private class Fit(val rot: Array<DoubleArray>, val f: Double, val total: Int, val rmsArcmin: Double) {
        fun better(o: Fit): Boolean = total > o.total || (total == o.total && rmsArcmin < o.rmsArcmin)
    }

    private fun tryHypothesis(
        obs: List<Obs>, ov: IntArray, cv: IntArray, s3cat: Double,
        cx: Double, cy: Double, fLo: Double, fHi: Double, nTop: Int, tolDeg: Double
    ): Fit? {
        val x = catalog.xyz
        // En uzun kenar (s3), kucuk ve orta kenarlarin karsisindaki koseler ov[0]-ov[1] arasindadir.
        val a = obs[ov[0]]; val b = obs[ov[1]]
        val angLoF = angPix(a, b, cx, cy, fHi); val angHiF = angPix(a, b, cx, cy, fLo)
        if (s3cat < angLoF - tolDeg || s3cat > angHiF + tolDeg) return null
        var lo = fLo; var hi = fHi
        for (it in 0 until 40) { val mid = 0.5 * (lo + hi); if (angPix(a, b, cx, cy, mid) > s3cat) lo = mid else hi = mid }
        val f = 0.5 * (lo + hi)
        // Odak bu kenardan bulundu; tum kenarlarin katalogla uyustugunu dogrula:
        for (i in 0 until 3) for (j in i + 1 until 3) {
            val ao = angPix(obs[ov[i]], obs[ov[j]], cx, cy, f)
            val ac = angDeg(dot3(x, cv[i], cv[j]))
            if (abs(ao - ac) > tolDeg) return null
        }
        val camV = Array(3) { camVec(obs[ov[it]], cx, cy, f) }
        val celV = Array(3) { doubleArrayOf(x[3 * cv[it]], x[3 * cv[it] + 1], x[3 * cv[it] + 2]) }
        val rot = wahba(camV, celV, doubleArrayOf(1.0, 1.0, 1.0)) ?: return null

        // Hizli dogrulama: en parlak yildizlar dizin yildizlariyla
        val tolCos = kotlin.math.cos(Math.toRadians(tolDeg))
        var topMatch = 0
        for (k in 0 until nTop) {
            val c = mulVec(rot, camVec(obs[k], cx, cy, f))
            if (nearestDot(c, idxStar.size) >= tolCos) topMatch++
        }
        if (topMatch < config.minTopMatches) return null
        return refine(obs, rot, f, cx, cy)
    }

    private fun nearestDot(c: DoubleArray, limit: Int): Double {
        val x = catalog.xyz; var best = -1.0
        for (i in 0 until limit) {
            val d = x[3 * i] * c[0] + x[3 * i + 1] * c[1] + x[3 * i + 2] * c[2]
            if (d > best) best = d
        }
        return best
    }

    private val verifyCount: Int by lazy { catalog.countUpTo(config.verifyMag) }

    /** Esle -> Wahba -> odak uzakligi aramasi; tolerans daralir. Dogrulama sayisi ve rms dondurur. */
    private fun refine(obs: List<Obs>, rot0: Array<DoubleArray>, f0: Double, cx: Double, cy: Double): Fit? {
        var rot = rot0; var f = f0
        var matches = 0; var rms = 0.0
        for (tolDeg in doubleArrayOf(0.5, 0.3, 0.15, 0.1)) {
            val pairs = matchPairs(obs, rot, f, cx, cy, tolDeg)
            matches = pairs.size
            if (matches < 3) return null
            val camV = Array(matches) { camVec(obs[pairs[it].first], cx, cy, f) }
            val celV = Array(matches) { doubleArrayOf(catalog.xyz[3 * pairs[it].second], catalog.xyz[3 * pairs[it].second + 1], catalog.xyz[3 * pairs[it].second + 2]) }
            rot = wahba(camV, celV, DoubleArray(matches) { 1.0 }) ?: return null
            // odak uzakligini sabit eslesmelerle ince ayarla (altin kesit)
            var lo = f * 0.97; var hi = f * 1.03
            val gr = 0.6180339887
            fun cost(ff: Double): Double {
                var s = 0.0
                for ((oi, ci) in pairs) {
                    val c = mulVec(rot, camVec(obs[oi], cx, cy, ff))
                    val d = c[0] * catalog.xyz[3 * ci] + c[1] * catalog.xyz[3 * ci + 1] + c[2] * catalog.xyz[3 * ci + 2]
                    val e = acos(d.coerceIn(-1.0, 1.0)); s += e * e
                }
                return s
            }
            var m1 = hi - gr * (hi - lo); var m2 = lo + gr * (hi - lo)
            var c1 = cost(m1); var c2 = cost(m2)
            for (it in 0 until 30) {
                if (c1 < c2) { hi = m2; m2 = m1; c2 = c1; m1 = hi - gr * (hi - lo); c1 = cost(m1) }
                else { lo = m1; m1 = m2; c1 = c2; m2 = lo + gr * (hi - lo); c2 = cost(m2) }
            }
            f = 0.5 * (lo + hi)
            // Odak degisince donusu bir kez daha uydur
            val camV2 = Array(matches) { camVec(obs[pairs[it].first], cx, cy, f) }
            rot = wahba(camV2, celV, DoubleArray(matches) { 1.0 }) ?: return null
        }
        val pairs = matchPairs(obs, rot, f, cx, cy, 0.1)
        if (pairs.size < config.minTotalMatches) return null
        var s = 0.0
        for ((oi, ci) in pairs) {
            val c = mulVec(rot, camVec(obs[oi], cx, cy, f))
            val d = c[0] * catalog.xyz[3 * ci] + c[1] * catalog.xyz[3 * ci + 1] + c[2] * catalog.xyz[3 * ci + 2]
            val e = Math.toDegrees(acos(d.coerceIn(-1.0, 1.0))) * 60.0; s += e * e
        }
        rms = sqrt(s / pairs.size)
        if (rms > config.maxRmsArcmin) return null
        return Fit(rot, f, pairs.size, rms)
    }

    /** Her gozlenen yildiz icin en yakin (tolerans icinde ve benzersiz) katalog yildizi. */
    private fun matchPairs(obs: List<Obs>, rot: Array<DoubleArray>, f: Double, cx: Double, cy: Double, tolDeg: Double): List<Pair<Int, Int>> {
        val tolCos = kotlin.math.cos(Math.toRadians(tolDeg))
        val x = catalog.xyz
        val usedCat = HashSet<Int>(); val out = ArrayList<Pair<Int, Int>>()
        val cand = ArrayList<Triple<Double, Int, Int>>()
        for (oi in obs.indices) {
            val c = mulVec(rot, camVec(obs[oi], cx, cy, f))
            var bestD = -1.0; var bestI = -1
            for (i in 0 until verifyCount) {
                val d = x[3 * i] * c[0] + x[3 * i + 1] * c[1] + x[3 * i + 2] * c[2]
                if (d > bestD) { bestD = d; bestI = i }
            }
            if (bestD >= tolCos) cand.add(Triple(bestD, oi, bestI))
        }
        cand.sortByDescending { it.first }
        for ((_, oi, ci) in cand) if (usedCat.add(ci)) out.add(Pair(oi, ci))
        return out
    }

    private fun finish(obs: List<Obs>, fit: Fit, cx: Double, cy: Double, w: Int, h: Int): PlateSolution {
        val bore = mulVec(fit.rot, doubleArrayOf(0.0, 0.0, 1.0))
        val ra = Math.toDegrees(atan2(bore[1], bore[0])).let { if (it < 0) it + 360.0 else it }
        val dec = Math.toDegrees(kotlin.math.asin(bore[2].coerceIn(-1.0, 1.0)))
        val longPx = max(w, h).toDouble()
        val fov = Math.toDegrees(2.0 * atan(longPx / 2.0 / fit.f))
        return PlateSolution(fit.rot, fit.f, fov, fit.total, obs.size, fit.rmsArcmin, ra, dec)
    }

    // ---- geometri yardimcilari

    private fun camVec(o: Obs, cx: Double, cy: Double, f: Double): DoubleArray {
        val x = (o.x - cx) / f; val y = (o.y - cy) / f
        val n = sqrt(x * x + y * y + 1.0)
        return doubleArrayOf(x / n, y / n, 1.0 / n)
    }

    private fun angOf(a: DoubleArray, b: DoubleArray): Double =
        angDeg(a[0] * b[0] + a[1] * b[1] + a[2] * b[2])

    private fun angPix(a: Obs, b: Obs, cx: Double, cy: Double, f: Double): Double =
        angOf(camVec(a, cx, cy, f), camVec(b, cx, cy, f))

    private fun dot3(x: DoubleArray, i: Int, j: Int): Double =
        x[3 * i] * x[3 * j] + x[3 * i + 1] * x[3 * j + 1] + x[3 * i + 2] * x[3 * j + 2]

    private fun mulVec(m: Array<DoubleArray>, v: DoubleArray) = doubleArrayOf(
        m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
        m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
        m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
    )

    companion object {
        /**
         * Wahba problemi (Davenport q-yontemi): cel_i ~ R * cam_i icin en iyi donus R.
         * Birim vektorler ve agirliklar; dejenere ise null.
         */
        fun wahba(cam: Array<DoubleArray>, cel: Array<DoubleArray>, w: DoubleArray): Array<DoubleArray>? {
            val b = Array(3) { DoubleArray(3) }
            for (k in cam.indices) for (i in 0 until 3) for (j in 0 until 3) b[i][j] += w[k] * cel[k][i] * cam[k][j]
            val s = Array(3) { i -> DoubleArray(3) { j -> b[i][j] + b[j][i] } }
            val sigma = b[0][0] + b[1][1] + b[2][2]
            val z = doubleArrayOf(b[1][2] - b[2][1], b[2][0] - b[0][2], b[0][1] - b[1][0])
            val k = Array(4) { DoubleArray(4) }
            for (i in 0 until 3) for (j in 0 until 3) k[i][j] = s[i][j] - (if (i == j) sigma else 0.0)
            for (i in 0 until 3) { k[i][3] = z[i]; k[3][i] = z[i] }
            k[3][3] = sigma
            val q = maxEigenvector(k) ?: return null
            val x = q[0]; val y = q[1]; val zq = q[2]; val ww = q[3]
            val n = sqrt(x * x + y * y + zq * zq + ww * ww)
            if (n < 1e-12) return null
            val qx = x / n; val qy = y / n; val qz = zq / n; val qw = ww / n
            // Davenport q, attitude A (cel = A cam) icin: aktif donusun devrigi degil, dogrudan A:
            return arrayOf(
                doubleArrayOf(1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy + qz * qw), 2 * (qx * qz - qy * qw)),
                doubleArrayOf(2 * (qx * qy - qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz + qx * qw)),
                doubleArrayOf(2 * (qx * qz + qy * qw), 2 * (qy * qz - qx * qw), 1 - 2 * (qx * qx + qy * qy))
            )
        }

        /** Simetrik 4x4 matrisin en buyuk ozdegerinin ozvektoru (Jacobi). */
        private fun maxEigenvector(m: Array<DoubleArray>): DoubleArray? {
            val n = 4
            val a = Array(n) { m[it].clone() }
            val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
            for (sweep in 0 until 60) {
                var off = 0.0
                for (i in 0 until n) for (j in i + 1 until n) off += a[i][j] * a[i][j]
                if (off < 1e-24) break
                for (p in 0 until n) for (q in p + 1 until n) {
                    if (abs(a[p][q]) < 1e-30) continue
                    val theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
                    val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0); val s = t * c
                    for (r in 0 until n) {
                        val arp = a[r][p]; val arq = a[r][q]
                        a[r][p] = c * arp - s * arq; a[r][q] = s * arp + c * arq
                    }
                    for (r in 0 until n) {
                        val apr = a[p][r]; val aqr = a[q][r]
                        a[p][r] = c * apr - s * aqr; a[q][r] = s * apr + c * aqr
                    }
                    for (r in 0 until n) {
                        val vrp = v[r][p]; val vrq = v[r][q]
                        v[r][p] = c * vrp - s * vrq; v[r][q] = s * vrp + c * vrq
                    }
                }
            }
            var bi = 0
            for (i in 1 until n) if (a[i][i] > a[bi][bi]) bi = i
            return DoubleArray(n) { v[it][bi] }
        }
    }
}
