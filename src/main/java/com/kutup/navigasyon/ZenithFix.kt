package com.kutup.navigasyon

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Zenit tabanli konum sonucu (boylam dogu +, [-180, 180)). */
data class ZenithPositionFix(val latitudeDeg: Double, val longitudeDeg: Double)

/**
 * Zenit tabanli konum cozucu (astro_nav/zenith_fix.py'nin Kotlin portu; dunyanin her yerinde calisir).
 *
 * Plate solve yonelimi (RA/Dec/Roll) + yercekimi (zenit) vektoru + UTC zamanindan:
 *   enlem  = zenitin deklinasyonu
 *   boylam = zenitin sag acikligi - GAST
 *
 * Kamera ekseni (OpenCV): x = goruntu sagi, y = goruntu asagisi, z = bakis yonu (sag-el).
 * Matrisler [satir][sutun] bicimindedir; sutunlar kamera eksenlerinin ICRS'teki karsiligidir.
 */
object ZenithFix {
    private const val ARCSEC = PI / (180.0 * 3600.0)
    private const val TWO_PI = 2.0 * PI
    private const val EARTH_RADIUS_KM = 6371.0088

    // ------------------------------------------------------------ zaman / astronomi

    fun julianDateUtc(epochMillis: Long): Double {
        val t = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
        var y = t.year
        var m = t.monthValue
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = Math.floorDiv(y, 100)
        val b = 2 - a + Math.floorDiv(a, 4)
        val dayFraction = (t.hour + (t.minute + (t.second + t.nano / 1e9) / 60.0) / 60.0) / 24.0
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + t.dayOfMonth + dayFraction + b - 1524.5
    }

    private fun poly(coeffs: DoubleArray, t: Double): Double {
        var total = 0.0
        for (i in coeffs.indices.reversed()) total = total * t + coeffs[i]
        return total
    }

