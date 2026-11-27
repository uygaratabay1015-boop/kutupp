package com.kutup.navigasyon

import kotlin.math.*

/** Bir Gunes olcumu: zaman ve DUZELTILMIS (kirilma+paralaks) geometrik zenit uzakligi. */
data class SunSighting(val epochMs: Long, val zenithDeg: Double, val sigmaDeg: Double, val azimuthHintDeg: Double? = null)

data class SunFix(
    val latDeg: Double, val lonDeg: Double, val errorKm68: Double, val rmsResidualDeg: Double,
    val ambiguous: Boolean, val altLatDeg: Double?, val altLonDeg: Double?, val count: Int
)

/** Gunes yuksekliklerinden konum (konum dairelerinin kesisimi, agirlikli en kucuk kareler). Pusula istege bagli ipucu. */
object SunSolver {
    private const val D2R = PI / 180.0
    private class Cand(val lat: Double, val lon: Double, val cost: Double)

    private fun cost(lat: Double, lon: Double, ps: List<SunPosition>, s: List<SunSighting>): Double {
        var c = 0.0
        for (i in s.indices) { val r = (SunEphemeris.zenithDistanceDeg(lat, lon, ps[i]) - s[i].zenithDeg) / s[i].sigmaDeg; c += r * r }
        return c
    }

    fun solve(sightings: List<SunSighting>): SunFix? {
        if (sightings.size < 2) return null
        val ps = sightings.map { SunEphemeris.position(it.epochMs) }
        // 1) kaba izgara
        val coarse = ArrayList<Cand>()
        var lat = -88.0
        while (lat <= 88.0) { var lon = -180.0; while (lon < 180.0) { coarse.add(Cand(lat, lon, cost(lat, lon, ps, sightings))); lon += 2.0 }; lat += 2.0 }
        coarse.sortBy { it.cost }
        val first = refine(coarse[0].lat, coarse[0].lon, ps, sightings)
        val farther = coarse.firstOrNull { SunFixMath.km(it.lat, it.lon, first.lat, first.lon) > 1500.0 }
        val second = farther?.let { refine(it.lat, it.lon, ps, sightings) }
        var chosen = first; var other = second
        val n = sightings.size
        // pusula ipucu: iki aday gerceklikle uyusuyorsa pusulaya yakin olan secilir
        val hints = sightings.mapIndexedNotNull { i, s -> s.azimuthHintDeg?.let { i to it } }
        var ambiguous = second != null && second.cost < first.cost + 9.0
        if (ambiguous && hints.isNotEmpty() && second != null) {
            fun mism(c: Cand): Double = hints.map { (i, h) ->
                val a = SunEphemeris.azimuthDeg(c.lat, c.lon, ps[i]); abs(((a - h + 540.0) % 360.0) - 180.0)
            }.average()
            val m1 = mism(first); val m2 = mism(second)
            if (abs(m1 - m2) > 25.0) { if (m2 < m1) { chosen = second; other = first }; ambiguous = false }
        }
        val cov = covariance(chosen.lat, chosen.lon, ps, sightings)
        val chi2 = chosen.cost
        val infl = if (n > 2) max(1.0, chi2 / (n - 2)) else 1.0
        val err = sqrt(((cov[0] + cov[3] * cos(chosen.lat * D2R).pow(2)) * infl)) * 111.195
        val rms = sqrt(sightings.indices.sumOf { i -> (SunEphemeris.zenithDistanceDeg(chosen.lat, chosen.lon, ps[i]) - sightings[i].zenithDeg).pow(2) } / n)
        return SunFix(chosen.lat, chosen.lon, err, rms, ambiguous, other?.lat.takeIf { ambiguous || other != null }, other?.lon.takeIf { ambiguous || other != null }, n)
    }

    private fun refine(lat0: Double, lon0: Double, ps: List<SunPosition>, s: List<SunSighting>): Cand {
        var lat = lat0; var lon = lon0; var lam = 1e-3
        var c = cost(lat, lon, ps, s)
        repeat(40) {
            val jtj = DoubleArray(4); val jtr = DoubleArray(2)
            val eps = 1e-4
            for (i in s.indices) {
                val d0 = SunEphemeris.zenithDistanceDeg(lat, lon, ps[i])
                val dLat = (SunEphemeris.zenithDistanceDeg(lat + eps, lon, ps[i]) - d0) / eps
                val dLon = (SunEphemeris.zenithDistanceDeg(lat, lon + eps, ps[i]) - d0) / eps
                val w = 1.0 / (s[i].sigmaDeg * s[i].sigmaDeg); val r = d0 - s[i].zenithDeg
                jtj[0] += w * dLat * dLat; jtj[1] += w * dLat * dLon; jtj[3] += w * dLon * dLon
                jtr[0] += w * dLat * r; jtr[1] += w * dLon * r
            }
            jtj[2] = jtj[1]
            val a = jtj[0] * (1 + lam); val d = jtj[3] * (1 + lam); val b = jtj[1]
            val det = a * d - b * b
            if (abs(det) < 1e-18) return@repeat
            val dLatStep = -(d * jtr[0] - b * jtr[1]) / det
            val dLonStep = -(-b * jtr[0] + a * jtr[1]) / det
            val nl = (lat + dLatStep).coerceIn(-89.9, 89.9); val no = lon + dLonStep
            val nc = cost(nl, no, ps, s)
            if (nc < c) { lat = nl; lon = no; c = nc; lam = max(lam / 3, 1e-9) } else lam *= 4
        }
        return Cand(lat, ((lon + 540.0) % 360.0) - 180.0, c)
    }

    /** Kovaryans (derece^2): [var_lat, cov, cov, var_lon]. */
    private fun covariance(lat: Double, lon: Double, ps: List<SunPosition>, s: List<SunSighting>): DoubleArray {
        val j = DoubleArray(4); val eps = 1e-4
        for (i in s.indices) {
            val d0 = SunEphemeris.zenithDistanceDeg(lat, lon, ps[i])
            val dLat = (SunEphemeris.zenithDistanceDeg(lat + eps, lon, ps[i]) - d0) / eps
            val dLon = (SunEphemeris.zenithDistanceDeg(lat, lon + eps, ps[i]) - d0) / eps
            val w = 1.0 / (s[i].sigmaDeg * s[i].sigmaDeg)
            j[0] += w * dLat * dLat; j[1] += w * dLat * dLon; j[3] += w * dLon * dLon
        }
        j[2] = j[1]
        val det = j[0] * j[3] - j[1] * j[1]
        if (abs(det) < 1e-12) return doubleArrayOf(1e6, 0.0, 0.0, 1e6)
        return doubleArrayOf(j[3] / det, -j[1] / det, -j[2] / det, j[0] / det)
    }
}

object SunFixMath {
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = ZenithFix.greatCircleKm(lat1, lon1, lat2, lon2)
}
