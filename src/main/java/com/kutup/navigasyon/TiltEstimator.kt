package com.kutup.navigasyon

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cekim aninin eğim tahmini (cihaz uzerinde, internetsiz).
 *
 * @property upDevice zenit yonu, CIHAZ eksenlerinde (x sag, y ust, z ekrandan disari), birim vektor
 * @property pitchDeg arka kameranin ufuk ustu yuksekligi (+ = ufkun ustu)
 * @property rollDeg  kamera ekseni etrafinda donus (+ = saat yonu, sag taraf asagi)
 * @property spreadDeg pencere icindeki rms aci dagilimi (dusuk = sabit tutuldu)
 * @property quality 0..1 (1 = cok sabit ve yeterli ornek)
 */
data class TiltEstimate(
    val upDevice: DoubleArray,
    val pitchDeg: Double,
    val rollDeg: Double,
    val spreadDeg: Double,
    val sampleCount: Int,
    val usedGyro: Boolean,
    val quality: Double
)

/**
 * Ivmeolcer + jiroskop fuzyonu ve pozlama penceresi ortalamasi.
 *
 * Yontem:
 *  - Zenit vektoru u (cihaz ekseninde) jiroskopla ilerletilir:  du/dt = -w x u  (kisa surede cok hassas),
 *    ivmeolcerle yavasca duzeltilir (mutlak yon, kayma olmaz). Cihaz ivmelendiginde (|a| != g) veya
 *    hizli donerken ivmeolcerin agirligi dusurulur => el titremesi tahmini bozmaz.
 *  - Cihaz duragansa jiroskop sapmasi (bias) otomatik kestirilir.
 *  - Sonuc, [endNs - pencere, endNs] araligindaki ornekler uzerinde agirlikli ortalamadir.
 *  - Jiroskop yoksa ivmeolcer alcak-geciren filtre + pencere ortalamasina duser.
 *
 * Zaman damgalari SensorEvent.timestamp (ns) olmalidir. Sinif iplik guvenli DEGILDIR.
 */
class TiltEstimator(private val config: Config = Config()) {

    data class Config(
        val gravity: Double = 9.80665,
        /** Jiroskop varken ivmeolcer duzeltme zaman sabiti (sn). */
        val tauWithGyro: Double = 1.5,
        /** Jiroskop yokken ivmeolcer yumusatma zaman sabiti (sn). */
        val tauNoGyro: Double = 0.3,
        /** |a| ile g arasindaki goreli sapma (tam guven siniri). */
        val accelTolerance: Double = 0.06,
        /**
         * Hizli donuste ivmeolcer guveninin dusme olcegi (rad/s). Donus hizi yercekimi yonu olcumunu
         * bozmaz (bozan, elin DOGRUSAL ivmesidir); cok kucuk tutulursa titrek elde ivmeolcer tamamen
         * devre disi kalir ve jiroskop kayar (simulasyonda 0.5 => ~8 derece, 2.0 => ~1.4 derece).
         */
        val rateScale: Double = 2.0,
        /** Duragan sayilan donus hizi (rad/s) - bias kestirimi icin. */
        val stillRate: Double = 0.05,
        val biasAlpha: Double = 0.005,
        /**
         * Hareket halindeyken jiroskop sapmasini ivmeolcer hatasindan ogrenme kazanci
         * (Mahony integral terimi; Kp = 1/tauWithGyro icin kritik sonum ~ Kp^2/4).
         */
        val biasIntegralGain: Double = 0.1,
        val maxGyroGapSeconds: Double = 0.2,
        val historySeconds: Double = 15.0
    )

    private class Sample(val tNs: Long, val up: DoubleArray, val weight: Double)

    private var up: DoubleArray? = null
    private var lastGyroNs = Long.MIN_VALUE
    private var lastAccelNs = Long.MIN_VALUE
    private var lastRate = 0.0
    private var lastTrust = 0.0
    private var gyroSeen = false
    private val bias = doubleArrayOf(0.0, 0.0, 0.0)
    private val history = ArrayDeque<Sample>()

    val gyroBiasRadPerSec: DoubleArray get() = bias.copyOf()
    val latestTimestampNs: Long get() = history.lastOrNull()?.tNs ?: Long.MIN_VALUE

    fun reset() {
        up = null
        lastGyroNs = Long.MIN_VALUE
        lastAccelNs = Long.MIN_VALUE
        lastRate = 0.0
        lastTrust = 0.0
        gyroSeen = false
        history.clear()
    }

