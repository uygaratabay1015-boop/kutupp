package com.kutup.navigasyon

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class BurstFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val framesUsed: Int,
    val framesTotal: Int,
    /** Kareler arasi gozlenen yayilma (km, ortalamaya olan 1B-radyal RMS). */
    val scatterKm: Double,
    /** Birlestirilmis egim belirsizligi (derece, 1 sigma). */
    val tiltSigmaDeg: Double,
    val errorKm68: Double,
    val errorKm95: Double
)

/**
 * Seri cekimde karelerin konumlarini birlestirir. Rastgele egim hatasi (titreme) kareler arasi bagimsizdir,
 * ~1/sqrt(N) ile azalir; sabit sapma (kalibrasyon belirsizligi) azalmaz ve ayrica eklenir.
 * Agirlik: 1/max(spread, 0.03°)^2. Aykiri kareler (ortancadan > max(10 km, 3*MAD)) atilir.
 */
class BurstAverager {
    private class F(val lat: Double, val lon: Double, val spreadDeg: Double, val plateRmsDeg: Double) {
        val v = doubleArrayOf(
            cos(Math.toRadians(lat)) * cos(Math.toRadians(lon)),
            cos(Math.toRadians(lat)) * sin(Math.toRadians(lon)),
            sin(Math.toRadians(lat))
        )
    }

    private val frames = ArrayList<F>()
    val count: Int get() = frames.size

    fun add(latDeg: Double, lonDeg: Double, spreadDeg: Double, plateRmsDeg: Double = 0.02) {
        frames.add(F(latDeg, lonDeg, spreadDeg, plateRmsDeg))
    }

    fun clear() = frames.clear()

    private fun weight(f: F) = 1.0 / max(f.spreadDeg, 0.03).let { it * it }

    private fun mean(list: List<F>): DoubleArray {
        val s = DoubleArray(3)
        for (f in list) { val w = weight(f); for (i in 0 until 3) s[i] += w * f.v[i] }
        val n = sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
        return doubleArrayOf(s[0] / n, s[1] / n, s[2] / n)
    }

    private fun km(a: DoubleArray, b: DoubleArray): Double {
        val d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
        return 6371.0088 * kotlin.math.acos(d)
    }

    /**
     * @param systematicSigmaDeg kareler arasinda ortak (azalmayan) egim belirsizligi (kalibrasyon/sapma)
     * @param timeSigmaSec saat hatasi (ortak)
     */
    fun result(systematicSigmaDeg: Double, timeSigmaSec: Double): BurstFix? {
        if (frames.isEmpty()) return null
        var kept: List<F> = frames
        if (frames.size >= 3) {
            val center = mean(frames)
            val dists = frames.map { km(it.v, center) }.sorted()
            val med = dists[dists.size / 2]
            val mad = frames.map { kotlin.math.abs(km(it.v, center) - med) }.sorted()[frames.size / 2]
            val limit = max(10.0, med + 3.0 * 1.4826 * mad)
            val filtered = frames.filter { km(it.v, center) <= limit }
            if (filtered.size >= 2) kept = filtered
        }
        val m = mean(kept)
        val lat = Math.toDegrees(asin(m[2].coerceIn(-1.0, 1.0)))
        val lon = Math.toDegrees(atan2(m[1], m[0]))
        val scatter = if (kept.size > 1) sqrt(kept.sumOf { val d = km(it.v, m); d * d } / kept.size) else 0.0
        val randomSigma = 1.0 / sqrt(kept.sumOf { weight(it) })
        val tiltSigma = sqrt(randomSigma * randomSigma + systematicSigmaDeg * systematicSigmaDeg)
        val plate = kept.map { it.plateRmsDeg }.average() / sqrt(kept.size.toDouble())
        val (e68, e95) = ZenithLocator.estimateErrorKm(lat, tiltSigma, timeSigmaSec, plate)
        return BurstFix(lat, lon, kept.size, frames.size, scatter, tiltSigma, e68, e95)
    }
}
