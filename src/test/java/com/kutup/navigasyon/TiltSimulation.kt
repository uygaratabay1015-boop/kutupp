package com.kutup.navigasyon

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * El titremesi simulasyonu (test yardimcisi, JUnit'e bagli degil).
 * Gercek bir telefonun ivmeolcer/jiroskop davranisini yaklasik taklit eder:
 *  - titreme: 0.8-5.3 Hz sinuslar, her eksende [tremorDeg] rms aci
 *  - elin dogrusal ivmesi ivmeolcere eklenir (dogrultuyu bozan asil kaynak)
 *  - jiroskop sapmasi (bias) + gurultu, ivmeolcer gurultusu
 */
object TiltSimulation {
    class Result(
        val naiveAccelErrDeg: Double,      // son ivmeolcer okumasi
        val emaAccelErrDeg: Double,        // ivmeolcer ustel yumusatma (alpha 0.18)
        val instantFusedErrDeg: Double,    // fuzyon durumu, pencere sonu (OS rotation vector benzeri)
        val windowFusedErrDeg: Double,     // TiltEstimator pencere ortalamasi
        val spreadDeg: Double,
        val quality: Double
    )

    private val freqs = doubleArrayOf(0.8, 1.7, 3.1, 5.3)

    fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) /
            (sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]))
        return Math.toDegrees(acos(d.coerceIn(-1.0, 1.0)))
    }

    /** Cihaz eksenindeki gercek zenit vektoru (pitch: kamera yuksekligi, roll: saat yonu +). */
    fun upDevice(pitchDeg: Double, rollDeg: Double): DoubleArray {
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)
        // upCam = (-sin r cos p, -cos r cos p, sin p)  =>  dev = (cam_x, -cam_y, -cam_z)
        return doubleArrayOf(-sin(r) * cos(p), cos(r) * cos(p), -sin(p))
    }

    fun run(
        seed: Long,
        pitchDeg: Double = 40.0,
        rollDeg: Double = 5.0,
        tremorDeg: Double = 0.7,
        handAccelMps2: Double = 0.7,
        gyroBiasRadPerSec: Double = 0.01,
        gyroNoise: Double = 0.002,
        accelNoise: Double = 0.03,
        seconds: Double = 8.0,
        rateHz: Int = 100,
        windowSeconds: Double = 1.0,
        withGyro: Boolean = true,
        config: TiltEstimator.Config = TiltEstimator.Config()
    ): Result {
        val rnd = Random(seed)
        val up0 = upDevice(pitchDeg, rollDeg)
        // her eksen ve frekans icin genlik/faz
        val amp = Array(3) { DoubleArray(freqs.size) }
        val ph = Array(3) { DoubleArray(freqs.size) { rnd.nextDouble() * 2 * PI } }
        val accPh = Array(3) { DoubleArray(freqs.size) { rnd.nextDouble() * 2 * PI } }
        for (ax in 0..2) for (k in freqs.indices) {
            amp[ax][k] = Math.toRadians(tremorDeg) * sqrt(2.0 / freqs.size)
        }
        val bias = doubleArrayOf(gyroBiasRadPerSec, -0.8 * gyroBiasRadPerSec, 0.5 * gyroBiasRadPerSec)

        fun theta(t: Double) = DoubleArray(3) { ax ->
            var s = 0.0
            for (k in freqs.indices) s += amp[ax][k] * sin(2 * PI * freqs[k] * t + ph[ax][k])
            s
        }
        fun omega(t: Double) = DoubleArray(3) { ax ->
            var s = 0.0
            for (k in freqs.indices) s += amp[ax][k] * 2 * PI * freqs[k] * cos(2 * PI * freqs[k] * t + ph[ax][k])
            s
        }
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
        )
        fun upTrue(t: Double): DoubleArray {
            val th = theta(t)
            val c = cross(th, up0)
            val v = doubleArrayOf(up0[0] - c[0], up0[1] - c[1], up0[2] - c[2])
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }
        // elin dogrusal ivmesi: genlik handAccel (rms), rastgele yon
        fun handAccel(t: Double) = DoubleArray(3) { ax ->
            var s = 0.0
            for (k in freqs.indices) s += sin(2 * PI * freqs[k] * t + accPh[ax][k])
            s * handAccelMps2 * sqrt(2.0 / freqs.size)
        }

        val est = TiltEstimator(config)
        val g = 9.80665
        val dtNs = (1e9 / rateHz).toLong()
        val steps = (seconds * rateHz).toInt()
        var ema: DoubleArray? = null
        var lastAcc = DoubleArray(3)
        val truthWin = ArrayList<DoubleArray>()
        val endT = (steps - 1) / rateHz.toDouble()

        for (i in 0 until steps) {
            val t = i / rateHz.toDouble()
            val tNs = i * dtNs
            val u = upTrue(t)
            val ha = handAccel(t)
            val ax = g * u[0] + ha[0] + rnd.nextGaussian() * accelNoise
            val ay = g * u[1] + ha[1] + rnd.nextGaussian() * accelNoise
            val az = g * u[2] + ha[2] + rnd.nextGaussian() * accelNoise
            lastAcc = doubleArrayOf(ax, ay, az)
            if (withGyro) {
                val w = omega(t)
                est.addGyroscope(
                    tNs,
                    w[0] + bias[0] + rnd.nextGaussian() * gyroNoise,
                    w[1] + bias[1] + rnd.nextGaussian() * gyroNoise,
                    w[2] + bias[2] + rnd.nextGaussian() * gyroNoise
                )
            }
            est.addAccelerometer(tNs + 1_000_000L, ax, ay, az)
            val n = sqrt(ax * ax + ay * ay + az * az)
            val ah = doubleArrayOf(ax / n, ay / n, az / n)
            ema = if (ema == null) ah else doubleArrayOf(
                ema[0] + 0.18 * (ah[0] - ema[0]), ema[1] + 0.18 * (ah[1] - ema[1]), ema[2] + 0.18 * (ah[2] - ema[2])
            )
            if (t > endT - windowSeconds) truthWin.add(u)
        }

        var tx = 0.0; var ty = 0.0; var tz = 0.0
        for (u in truthWin) { tx += u[0]; ty += u[1]; tz += u[2] }
        val truth = doubleArrayOf(tx, ty, tz)

        val window = est.estimateLatest(windowSeconds)!!
        val instant = est.estimateLatest(0.02, minSamples = 1)!!
        return Result(
            naiveAccelErrDeg = angleDeg(lastAcc, truth),
            emaAccelErrDeg = angleDeg(ema!!, truth),
            instantFusedErrDeg = angleDeg(instant.upDevice, truth),
            windowFusedErrDeg = angleDeg(window.upDevice, truth),
            spreadDeg = window.spreadDeg,
            quality = window.quality
        )
    }

    fun median(values: List<Double>): Double = values.sorted().let { it[it.size / 2] }
    fun p95(values: List<Double>): Double = values.sorted().let { it[(it.size * 95) / 100] }
}
