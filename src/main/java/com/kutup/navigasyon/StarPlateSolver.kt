package com.kutup.navigasyon

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** J2000 yildiz katalogu (assets/star_catalog_j2000.bin, parlakliga gore sirali). */
class StarCatalog(val raDeg: DoubleArray, val decDeg: DoubleArray, val mag: FloatArray) {
    val size: Int get() = mag.size
    /** ICRS birim vektorleri (x,y,z ardisik). */
    val vec: DoubleArray = DoubleArray(size * 3).also { v ->
        for (i in 0 until size) {
            val ra = Math.toRadians(raDeg[i]); val de = Math.toRadians(decDeg[i])
            v[3 * i] = cos(de) * cos(ra); v[3 * i + 1] = cos(de) * sin(ra); v[3 * i + 2] = sin(de)
        }
    }

    companion object {
        /** Bicim: int32 N, N x (float32 ra, float32 dec, float32 mag), big endian. */
        fun load(input: InputStream): StarCatalog {
            DataInputStream(input.buffered()).use { s ->
                val n = s.readInt()
                require(n in 1..200_000) { "Katalog bozuk" }
                val ra = DoubleArray(n); val de = DoubleArray(n); val mg = FloatArray(n)
                for (i in 0 until n) { ra[i] = s.readFloat().toDouble(); de[i] = s.readFloat().toDouble(); mg[i] = s.readFloat() }
                return StarCatalog(ra, de, mg)
            }
        }
    }
}

data class PlateSolution(
    /** Kamera (x sag, y asagi, z bakis) -> ICRS donusumu. */
    val celFromCam: Array<DoubleArray>,
    /** Uzun kenar gorus acisi (derece), rafine edilmis. */
    val longSideFovDeg: Double,
    val matchedStars: Int,
    val usedStars: Int,
    val rmsDeg: Double,
    val boresightRaDeg: Double,
    val boresightDecDeg: Double,
    /** Radyal distorsiyon katsayisi (0 = kullanilmadi). */
    val k1: Double = 0.0
)

/**
 * Cihaz ici (internetsiz) "lost in the sky" plaka cozucu.
 *
 * Gorus acisi yaklasik bilinir (kamera metadatasi, +-%5). Yontem: gozlenen yildiz ciftlerini katalog
 * ciftleriyle eslestir, cift acisindan odak uzakligini kestir, ucuncu yildizla ucgeni dogrula,
 * Davenport q-yontemiyle donusumu bul, tum yildizlarla dogrula ve (donusum + odak) rafine et.
 * Sonuc dogrudan kamera->ICRS matrisidir; roll isaret/konvansiyon sorunu yoktur.
 * Lens distorsiyonu modellenmez (telefon JPEG'i genelde duzeltilmistir).
 */