    /** Jiroskop orneği (rad/s, cihaz ekseni). */
    fun addGyroscope(tNs: Long, wx: Double, wy: Double, wz: Double) {
        if (!(wx.isFinite() && wy.isFinite() && wz.isFinite())) return
        val raw = doubleArrayOf(wx, wy, wz)
        val w = doubleArrayOf(wx - bias[0], wy - bias[1], wz - bias[2])
        lastRate = norm(w)

        // Duraganken bias'i ogren (gercek donus ~0 olmali).
        if (lastRate < config.stillRate && lastTrust > 0.9) {
            for (i in 0..2) bias[i] += config.biasAlpha * (raw[i] - bias[i])
        }

        val cur = up
        if (cur != null && lastGyroNs != Long.MIN_VALUE) {
            val dt = (tNs - lastGyroNs) / 1e9
            if (dt > 0.0 && dt <= config.maxGyroGapSeconds) {
                up = rotate(cur, w, -dt)
                push(tNs)
            }
        }
        lastGyroNs = tNs
        gyroSeen = true
    }

    /** Ivmeolcer ornegi (m/s^2, cihaz ekseni; duran cihazda YUKARI bakar). */
    fun addAccelerometer(tNs: Long, ax: Double, ay: Double, az: Double) {
        if (!(ax.isFinite() && ay.isFinite() && az.isFinite())) return
        val a = doubleArrayOf(ax, ay, az)
        val n = norm(a)
        if (n < 1e-6) return
        val ahat = doubleArrayOf(ax / n, ay / n, az / n)

        val dev = abs(n - config.gravity) / config.gravity
        var trust = exp(-(dev / config.accelTolerance) * (dev / config.accelTolerance))
        val gyroRecent = gyroSeen && lastGyroNs != Long.MIN_VALUE && (tNs - lastGyroNs) < 300_000_000L
        if (gyroRecent) {
            trust *= exp(-(lastRate / config.rateScale) * (lastRate / config.rateScale))
        }
        lastTrust = trust

        val cur = up
        if (cur == null) {
            up = ahat
            lastAccelNs = tNs
            push(tNs)
            return
        }

        val dt = if (lastAccelNs == Long.MIN_VALUE) 0.02 else ((tNs - lastAccelNs) / 1e9).coerceIn(0.0, 0.5)
        lastAccelNs = tNs
        val alpha = if (gyroRecent) {
            dt / (config.tauWithGyro + dt) * trust
        } else {
            dt / (config.tauNoGyro + dt) * max(trust, 0.2)
        }
        if (gyroRecent) {
            // Kalici hata vektoru e = ahat x u, jiroskop sapmasinin izidir; b <- b - Ki * trust * e * dt.
            // (Donus ekseni zenit etrafinda gozlenemez, ancak yalniz zenit yonu onemlidir.)
            val e = doubleArrayOf(
                ahat[1] * cur[2] - ahat[2] * cur[1],
                ahat[2] * cur[0] - ahat[0] * cur[2],
                ahat[0] * cur[1] - ahat[1] * cur[0]
            )
            for (i in 0..2) bias[i] -= config.biasIntegralGain * trust * e[i] * dt
        }
        up = unit(doubleArrayOf(
            cur[0] + alpha * (ahat[0] - cur[0]),
            cur[1] + alpha * (ahat[1] - cur[1]),
            cur[2] + alpha * (ahat[2] - cur[2])
        ))
        push(tNs)
    }

    /**
     * [endNs - windowSeconds, endNs] araligindaki fuzyon durumlarinin agirlikli ortalamasi.
     * Yeterli ornek yoksa null.
     */
    fun estimate(endNs: Long, windowSeconds: Double = 1.0, minSamples: Int = 5): TiltEstimate? {
        val startNs = endNs - (windowSeconds * 1e9).toLong()
        val samples = history.filter { it.tNs in startNs..endNs }
        if (samples.size < minSamples) return null

        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var sw = 0.0
        for (s in samples) {
            sx += s.weight * s.up[0]
            sy += s.weight * s.up[1]
            sz += s.weight * s.up[2]
            sw += s.weight
        }
        if (sw <= 0.0) return null
        val mean = unit(doubleArrayOf(sx / sw, sy / sw, sz / sw))

        var acc = 0.0
        for (s in samples) {
            val ang = acos((s.up[0] * mean[0] + s.up[1] * mean[1] + s.up[2] * mean[2]).coerceIn(-1.0, 1.0))
            acc += s.weight * ang * ang
        }
        val spreadDeg = Math.toDegrees(sqrt(acc / sw))
        val coverage = min(1.0, samples.size / 10.0)
        val quality = exp(-(spreadDeg / 1.0) * (spreadDeg / 1.0)) * coverage

        val pitch = Math.toDegrees(asin((-mean[2]).coerceIn(-1.0, 1.0)))
        val roll = Math.toDegrees(atan2(-mean[0], mean[1]))
        return TiltEstimate(mean, pitch, roll, spreadDeg, samples.size, gyroSeen, quality)
    }

