package com.kutup.navigasyon

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class SwingFrame(
    /** Kamera->ICRS (kirilma duzeltmeli plaka cozumu). */
    val celFromCam: Array<DoubleArray>,
    /** Olculen zenit yonu, kamera ekseninde (kalibrasyon UYGULANMAMIS ham olcum de olabilir). */
    val upMeasuredCam: DoubleArray,
    val epochMillis: Long,
    /** Egim olcum belirsizligi (derece), agirlik 1/sigma^2. */
    val sigmaDeg: Double
)

data class SwingResult(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** Kestirilen sabit sensor sapmasi (kamera ekseninde, radyan). */
    val offsetCamRad: DoubleArray,
    val offsetDeg: Double,
    /** 0 = tum kareler ayni yone, 1 = tamamen dengeli. Sapmanin gozlenebilirligi icin. */
    val azimuthBalance: Double,
    val observable: Boolean,
    val scatterBeforeKm: Double,
    val scatterAfterKm: Double,
    val framesUsed: Int,
    /** Birlestirilmis egim belirsizligi (derece, 1 sigma). */
    val tiltSigmaDeg: Double
)

/**
 * "Dondurme" (swing) kendi kendine kalibrasyon: ayni yerden, ayni egimle ama FARKLI yonlere bakan
 * karelerde sabit sensor sapmasi (kamera ekseninde sabit vektor) her karede dunya cercevesinde farkli yone
 * doner, konum tahminleri ise ayni noktaya dusmek zorundadir. Sapmayi, konum dagilimini en aza
 * indirecek sekilde kestiririz. Bilinen konum GEREKMEZ.
 */
object SwingSolver {
    private const val PRIOR_SIGMA_DEG = 1.0