    private fun rx(a: Double): Array<DoubleArray> {
        val c = cos(a)
        val s = sin(a)
        return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, c, s), doubleArrayOf(0.0, -s, c))
    }

    private fun rz(a: Double): Array<DoubleArray> {
        val c = cos(a)
        val s = sin(a)
        return arrayOf(doubleArrayOf(c, s, 0.0), doubleArrayOf(-s, c, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { i -> DoubleArray(3) { j -> a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] } }

    private fun mulVec(a: Array<DoubleArray>, v: DoubleArray): DoubleArray =
        DoubleArray(3) { i -> a[i][0] * v[0] + a[i][1] * v[1] + a[i][2] * v[2] }

    private fun unit(v: DoubleArray): DoubleArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        require(n > 0.0 && n.isFinite()) { "Gecersiz (sifir/NaN) vektor" }
        return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    /** (dpsi, deps) radyan; dusuk-duzey nutasyon serisi (~0.5 yay-saniyesi). */
    private fun nutation(t: Double): Pair<Double, Double> {
        val omega = Math.toRadians(125.04452 - 1934.136261 * t)
        val lSun = Math.toRadians(280.4665 + 36000.7698 * t)
        val lMoon = Math.toRadians(218.3165 + 481267.8813 * t)
        val dpsi = (-17.20 * sin(omega) - 1.32 * sin(2 * lSun) - 0.23 * sin(2 * lMoon) + 0.21 * sin(2 * omega)) * ARCSEC
        val deps = (9.20 * cos(omega) + 0.57 * cos(2 * lSun) + 0.10 * cos(2 * lMoon) - 0.09 * cos(2 * omega)) * ARCSEC
        return dpsi to deps
    }

    /** ICRS (J2000) -> gercek ekvator/ekinoks (tarih). IAU 2006 Fukushima-Williams + nutasyon. */
    fun precessionNutationMatrix(jd: Double): Array<DoubleArray> {
        val t = (jd - 2451545.0) / 36525.0
        val gamb = poly(doubleArrayOf(-0.052928, 10.556378, 0.4932044, -0.00031238, -0.000002788, 0.0000000260), t) * ARCSEC
        val phib = poly(doubleArrayOf(84381.412819, -46.811016, 0.0511268, 0.00053289, -0.000000440, -0.0000000176), t) * ARCSEC
        val psib = poly(doubleArrayOf(-0.041775, 5038.481484, 1.5584175, -0.00018522, -0.000026452, -0.0000000148), t) * ARCSEC
        val epsa = poly(doubleArrayOf(84381.406, -46.836769, -0.0001831, 0.00200340, -0.000000576, -0.0000000434), t) * ARCSEC
        val (dpsi, deps) = nutation(t)
        return mul(mul(mul(rx(-(epsa + deps)), rz(-(psib + dpsi))), rx(phib)), rz(gamb))
    }

    /** Gorunur Greenwich yildiz zamani (radyan, [0, 2pi)). */
    fun greenwichApparentSiderealTime(jd: Double): Double {
        val tu = jd - 2451545.0
        val t = tu / 36525.0
        val x = 0.7790572732640 + 1.00273781191135448 * tu
        val era = TWO_PI * (x - floor(x))
        val gmst = era + poly(
            doubleArrayOf(0.014506, 4612.156534, 1.3915817, -0.00000044, -0.000029956, -0.0000000368), t
        ) * ARCSEC
        val (dpsi, _) = nutation(t)
        val epsa = poly(doubleArrayOf(84381.406, -46.836769, -0.0001831, 0.00200340), t) * ARCSEC
        val g = gmst + dpsi * cos(epsa)
        return g - TWO_PI * floor(g / TWO_PI)
    }

    // ------------------------------------------------------------ yonelim ve girdiler

    /**
     * Kamera -> ICRS donusum matrisi. Roll: goruntu-yukarisinin konum acisi (kuzeyden doguya, derece).
     * Plate solver'in roll tanimi farkli olabilir; rollSign = -1 ile cevrilir.
     */
    fun rotationFromRadecRoll(raDeg: Double, decDeg: Double, rollDeg: Double, rollSign: Double = 1.0): Array<DoubleArray> {
        val a = Math.toRadians(raDeg)
        val d = Math.toRadians(decDeg)
        val th = Math.toRadians(rollDeg) * rollSign
        val f = doubleArrayOf(cos(d) * cos(a), cos(d) * sin(a), sin(d))
        val north = doubleArrayOf(-sin(d) * cos(a), -sin(d) * sin(a), cos(d))
        val east = doubleArrayOf(-sin(a), cos(a), 0.0)
        val up = DoubleArray(3) { cos(th) * north[it] + sin(th) * east[it] }
        val x = cross(f, up) // goruntu sagi
        return arrayOf(
            doubleArrayOf(x[0], -up[0], f[0]),
            doubleArrayOf(x[1], -up[1], f[1]),
            doubleArrayOf(x[2], -up[2], f[2])
        )
    }

    /**
     * Egim acilarindan zenit yonu (kamera ekseninde). pitchDeg: bakisin ufuk ustu yuksekligi;
     * rollDeg: + = saat yonu.
     */
    fun upFromTilt(pitchDeg: Double, rollDeg: Double = 0.0): DoubleArray {
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)
        return unit(doubleArrayOf(-sin(r) * cos(p), -cos(r) * cos(p), sin(p)))
    }

    /**
     * Ivmeolcer/yercekimi okumasindan zenit yonu (okuma KAMERA ekseninde olmali).
     * Android'de duran cihazda sensor yukari bakar (sensorPointsUp = true).
     */
    fun upFromGravity(x: Double, y: Double, z: Double, sensorPointsUp: Boolean = true): DoubleArray {
        val v = unit(doubleArrayOf(x, y, z))
        return if (sensorPointsUp) v else doubleArrayOf(-v[0], -v[1], -v[2])
    }

    // ------------------------------------------------------------ cozum

    fun solvePosition(celFromCam: Array<DoubleArray>, upCam: DoubleArray, epochMillis: Long): ZenithPositionFix {
        val jd = julianDateUtc(epochMillis)
        val zen = mulVec(precessionNutationMatrix(jd), mulVec(celFromCam, unit(upCam)))
        val lat = Math.toDegrees(asin(max(-1.0, min(1.0, zen[2]))))
        val ra = atan2(zen[1], zen[0])
        val lonRaw = Math.toDegrees(ra - greenwichApparentSiderealTime(jd))
        val lon = (((lonRaw + 180.0) % 360.0) + 360.0) % 360.0 - 180.0
        return ZenithPositionFix(lat, lon)
    }

    /** Bilinen konum+zamanda zenit yonu, ICRS (solvePosition'in tersi). Sensor kalibrasyonu icin. */
    fun zenithIcrs(latDeg: Double, lonDeg: Double, epochMillis: Long): DoubleArray {
        val jd = julianDateUtc(epochMillis)
        val lst = Math.toRadians(lonDeg) + greenwichApparentSiderealTime(jd)
        val la = Math.toRadians(latDeg)
        val zTd = doubleArrayOf(cos(la) * cos(lst), cos(la) * sin(lst), sin(la))
        val p = precessionNutationMatrix(jd)
        // P: ICRS -> true-of-date, ters = devrik
        return DoubleArray(3) { i -> p[0][i] * zTd[0] + p[1][i] * zTd[1] + p[2][i] * zTd[2] }
    }

    fun greatCircleKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin((p2 - p1) / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(h)))
    }

    /** Kameranin ufuk ustu yuksekligi (derece); negatifse egim/isaret hatasi olabilir. */
    fun boresightAltitudeDeg(upCam: DoubleArray): Double =
        Math.toDegrees(asin(max(-1.0, min(1.0, unit(upCam)[2]))))
}