class StarPlateSolver(
    private val catalog: StarCatalog,
    private val patternMaxMag: Float = 4.6f,
    private val maxPatternSepDeg: Double = 80.0
) {
    // --- desen veritabani: her yildiz icin acisal uzakliga gore sirali komsular ---
    private val patIdx: IntArray = (0 until catalog.size).filter { catalog.mag[it] <= patternMaxMag }.toIntArray()
    private val nbrIdx: Array<IntArray>
    private val nbrSep: Array<DoubleArray>

    private val patPos: IntArray = IntArray(catalog.size) { -1 }.also { arr -> patIdx.forEachIndexed { p, c -> arr[c] = p } }

    // Dec'e gore sirali katalog (dogrulama icin hizli yakin yildiz arama)
    private val byDec: IntArray = (0 until catalog.size).sortedBy { catalog.decDeg[it] }.toIntArray()
    private val decSorted: DoubleArray = DoubleArray(catalog.size) { catalog.decDeg[byDec[it]] }

    init {
        val maxSep = Math.toRadians(maxPatternSepDeg)
        val n = patIdx.size
        val tmpIdx = Array(n) { ArrayList<Int>() }
        val tmpSep = Array(n) { ArrayList<Double>() }
        for (a in 0 until n) for (b in a + 1 until n) {
            val d = angle(patIdx[a], patIdx[b])
            if (d <= maxSep) {
                tmpIdx[a].add(patIdx[b]); tmpSep[a].add(d)
                tmpIdx[b].add(patIdx[a]); tmpSep[b].add(d)
            }
        }
        nbrIdx = Array(n) { a ->
            val order = tmpSep[a].indices.sortedBy { tmpSep[a][it] }
            IntArray(order.size) { tmpIdx[a][order[it]] }
        }
        nbrSep = Array(n) { a ->
            val order = tmpSep[a].indices.sortedBy { tmpSep[a][it] }
            DoubleArray(order.size) { tmpSep[a][order[it]] }
        }
    }

    private fun angle(i: Int, j: Int): Double {
        val v = catalog.vec
        val d = v[3 * i] * v[3 * j] + v[3 * i + 1] * v[3 * j + 1] + v[3 * i + 2] * v[3 * j + 2]
        return acos(d.coerceIn(-1.0, 1.0))
    }

    private class Obs(val x: Double, val y: Double)

    private fun camVec(o: Obs, cx: Double, cy: Double, f: Double, k1: Double = 0.0): DoubleArray {
        var a = (o.x - cx) / f; var b = (o.y - cy) / f
        if (k1 != 0.0) { val sc = 1.0 + k1 * (a * a + b * b); a *= sc; b *= sc }   // radyal distorsiyon (tek katsayi)
        val n = sqrt(a * a + b * b + 1.0)
        return doubleArrayOf(a / n, b / n, 1.0 / n)
    }

    private fun angleBetween(p: DoubleArray, q: DoubleArray): Double =
        acos((p[0] * q[0] + p[1] * q[1] + p[2] * q[2]).coerceIn(-1.0, 1.0))

    /** Iki gozlemin arasindaki aci tam f icin; f'yi katalog acisina uyduracak sekilde ikiye bolme. */
    private fun focalFromPair(a: Obs, b: Obs, cx: Double, cy: Double, targetRad: Double, f0: Double): Double? {
        var lo = f0 * 0.92; var hi = f0 * 1.08
        val aLo = angleBetween(camVec(a, cx, cy, lo), camVec(b, cx, cy, lo))
        val aHi = angleBetween(camVec(a, cx, cy, hi), camVec(b, cx, cy, hi))
        // f buyudukce aci kucuk
        if (targetRad > aLo || targetRad < aHi) return null
        repeat(22) {
            val mid = 0.5 * (lo + hi)
            val am = angleBetween(camVec(a, cx, cy, mid), camVec(b, cx, cy, mid))
            if (am > targetRad) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    /** cel = R * cam. Agirlikli Davenport q-yontemi. */
    private fun fitRotation(cam: List<DoubleArray>, cel: List<DoubleArray>): Array<DoubleArray> {
        val b = Array(3) { DoubleArray(3) }
        for (k in cam.indices) for (i in 0 until 3) for (j in 0 until 3) b[i][j] += cel[k][i] * cam[k][j]
        val sigma = b[0][0] + b[1][1] + b[2][2]
        val s = Array(3) { i -> DoubleArray(3) { j -> b[i][j] + b[j][i] } }
        val z = doubleArrayOf(b[1][2] - b[2][1], b[2][0] - b[0][2], b[0][1] - b[1][0])
        val kMat = Array(4) { DoubleArray(4) }
        for (i in 0 until 3) { for (j in 0 until 3) kMat[i][j] = s[i][j] - if (i == j) sigma else 0.0; kMat[i][3] = z[i]; kMat[3][i] = z[i] }
        kMat[3][3] = sigma
        val q = dominantEigenvector4(kMat)
        val qv = doubleArrayOf(q[0], q[1], q[2]); val q4 = q[3]
        val qq = qv[0] * qv[0] + qv[1] * qv[1] + qv[2] * qv[2]
        val cx = arrayOf(
            doubleArrayOf(0.0, -qv[2], qv[1]),
            doubleArrayOf(qv[2], 0.0, -qv[0]),
            doubleArrayOf(-qv[1], qv[0], 0.0)
        )
        return Array(3) { i -> DoubleArray(3) { j ->
            (if (i == j) q4 * q4 - qq else 0.0) + 2.0 * qv[i] * qv[j] - 2.0 * q4 * cx[i][j]
        } }
    }

    /** Simetrik 4x4 icin en buyuk ozdeger vektoru (Jacobi). */
    private fun dominantEigenvector4(m: Array<DoubleArray>): DoubleArray {
        val a = Array(4) { m[it].clone() }
        val v = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(60) {
            var p = 0; var q = 1; var big = 0.0
            for (i in 0 until 4) for (j in i + 1 until 4) if (abs(a[i][j]) > big) { big = abs(a[i][j]); p = i; q = j }
            if (big < 1e-15) return@repeat
            val theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
            val c = 1.0 / sqrt(t * t + 1.0); val s = t * c
            for (k in 0 until 4) { val akp = a[k][p]; val akq = a[k][q]; a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq }
            for (k in 0 until 4) { val apk = a[p][k]; val aqk = a[q][k]; a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk }
            for (k in 0 until 4) { val vkp = v[k][p]; val vkq = v[k][q]; v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq }
        }
        var best = 0
        for (i in 1 until 4) if (a[i][i] > a[best][best]) best = i
        return DoubleArray(4) { v[it][best] }
    }

    private fun mulVec(r: Array<DoubleArray>, v: DoubleArray) = doubleArrayOf(
        r[0][0] * v[0] + r[0][1] * v[1] + r[0][2] * v[2],
        r[1][0] * v[0] + r[1][1] * v[1] + r[1][2] * v[2],
        r[2][0] * v[0] + r[2][1] * v[1] + r[2][2] * v[2]
    )

    /** w'ya tolerans icinde en yakin katalog yildizi (indeks, aci) ; yoksa -1. */
    private fun nearestCatalog(w: DoubleArray, tolRad: Double, maxMag: Float): Pair<Int, Double> {
        val decDeg = Math.toDegrees(kotlin.math.asin(w[2].coerceIn(-1.0, 1.0)))
        val tolDeg = Math.toDegrees(tolRad)
        var lo = lowerBound(decSorted, decDeg - tolDeg)
        var bestI = -1; var bestD = Double.MAX_VALUE
        val cosTol = cos(tolRad)
        while (lo < decSorted.size && decSorted[lo] <= decDeg + tolDeg) {
            val i = byDec[lo]
            if (catalog.mag[i] <= maxMag) {
                val d = catalog.vec[3 * i] * w[0] + catalog.vec[3 * i + 1] * w[1] + catalog.vec[3 * i + 2] * w[2]
                if (d >= cosTol) {
                    val ang = acos(d.coerceIn(-1.0, 1.0))
                    if (ang < bestD) { bestD = ang; bestI = i }
                }
            }
            lo++
        }
        return Pair(bestI, bestD)
    }

    private fun lowerBound(arr: DoubleArray, x: Double): Int {
        var lo = 0; var hi = arr.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (arr[mid] < x) lo = mid + 1 else hi = mid }
        return lo
    }

    private class Fit(val r: Array<DoubleArray>, val f: Double, val matches: Int, val rmsRad: Double, val k1: Double = 0.0)

    /**
     * Baslangic (R, f) ile eslestir -> donusum + odak rafine et. Tolerans azalir.
     */
    private fun refine(obs: List<Obs>, cx: Double, cy: Double, r0: Array<DoubleArray>, f0: Double, maxMag: Float,
                       k1Init: Double = 0.0, fitK: Boolean = true): Fit? {
        var r = r0; var f = f0; var k1 = k1Init
        val tolsDeg = doubleArrayOf(0.4, 0.25, 0.15, 0.1)
        for (tol in tolsDeg) {
            val used = HashSet<Int>()
            // eslesmeleri sabit tut, odak + donusumu birlikte ara
            val matchedObs = ArrayList<Obs>(); val matchedCel = ArrayList<DoubleArray>()
            for (o in obs) {
                val w = mulVec(r, camVec(o, cx, cy, f, k1))
                val (i, _) = nearestCatalog(w, Math.toRadians(tol), maxMag)
                if (i >= 0 && used.add(i)) { matchedObs.add(o); matchedCel.add(doubleArrayOf(catalog.vec[3 * i], catalog.vec[3 * i + 1], catalog.vec[3 * i + 2])) }
            }
            if (matchedObs.size < 4) return null
            fun cost(ff: Double, kk: Double = k1): Pair<Double, Array<DoubleArray>> {
                val cams = matchedObs.map { camVec(it, cx, cy, ff, kk) }
                val rr = fitRotation(cams, matchedCel)
                var sum = 0.0
                for (k in cams.indices) { val e = angleBetween(mulVec(rr, cams[k]), matchedCel[k]); sum += e * e }
                return Pair(sqrt(sum / cams.size), rr)
            }
            var lo = f * 0.97; var hi = f * 1.03
            val gr = 0.6180339887
            var m1 = hi - gr * (hi - lo); var m2 = lo + gr * (hi - lo)
            var c1 = cost(m1).first; var c2 = cost(m2).first
            repeat(30) {
                if (c1 < c2) { hi = m2; m2 = m1; c2 = c1; m1 = hi - gr * (hi - lo); c1 = cost(m1).first }
                else { lo = m1; m1 = m2; c1 = c2; m2 = lo + gr * (hi - lo); c2 = cost(m2).first }
            }
            f = 0.5 * (lo + hi)
            // Son iki (siki) turda tek katsayili distorsiyonu da kestir; yalnizca belirgin iyilesme varsa kullan.
            if (fitK && matchedObs.size >= 10) {
                val base = cost(f, 0.0).first
                var klo = -0.08; var khi = 0.08
                var n1 = khi - gr * (khi - klo); var n2 = klo + gr * (khi - klo)
                var d1 = cost(f, n1).first; var d2 = cost(f, n2).first
                repeat(24) {
                    if (d1 < d2) { khi = n2; n2 = n1; d2 = d1; n1 = khi - gr * (khi - klo); d1 = cost(f, n1).first }
                    else { klo = n1; n1 = n2; d1 = d2; n2 = klo + gr * (khi - klo); d2 = cost(f, n2).first }
                }
                val kBest = 0.5 * (klo + khi)
                val kCost = cost(f, kBest).first
                if (kCost < 0.85 * base && abs(kBest) > 1e-4) {
                    k1 = kBest
                    // f'yi yeni k1 ile bir kez daha incelt
                    var l2 = f * 0.985; var h2 = f * 1.015
                    var p1 = h2 - gr * (h2 - l2); var p2 = l2 + gr * (h2 - l2)
                    var e1 = cost(p1).first; var e2 = cost(p2).first
                    repeat(24) {
                        if (e1 < e2) { h2 = p2; p2 = p1; e2 = e1; p1 = h2 - gr * (h2 - l2); e1 = cost(p1).first }
                        else { l2 = p1; p1 = p2; e1 = e2; p2 = l2 + gr * (h2 - l2); e2 = cost(p2).first }
                    }
                    f = 0.5 * (l2 + h2)
                } else k1 = k1Init
            }
            val rr = cost(f).second
            r = rr
        }
        // son tolerans (0.1 derece) ile nihai eslesme sayisi
        var inl = 0; val used = HashSet<Int>(); var sum = 0.0
        for (o in obs) {
            val w = mulVec(r, camVec(o, cx, cy, f, k1))
            val (i, d) = nearestCatalog(w, Math.toRadians(0.12), maxMag)
            if (i >= 0 && used.add(i)) { inl++; sum += d * d }
        }
        if (inl < 4) return null
        return Fit(r, f, inl, sqrt(sum / inl), k1)
    }

    /**
     * @param stars tespit edilen yildizlar (parlaklik buyukten kucuge siralanmasi gerekmez)
     * @param widthPx, heightPx goruntu boyutu (kamera cercevesinde dik gorunen goruntu)
     * @param longSideFovDegGuess uzun kenar gorus acisi tahmini (metadata)
     * @param maxMagForVerify dogrulamada kullanilacak katalog parlaklik siniri
     */
    fun solve(
        stars: List<Star>, widthPx: Int, heightPx: Int, longSideFovDegGuess: Double,
        maxMagForVerify: Float = 6.0f, maxMillis: Long = 12_000L, minMatches: Int = 8
    ): PlateSolution? {
        val deadline = System.nanoTime() + maxMillis * 1_000_000L
        if (stars.size < 5 || widthPx < 16 || heightPx < 16) return null
        val sorted = stars.sortedByDescending { it.brightness }.take(40)
        val obs = sorted.map { Obs(it.x.toDouble(), it.y.toDouble()) }
        val cx = widthPx / 2.0; val cy = heightPx / 2.0
        val longPx = max(widthPx, heightPx).toDouble()
        val f0 = (longPx / 2.0) / tan(Math.toRadians(longSideFovDegGuess) / 2.0)

        val topK = min(obs.size, 12)
        val minSepRad = Math.toRadians(longSideFovDegGuess * 0.12)
        var best: Fit? = null
        var tested = 0

        // Parlak yildizlardan baslayarak ciftler
        val pairs = ArrayList<IntArray>()
        for (sum in 1..(2 * topK - 3)) for (a in 0 until topK) { val b = sum - a; if (b > a && b < topK) pairs.add(intArrayOf(a, b)) }

        for (pr in pairs) {
            val a = obs[pr[0]]; val b = obs[pr[1]]
            val va0 = camVec(a, cx, cy, f0); val vb0 = camVec(b, cx, cy, f0)
            val sepObs0 = angleBetween(va0, vb0)
            if (sepObs0 < minSepRad) continue
            val lo = sepObs0 * 0.93 - Math.toRadians(0.15); val hi = sepObs0 * 1.07 + Math.toRadians(0.15)
            for (pi in patIdx.indices) {
                val seps = nbrSep[pi]
                var k = lowerBound(seps, lo)
                while (k < seps.size && seps[k] <= hi) {
                    val iStar = patIdx[pi]; val jStar = nbrIdx[pi][k]
                    k++
                    tested++
                    if ((tested and 255) == 0 && System.nanoTime() > deadline) return finalize(best, obs, cx, cy, maxMagForVerify)
                    val f = focalFromPair(a, b, cx, cy, seps[k - 1], f0) ?: continue
                    if (abs(f / f0 - 1.0) > 0.07) continue
                    // ucuncu yildiz: gozlenen (a,c),(b,c) acilari katalogdaki bir k yildizi ile uyusmali
                    val cams = ArrayList<DoubleArray>(topK)
                    for (q in 0 until topK) cams.add(camVec(obs[q], cx, cy, f))
                    val ci = catalogThird(cams, pr[0], pr[1], iStar, jStar) ?: continue
                    val rot = fitRotation(
                        listOf(cams[pr[0]], cams[pr[1]], cams[ci.first]),
                        listOf(starVec(iStar), starVec(jStar), starVec(ci.second))
                    )
                    // kaba dogrulama
                    var coarse = 0
                    for (idx in obs.indices) {
                        val cv = camVec(obs[idx], cx, cy, f)
                        val w = mulVec(rot, cv)
                        val tol = Math.toRadians(0.12) + 0.006 * angleBetween(cv, doubleArrayOf(0.0, 0.0, 1.0))
                        if (nearestCatalog(w, tol, maxMagForVerify).first >= 0) coarse++
                    }
                    if (coarse < 6) continue
                    val fit = refine(obs, cx, cy, rot, f, maxMagForVerify) ?: continue
                    if (fit.matches >= minMatches && fit.rmsRad < Math.toRadians(0.08)) {
                        if (best == null || fit.matches > best!!.matches ||
                            (fit.matches == best!!.matches && fit.rmsRad < best!!.rmsRad)) best = fit
                        if (fit.matches >= minMatches + 2) return finalize(best, obs, cx, cy, maxMagForVerify)
                    }
                }
            }
            if (best != null) return finalize(best, obs, cx, cy, maxMagForVerify)
        }
        return finalize(best, obs, cx, cy, maxMagForVerify)
    }

    /**
     * Atmosfer kirilmasi duzeltmesi: gozlenen (gorunur) yildizlar zenite dogru kayar (Saemundsson formulu).
     * Eslesen yildizlarin katalog vektorlerini gorunur konumlarina kaydirip donusumu yeniden uydurur.
     * Aksi halde sabit bir kayma (~1 km) zenit yonune yansir.
     * @param upCam yaklasik zenit yonu (kamera ekseninde)
     */
    fun correctForRefraction(
        stars: List<Star>, widthPx: Int, heightPx: Int, plate: PlateSolution, upCam: DoubleArray,
        epochMillis: Long? = null
    ): PlateSolution {
        val beta = epochMillis?.let { earthVelocityOverC(it) }
        val cx = widthPx / 2.0; val cy = heightPx / 2.0
        val f = (max(widthPx, heightPx) / 2.0) / tan(Math.toRadians(plate.longSideFovDeg) / 2.0)
        val obs = stars.sortedByDescending { it.brightness }.take(40).map { Obs(it.x.toDouble(), it.y.toDouble()) }
        val un = sqrt(upCam[0] * upCam[0] + upCam[1] * upCam[1] + upCam[2] * upCam[2])
        val upN = doubleArrayOf(upCam[0] / un, upCam[1] / un, upCam[2] / un)
        var r = plate.celFromCam
        repeat(2) {
            val z = mulVec(r, upN)
            val used = HashSet<Int>()
            val cams = ArrayList<DoubleArray>(); val apparent = ArrayList<DoubleArray>()
            for (o in obs) {
                val cv = camVec(o, cx, cy, f, plate.k1)
                val (i, _) = nearestCatalog(mulVec(r, cv), Math.toRadians(0.15), 6.0f)
                if (i < 0 || !used.add(i)) continue
                val v0 = starVec(i)
                val v = if (beta != null) aberrate(v0, beta) else v0
                val sinAlt = (v[0] * z[0] + v[1] * z[1] + v[2] * z[2]).coerceIn(-1.0, 1.0)
                val altDeg = Math.toDegrees(kotlin.math.asin(sinAlt))
                if (altDeg < 8.0) continue
                val rho = Math.toRadians(1.02 / tan(Math.toRadians(altDeg + 10.3 / (altDeg + 5.11))) / 60.0)
                val tx = z[0] - sinAlt * v[0]; val ty = z[1] - sinAlt * v[1]; val tz = z[2] - sinAlt * v[2]
                val tn = sqrt(tx * tx + ty * ty + tz * tz)
                if (tn < 1e-9) continue
                val c = cos(rho); val sn = sin(rho)
                cams.add(cv)
                apparent.add(doubleArrayOf(v[0] * c + sn * tx / tn, v[1] * c + sn * ty / tn, v[2] * c + sn * tz / tn))
            }
            if (cams.size < 6) return plate
            r = fitRotation(cams, apparent)
        }
        val bore = mulVec(r, doubleArrayOf(0.0, 0.0, 1.0))
        val ra = (Math.toDegrees(kotlin.math.atan2(bore[1], bore[0])) + 360.0) % 360.0
        val dec = Math.toDegrees(kotlin.math.asin(bore[2].coerceIn(-1.0, 1.0)))
        return plate.copy(celFromCam = r, boresightRaDeg = ra, boresightDecDeg = dec)
    }

    /**
     * Yillik sapinc (aberasyon): Dunya'nin yorunge hizi (~29.8 km/s) yildiz yonlerini <=20.5" kaydirir.
     * Gunes'in ekliptik boylamindan Dunya hiz vektoru / c (ICRS ekvatoral eksenleri).
     */
    private fun earthVelocityOverC(epochMillis: Long): DoubleArray {
        val jd = epochMillis / 86_400_000.0 + 2440587.5
        val n = jd - 2451545.0
        val g = Math.toRadians(357.528 + 0.9856003 * n)
        val lam = Math.toRadians(280.460 + 0.9856474 * n + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.4393)
        val b = 29.78 / 299_792.458
        return doubleArrayOf(b * sin(lam), -b * cos(lam) * cos(eps), -b * cos(lam) * sin(eps))
    }

    private fun aberrate(v: DoubleArray, beta: DoubleArray): DoubleArray {
        val d = v[0] * beta[0] + v[1] * beta[1] + v[2] * beta[2]
        val x = v[0] + beta[0] - d * v[0]; val y = v[1] + beta[1] - d * v[1]; val z = v[2] + beta[2] - d * v[2]
        val n = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / n, y / n, z / n)
    }

    private fun starVec(i: Int) = doubleArrayOf(catalog.vec[3 * i], catalog.vec[3 * i + 1], catalog.vec[3 * i + 2])

    /** (a->i, b->j) icin ucuncu gozlem c ve katalog yildizi k bul: acilar uyusmali. */
    private fun catalogThird(cams: List<DoubleArray>, a: Int, b: Int, i: Int, j: Int): Pair<Int, Int>? {
        val tolRel = 0.006; val tolAbs = Math.toRadians(0.12)
        val pi = patPos[i]; if (pi < 0) return null
        for (c in cams.indices) {
            if (c == a || c == b) continue
            val sac = angleBetween(cams[a], cams[c]); val sbc = angleBetween(cams[b], cams[c])
            val lo = sac * (1 - tolRel) - tolAbs; val hi = sac * (1 + tolRel) + tolAbs
            var k = lowerBound(nbrSep[pi], lo)
            while (k < nbrSep[pi].size && nbrSep[pi][k] <= hi) {
                val kStar = nbrIdx[pi][k]; k++
                if (kStar == j) continue
                val d = angleBetween(starVec(j), starVec(kStar))
                if (abs(d - sbc) <= sbc * tolRel + tolAbs) return Pair(c, kStar)
            }
        }
        return null
    }

    /** Cozumden sonra distorsiyon taramasi: k1 izgarasinda eslesen yildiz sayisini enyukselten degeri sec. */
    private fun polishDistortion(fit: Fit, obs: List<Obs>, cx: Double, cy: Double, maxMag: Float): Fit {
        var best = fit
        var k = -0.08
        while (k <= 0.0801) {
            if (abs(k) > 1e-9) {
                val cand = refine(obs, cx, cy, fit.r, fit.f, maxMag, k1Init = k, fitK = false)
                if (cand != null && (cand.matches > best.matches || (cand.matches == best.matches && cand.rmsRad < best.rmsRad * 0.85))) best = cand
            }
            k += 0.01
        }
        if (best === fit) return fit
        // ince ayar: secilen k1 etrafinda serbest k1 ile yeniden rafine
        val fin = refine(obs, cx, cy, best.r, best.f, maxMag, k1Init = best.k1, fitK = true)
        return if (fin != null && fin.matches >= best.matches) fin else best
    }

    private fun finalize(best0: Fit?, obs: List<Obs>, cx: Double, cy: Double, maxMag: Float): PlateSolution? {
        best0 ?: return null
        val used = obs.size
        val best = polishDistortion(best0, obs, cx, cy, maxMag)
        val r = best.r
        val bore = mulVec(r, doubleArrayOf(0.0, 0.0, 1.0))
        val ra = (Math.toDegrees(kotlin.math.atan2(bore[1], bore[0])) + 360.0) % 360.0
        val dec = Math.toDegrees(kotlin.math.asin(bore[2].coerceIn(-1.0, 1.0)))
        val longPx = 2.0 * max(cx, cy)
        val fov = Math.toDegrees(2.0 * atan(longPx / 2.0 / best.f))
        return PlateSolution(r, fov, best.matches, used, Math.toDegrees(best.rmsRad), ra, dec, best.k1)
    }
}