    private fun posVec(f: SwingFrame, d: DoubleArray): DoubleArray {
        val u = DoubleArray(3) { f.upMeasuredCam[it] }
        val un = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2])
        val up = doubleArrayOf(u[0] / un - d[0], u[1] / un - d[1], u[2] / un - d[2])
        val fix = ZenithFix.solvePosition(f.celFromCam, up, f.epochMillis)
        val la = Math.toRadians(fix.latitudeDeg); val lo = Math.toRadians(fix.longitudeDeg)
        return doubleArrayOf(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
    }

    private fun weights(frames: List<SwingFrame>) = frames.map { 1.0 / max(it.sigmaDeg, 0.03).let { s -> s * s } }

    private fun wmean(p: List<DoubleArray>, w: List<Double>): DoubleArray {
        val s = DoubleArray(3)
        for (i in p.indices) for (k in 0 until 3) s[k] += w[i] * p[i][k]
        val n = sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
        return doubleArrayOf(s[0] / n, s[1] / n, s[2] / n)
    }

    private fun residuals(frames: List<SwingFrame>, w: List<Double>, d: DoubleArray): DoubleArray {
        val p = frames.map { posVec(it, d) }
        val m = wmean(p, w)
        val r = DoubleArray(3 * frames.size + 3)
        val deg = 180.0 / Math.PI
        for (i in frames.indices) for (k in 0 until 3) r[3 * i + k] = sqrt(w[i]) * deg * (p[i][k] - m[k])
        // zayif oncul: sapma ~N(0, 1°); gozlenemeyen bilesen (yukari yonu) sifira cekilir
        for (k in 0 until 3) r[3 * frames.size + k] = deg * d[k] / PRIOR_SIGMA_DEG
        return r
    }

    private fun scatterKm(frames: List<SwingFrame>, w: List<Double>, d: DoubleArray): Double {
        val p = frames.map { posVec(it, d) }
        val m = wmean(p, w)
        var s = 0.0
        for (v in p) {
            val dot = (v[0] * m[0] + v[1] * m[1] + v[2] * m[2]).coerceIn(-1.0, 1.0)
            val a = kotlin.math.acos(dot) * 6371.0088
            s += a * a
        }
        return sqrt(s / p.size)
    }

    /** Boresight yonunun yerel azimutlarinin dengeli dagilimi: 1 - |ortalama birim vektor|. */
    fun azimuthBalance(frames: List<SwingFrame>): Double {
        var sx = 0.0; var sy = 0.0
        for (f in frames) {
            val r = f.celFromCam
            val bore = doubleArrayOf(r[0][2], r[1][2], r[2][2])
            val un = sqrt(f.upMeasuredCam.sumOf { it * it })
            val z = DoubleArray(3) { k -> r[k][0] * f.upMeasuredCam[0] / un + r[k][1] * f.upMeasuredCam[1] / un + r[k][2] * f.upMeasuredCam[2] / un }
            val pole = doubleArrayOf(0.0, 0.0, 1.0)
            val pz = pole[0] * z[0] + pole[1] * z[1] + pole[2] * z[2]
            val n = doubleArrayOf(pole[0] - pz * z[0], pole[1] - pz * z[1], pole[2] - pz * z[2])
            val nn = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (nn < 1e-6) continue
            val nu = doubleArrayOf(n[0] / nn, n[1] / nn, n[2] / nn)
            val e = doubleArrayOf(nu[1] * z[2] - nu[2] * z[1], nu[2] * z[0] - nu[0] * z[2], nu[0] * z[1] - nu[1] * z[0])
            val az = atan2(bore[0] * e[0] + bore[1] * e[1] + bore[2] * e[2], bore[0] * nu[0] + bore[1] * nu[1] + bore[2] * nu[2])
            sx += cos(az); sy += sin(az)
        }
        val n = frames.size.toDouble()
        return 1.0 - sqrt(sx * sx + sy * sy) / n
    }

    /** Yerel azimut (derece, kuzeyden doguya) - ekranda gostermek/kare arasi donus icin. */
    fun boresightAzimuthDeg(celFromCam: Array<DoubleArray>, upCam: DoubleArray): Double {
        val r = celFromCam
        val un = sqrt(upCam.sumOf { it * it })
        val z = DoubleArray(3) { k -> (r[k][0] * upCam[0] + r[k][1] * upCam[1] + r[k][2] * upCam[2]) / un }
        val bore = doubleArrayOf(r[0][2], r[1][2], r[2][2])
        val pz = z[2]
        val n = doubleArrayOf(-pz * z[0], -pz * z[1], 1.0 - pz * z[2])
        val nn = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        val nu = doubleArrayOf(n[0] / nn, n[1] / nn, n[2] / nn)
        val e = doubleArrayOf(nu[1] * z[2] - nu[2] * z[1], nu[2] * z[0] - nu[0] * z[2], nu[0] * z[1] - nu[1] * z[0])
        return (Math.toDegrees(atan2(bore[0] * e[0] + bore[1] * e[1] + bore[2] * e[2], bore[0] * nu[0] + bore[1] * nu[1] + bore[2] * nu[2])) + 360.0) % 360.0
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { i -> doubleArrayOf(a[i][0], a[i][1], a[i][2], b[i]) }
        for (c in 0 until 3) {
            var p = c
            for (r in c + 1 until 3) if (kotlin.math.abs(m[r][c]) > kotlin.math.abs(m[p][c])) p = r
            if (kotlin.math.abs(m[p][c]) < 1e-18) return null
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0 until 3) if (r != c) { val f = m[r][c] / m[c][c]; for (k in c until 4) m[r][k] -= f * m[c][k] }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    /**
     * @param minBalance en az bu azimut dengesi (0..1) yoksa sapma kestirilmez (duz agirlikli ortalama).
     * @param maxOffsetDeg makul sapma ust siniri.
     */
    fun solve(frames: List<SwingFrame>, minBalance: Double = 0.35, maxOffsetDeg: Double = 3.0): SwingResult? {
        if (frames.isEmpty()) return null
        val w = weights(frames)
        val zero = DoubleArray(3)
        val balance = azimuthBalance(frames)
        val before = scatterKm(frames, w, zero)
        val observable = frames.size >= 3 && balance >= minBalance
        var d = DoubleArray(3)
        if (observable) {
            var lambda = 1e-8
            var r = residuals(frames, w, d)
            var cost = r.sumOf { it * it }
            repeat(15) {
                val h = 1e-4
                val jac = Array(3) { DoubleArray(r.size) }
                for (k in 0 until 3) {
                    val dd = d.clone(); dd[k] += h
                    val r2 = residuals(frames, w, dd)
                    for (i in r.indices) jac[k][i] = (r2[i] - r[i]) / h
                }
                val jtj = Array(3) { a -> DoubleArray(3) { b -> (r.indices).sumOf { jac[a][it] * jac[b][it] } + if (a == b) lambda else 0.0 } }
                val jtr = DoubleArray(3) { a -> (r.indices).sumOf { jac[a][it] * r[it] } }
                val step = solve3(jtj, DoubleArray(3) { -jtr[it] }) ?: return@repeat
                val cand = DoubleArray(3) { d[it] + step[it] }
                val norm = sqrt(cand.sumOf { it * it })
                if (norm > Math.toRadians(maxOffsetDeg)) { lambda *= 10; return@repeat }
                val r3 = residuals(frames, w, cand)
                val c3 = r3.sumOf { it * it }
                if (c3 < cost) { d = cand; r = r3; cost = c3; lambda = max(lambda / 3, 1e-12) } else lambda *= 10
            }
        }
        val p = frames.map { posVec(it, d) }
        val m = wmean(p, w)
        val lat = Math.toDegrees(asin(m[2].coerceIn(-1.0, 1.0)))
        val lon = Math.toDegrees(atan2(m[1], m[0]))
        val randomSigma = 1.0 / sqrt(w.sum())
        // sapma kestirimi ayni gurultuyu kullandigi icin kucuk bir ceza
        val tiltSigma = sqrt((randomSigma * if (observable) 1.25 else 1.0).let { it * it } + if (observable) 0.03 * 0.03 else 0.0)
        return SwingResult(lat, lon, d, Math.toDegrees(sqrt(d.sumOf { it * it })), balance, observable, before, scatterKm(frames, w, d), frames.size, tiltSigma)
    }
}
