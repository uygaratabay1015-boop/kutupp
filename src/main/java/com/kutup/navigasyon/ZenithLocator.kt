package com.kutup.navigasyon

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

data class ZenithOutcome(
    val ok: Boolean,
    val latitudeDeg: Double = 0.0,
    val longitudeDeg: Double = 0.0,
    /** Tahmini 68% yaricap hatasi (km). */
    val errorKm68: Double = 0.0,
    val errorKm95: Double = 0.0,
    val message: String = "",
    val plate: PlateSolution? = null,
    val boresightAltitudeDeg: Double = 0.0
)

/**
 * Gece gokyuzu karesi + telefon egimi + saat -> enlem/boylam (tamamen cihaz ici).
 * Plaka cozumu kamera->ICRS matrisini verir; ZenithFix zenit yonunden konumu cikarir.
 */
class ZenithLocator(private val solver: StarPlateSolver) {

    /**
     * @param pitchDeg kamera ufuk ustu yuksekligi, @param rollDeg + = saat yonu (TiltEstimator ile ayni)
     * @param tiltSigmaDeg egim sensoru hatasi (1 sigma, derece)
     * @param timeSigmaSec saat hatasi (1 sigma, saniye)
     */
    fun locate(
        stars: List<Star>, widthPx: Int, heightPx: Int, longSideFovDeg: Double,
        pitchDeg: Double, rollDeg: Double, epochMillis: Long,
        tiltSigmaDeg: Double, timeSigmaSec: Double, maxSolveMillis: Long = 10_000L
    ): ZenithOutcome {
        if (stars.size < 8) return ZenithOutcome(false, message = "Yeterli yildiz yok (${stars.size}); en az ~8 gerekir")
        val plate = solver.solve(stars, widthPx, heightPx, longSideFovDeg, maxMillis = maxSolveMillis)
            ?: return ZenithOutcome(false, message = "Yildizlar katalogla eslestirilemedi (bulut/isik kirliligi/yanlis gorus acisi?)")
        val up = ZenithFix.upFromTilt(pitchDeg, rollDeg)
        val alt = ZenithFix.boresightAltitudeDeg(up)
        if (alt < 5.0) return ZenithOutcome(false, message = "Egim verisi tutarsiz (kamera yuksekligi ${"%.0f".format(alt)}°)", plate = plate)
        val fix = ZenithFix.solvePosition(plate.celFromCam, up, epochMillis)
        val (e68, e95) = estimateErrorKm(fix.latitudeDeg, tiltSigmaDeg, timeSigmaSec, plate.rmsDeg)
        return ZenithOutcome(true, fix.latitudeDeg, fix.longitudeDeg, e68, e95, "Zenit yontemi", plate, alt)
    }

    companion object {
        /** Basit hata modeli: egim + plaka cozum belirsizligi her eksende, saat boylamda. 2B Gauss: 68% ~ 1.51 sigma, 95% ~ 2.45 sigma. */
        fun estimateErrorKm(latDeg: Double, tiltSigmaDeg: Double, timeSigmaSec: Double, plateRmsDeg: Double): Pair<Double, Double> {
            val kmPerDeg = 111.195
            val base = hypot(tiltSigmaDeg, maxOf(plateRmsDeg, 0.02)) * kmPerDeg
            val timeKm = timeSigmaSec * (15.0 / 3600.0) * cos(Math.toRadians(latDeg)) * kmPerDeg
            val sigma = sqrt((base * base + (base * base + timeKm * timeKm)) / 2.0)
            return Pair(1.51 * sigma, 2.45 * sigma)
        }
    }
}
