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
    ): ZenithOutcome = locate(
        stars, widthPx, heightPx, longSideFovDeg, ZenithFix.upFromTilt(pitchDeg, rollDeg),
        epochMillis, tiltSigmaDeg, timeSigmaSec, maxSolveMillis
    )

    /** Dogrudan zenit yonu (kamera ekseninde, kalibrasyon uygulanmis olabilir). */
    fun locate(
        stars: List<Star>, widthPx: Int, heightPx: Int, longSideFovDeg: Double,
        upCam: DoubleArray, epochMillis: Long,
        tiltSigmaDeg: Double, timeSigmaSec: Double, maxSolveMillis: Long = 10_000L,
        correctRefraction: Boolean = true
    ): ZenithOutcome {
        if (stars.size < 8) return ZenithOutcome(false, message = "Yeterli yildiz yok (${stars.size}); en az ~8 gerekir")
        val plate = solvePlate(stars, widthPx, heightPx, longSideFovDeg, maxSolveMillis)
            ?: return ZenithOutcome(false, message = "Yildizlar katalogla eslestirilemedi (bulut/isik kirliligi/yanlis gorus acisi?)")
        val alt = ZenithFix.boresightAltitudeDeg(upCam)
        if (alt < 5.0) return ZenithOutcome(false, message = "Egim verisi tutarsiz (kamera yuksekligi ${"%.0f".format(alt)}°)", plate = plate)
        val plateFinal = if (correctRefraction) solver.correctForRefraction(stars, widthPx, heightPx, plate, upCam, epochMillis) else plate
        val fix = ZenithFix.solvePosition(plateFinal.celFromCam, upCam, epochMillis)
        val (e68, e95) = estimateErrorKm(fix.latitudeDeg, tiltSigmaDeg, timeSigmaSec, plate.rmsDeg)
        return ZenithOutcome(true, fix.latitudeDeg, fix.longitudeDeg, e68, e95, "Zenit yontemi", plateFinal, alt)
    }

    fun solvePlate(stars: List<Star>, widthPx: Int, heightPx: Int, longSideFovDeg: Double, maxSolveMillis: Long = 10_000L): PlateSolution? =
        solver.solve(stars, widthPx, heightPx, longSideFovDeg, maxMillis = maxSolveMillis)

    /**
     * Kalibrasyon karesi: bilinen konum/saat + yildiz cozumu -> gercek zenit yonu (kamera ekseninde).
     * @return null: yildizlar cozulemedi.
     */
    fun trueUpFromKnownSite(
        stars: List<Star>, widthPx: Int, heightPx: Int, longSideFovDeg: Double,
        knownLatDeg: Double, knownLonDeg: Double, epochMillis: Long, maxSolveMillis: Long = 10_000L
    ): DoubleArray? {
        val plate0 = solvePlate(stars, widthPx, heightPx, longSideFovDeg, maxSolveMillis) ?: return null
        val z = ZenithFix.zenithIcrs(knownLatDeg, knownLonDeg, epochMillis)
        fun camUp(r: Array<DoubleArray>) = DoubleArray(3) { k -> r[0][k] * z[0] + r[1][k] * z[1] + r[2][k] * z[2] } // cam = R^T cel
        val plate = solver.correctForRefraction(stars, widthPx, heightPx, plate0, camUp(plate0.celFromCam), epochMillis)
        return camUp(plate.celFromCam)
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
