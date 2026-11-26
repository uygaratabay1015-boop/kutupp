package com.kutup.navigasyon

import kotlin.math.sqrt

/**
 * Sensor sabit sapmasi (bias + sensor/kamera kayikligi) kalibrasyonu, tamamen cihaz ici.
 *
 * Bilinen konumdan cekilen karelerde: gercek zenit yonu (yildiz cozumu + bilinen konum/saat) ile
 * olculen egim yonu arasindaki fark [delta] biriktirilir. Duzeltme: up' = unit(up_olculen - ortalama delta).
 * Aci rehberi pitch/roll'u dar bir bantta tuttugu icin sabit ofset modeli yeterlidir.
 * Her ekran donusu (0/90/180/270) icin ayri tutulur.
 */
class TiltCalibration {
    private class Entry(var n: Int = 0, val sum: DoubleArray = DoubleArray(3), var sumSq: Double = 0.0)

    private val entries = HashMap<Int, Entry>()

    private fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
    private fun unit(v: DoubleArray): DoubleArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return DoubleArray(3) { v[it] / n } }

    private fun meanOf(e: Entry) = DoubleArray(3) { e.sum[it] / e.n }

    /** @return false: kare aykiri (>1.5° ortalamadan) oldugu icin reddedildi. */
    fun addFrame(rotationDeg: Int, upMeasured: DoubleArray, upTrue: DoubleArray): Boolean {
        val e = entries.getOrPut(rotationDeg) { Entry() }
        val d = sub(unit(upMeasured), unit(upTrue))
        if (e.n >= 3) {
            val m = meanOf(e); val dev = sub(d, m)
            if (sqrt(dev[0] * dev[0] + dev[1] * dev[1] + dev[2] * dev[2]) > Math.toRadians(1.5)) return false
        }
        e.n++
        for (i in 0 until 3) e.sum[i] += d[i]
        e.sumSq += d[0] * d[0] + d[1] * d[1] + d[2] * d[2]
        return true
    }

    fun frameCount(rotationDeg: Int): Int = entries[rotationDeg]?.n ?: 0
    fun isCalibrated(rotationDeg: Int, minFrames: Int = 3): Boolean = frameCount(rotationDeg) >= minFrames

    /** Kareler arasi kalan yayilma (derece); kalibrasyonun tutarliligi. */
    fun residualDeg(rotationDeg: Int): Double {
        val e = entries[rotationDeg] ?: return Double.NaN
        if (e.n < 2) return Double.NaN
        val m = meanOf(e)
        val v = e.sumSq / e.n - (m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        return Math.toDegrees(sqrt(maxOf(0.0, v)))
    }

    /** Duzeltilmis zenit yonu (kamera ekseninde). Kalibrasyon yoksa girdiyi aynen dondurur. */
    fun correct(rotationDeg: Int, upMeasured: DoubleArray): DoubleArray {
        val e = entries[rotationDeg]
        if (e == null || e.n < 1) return unit(upMeasured)
        return unit(sub(unit(upMeasured), meanOf(e)))
    }

    fun clear() = entries.clear()

    /** "rot:n:sx:sy:sz:sumsq;..." */
    fun serialize(): String = entries.entries.joinToString(";") { (r, e) ->
        "$r:${e.n}:${e.sum[0]}:${e.sum[1]}:${e.sum[2]}:${e.sumSq}"
    }

    companion object {
        fun deserialize(text: String?): TiltCalibration {
            val c = TiltCalibration()
            if (text.isNullOrBlank()) return c
            try {
                for (part in text.split(";")) {
                    val f = part.split(":")
                    if (f.size != 6) continue
                    val e = Entry(f[1].toInt(), doubleArrayOf(f[2].toDouble(), f[3].toDouble(), f[4].toDouble()), f[5].toDouble())
                    c.entries[f[0].toInt()] = e
                }
            } catch (_: NumberFormatException) {
                c.entries.clear()
            }
            return c
        }
    }
}