    /** En son orneğe kadar olan pencere (cihaz/sensor saat tabani farklarindan bagimsiz). */
    fun estimateLatest(windowSeconds: Double = 1.0, minSamples: Int = 5): TiltEstimate? {
        val end = latestTimestampNs
        if (end == Long.MIN_VALUE) return null
        return estimate(end, windowSeconds, minSamples)
    }

    private fun push(tNs: Long) {
        val u = up ?: return
        history.addLast(Sample(tNs, u.copyOf(), max(0.05, lastTrust)))
        val minNs = tNs - (config.historySeconds * 1e9).toLong()
        while (history.isNotEmpty() && history.first().tNs < minNs) history.removeFirst()
    }

    companion object {
        /**
         * Cihaz eksenindeki zenit vektorunu KAMERA eksenine (OpenCV: x sag, y asagi, z bakis) cevirir.
         * @param displayRotationDeg goruntunun dik gorunmesi icin kullanilan ekran donusu: 0, 90, 180, 270
         *        (Surface.ROTATION_0/90/180/270). Arka kamera bakis yonu = cihaz -z.
         */
        fun upInCamera(upDevice: DoubleArray, displayRotationDeg: Int): DoubleArray {
            val (rx, ry, ux, uy) = when (((displayRotationDeg % 360) + 360) % 360) {
                90 -> doubleArrayOf(0.0, -1.0, 1.0, 0.0)      // sag = -y, yukari = +x
                180 -> doubleArrayOf(-1.0, 0.0, 0.0, -1.0)    // sag = -x, yukari = -y
                270 -> doubleArrayOf(0.0, 1.0, -1.0, 0.0)     // sag = +y, yukari = -x
                else -> doubleArrayOf(1.0, 0.0, 0.0, 1.0)     // sag = +x, yukari = +y
            }
            val right = rx * upDevice[0] + ry * upDevice[1]
            val imageUp = ux * upDevice[0] + uy * upDevice[1]
            return doubleArrayOf(right, -imageUp, -upDevice[2])
        }

        /** Kamera ekseninde zenit vektorunden (yukseklik, roll) derece. roll: + = saat yonu. */
        fun pitchRollFromCameraUp(upCam: DoubleArray): Pair<Double, Double> {
            val pitch = Math.toDegrees(asin(upCam[2].coerceIn(-1.0, 1.0)))
            val roll = Math.toDegrees(atan2(-upCam[0], -upCam[1]))
            return pitch to roll
        }

        fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
            1 -> 90   // Surface.ROTATION_90
            2 -> 180  // Surface.ROTATION_180
            3 -> 270  // Surface.ROTATION_270
            else -> 0
        }

        private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

        private fun unit(v: DoubleArray): DoubleArray {
            val n = norm(v)
            return if (n > 0.0) doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) else v
        }

        /** v'yi w ekseni etrafinda |w|*dt kadar (Rodrigues) dondurur. */
        private fun rotate(v: DoubleArray, w: DoubleArray, dt: Double): DoubleArray {
            val wn = norm(w)
            if (wn < 1e-12) return v
            val theta = wn * dt
            val k = doubleArrayOf(w[0] / wn, w[1] / wn, w[2] / wn)
            val c = cos(theta)
            val s = sin(theta)
            val kv = k[0] * v[0] + k[1] * v[1] + k[2] * v[2]
            val cr = doubleArrayOf(
                k[1] * v[2] - k[2] * v[1],
                k[2] * v[0] - k[0] * v[2],
                k[0] * v[1] - k[1] * v[0]
            )
            return unit(doubleArrayOf(
                v[0] * c + cr[0] * s + k[0] * kv * (1 - c),
                v[1] * c + cr[1] * s + k[1] * kv * (1 - c),
                v[2] * c + cr[2] * s + k[2] * kv * (1 - c)
            ))
        }
    }
}
