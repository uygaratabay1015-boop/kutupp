package com.kutup.navigasyon

import java.io.InputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cihaz icindeki (internetsiz) yildiz katalogu: RA/Dec (J2000, derece) ve gorsel parlaklik.
 * Dosya bicimi (assets/star_catalog.csv): "ra_deg,dec_deg,vmag", '#' ile baslayan satirlar yorumdur.
 * Yildizlar parlaktan sonuka siralidir. [xyz] her yildiz icin birim vektor (x,y,z ardisik).
 */
class StarCatalog(val raDeg: DoubleArray, val decDeg: DoubleArray, val mag: DoubleArray) {
    val size: Int get() = raDeg.size
    val xyz: DoubleArray = DoubleArray(size * 3)

    init {
        for (i in 0 until size) {
            val ra = Math.toRadians(raDeg[i])
            val dec = Math.toRadians(decDeg[i])
            xyz[3 * i] = cos(dec) * cos(ra)
            xyz[3 * i + 1] = cos(dec) * sin(ra)
            xyz[3 * i + 2] = sin(dec)
        }
    }

    /** Parlakligi [maxMag] ve altinda olan yildiz sayisi (liste parlaktan sonuka siralidir). */
    fun countUpTo(maxMag: Double): Int {
        var n = 0
        while (n < size && mag[n] <= maxMag) n++
        return n
    }

    companion object {
        fun parse(stream: InputStream): StarCatalog {
            val ra = ArrayList<Double>(); val dec = ArrayList<Double>(); val mag = ArrayList<Double>()
            stream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val p = line.split(',')
                    if (p.size < 3) continue
                    val r = p[0].toDoubleOrNull() ?: continue
                    val d = p[1].toDoubleOrNull() ?: continue
                    val m = p[2].toDoubleOrNull() ?: continue
                    ra.add(r); dec.add(d); mag.add(m)
                }
            }
            val order = mag.indices.sortedBy { mag[it] }
            return StarCatalog(
                DoubleArray(order.size) { ra[order[it]] },
                DoubleArray(order.size) { dec[order[it]] },
                DoubleArray(order.size) { mag[order[it]] }
            )
        }
    }
}
