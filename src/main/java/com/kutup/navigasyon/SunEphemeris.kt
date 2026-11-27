package com.kutup.navigasyon

import kotlin.math.*

/** Gorunur Gunes konumu (tarih ekinoksu/ekliptigine gore, sapma+aberasyon dahil). Meeus bol.25, ~0.01° dogruluk. */
data class SunPosition(val raDeg: Double, val decDeg: Double, val gastDeg: Double)

object SunEphemeris {
    private const val D2R = PI / 180.0
    private const val R2D = 180.0 / PI

    fun position(epochMillis: Long): SunPosition {
        val jd = ZenithFix.julianDateUtc(epochMillis)
        val t = (jd - 2451545.0) / 36525.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) * D2R
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
            (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m)
        val trueLon = l0 + c
        val omega = (125.04 - 1934.136 * t) * D2R
        val lambda = (trueLon - 0.00569 - 0.00478 * sin(omega)) * D2R
        val eps = (23.439291 - 0.0130042 * t + 0.00256 * cos(omega)) * D2R
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda)) * R2D
        val dec = asin(sin(eps) * sin(lambda)) * R2D
        val gast = ZenithFix.greenwichApparentSiderealTime(jd) * R2D
        return SunPosition((ra + 360.0) % 360.0, dec, gast)
    }

    /** Gunes'in tam altindaki nokta (enlem, boylam; dogu +). */
    fun subSolarPoint(epochMillis: Long): Pair<Double, Double> {
        val p = position(epochMillis)
        var lon = p.raDeg - p.gastDeg
        lon = ((lon + 540.0) % 360.0) - 180.0
        return p.decDeg to lon
    }

    /** Gozlemcinin (enlem, boylam) icin Gunes'in geometrik zenit uzakligi (derece). */
    fun zenithDistanceDeg(latDeg: Double, lonDeg: Double, p: SunPosition): Double {
        val phi = latDeg * D2R
        val dec = p.decDeg * D2R
        val h = (p.gastDeg + lonDeg - p.raDeg) * D2R
        val x = sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h)
        val y = hypot(cos(dec) * sin(h), cos(phi) * sin(dec) - sin(phi) * cos(dec) * cos(h))
        return atan2(y, x) * R2D
    }

    /** Gunes azimutu (kuzeyden saat yonunde, derece). */
    fun azimuthDeg(latDeg: Double, lonDeg: Double, p: SunPosition): Double {
        val phi = latDeg * D2R
        val dec = p.decDeg * D2R
        val h = (p.gastDeg + lonDeg - p.raDeg) * D2R
        val az = atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi)) * R2D
        return (az + 180.0 + 360.0) % 360.0
    }

    /** Gorunen (kirilmali) yukseklikten kirilma duzeltmesi (derece, Bennett; 1010 hPa, 10 C). */
    fun refractionDeg(apparentAltDeg: Double): Double {
        val h = apparentAltDeg.coerceAtLeast(-1.0)
        return 1.02 / tan((h + 10.3 / (h + 5.11)) * D2R) / 60.0
    }

    /** Olculen (gorunen) zenit uzakligindan geometrik, geosentrik zenit uzakligi (kirilma + paralaks). */
    fun trueZenithDistanceDeg(apparentZenithDeg: Double): Double {
        val alt = 90.0 - apparentZenithDeg
        val z = apparentZenithDeg + refractionDeg(alt)
        return z - (8.794 / 3600.0) * sin(z * D2R)
    }
}
