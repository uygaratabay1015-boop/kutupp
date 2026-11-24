package com.kutup.navigasyon

import kotlin.math.abs

/** Ekranda gosterilecek hedefleme durumu. */
data class AimState(
    val pitchDeg: Float,
    val rollDeg: Float,
    val pitchOk: Boolean,
    val rollOk: Boolean,
    val steadyOk: Boolean,
    /** 0..1: hepsi dogruyken sabit kalma ilerlemesi. */
    val holdProgress: Float,
    val ready: Boolean,
    val message: String
)

/**
 * "Telefonu su aciyla tut" rehberi (iPhone kamera su terazisi mantigi). Saf mantik, Android'siz.
 *
 * Hedef: kamera ufkun [minPitchDeg]..[maxPitchDeg] derece ustunde (hem yildiz cok, hem roll iyi tanimli;
 * 90'a yakin roll belirsizlesir), roll ~0, ve [holdMs] boyunca titremesiz.
 */
class AimGuide(
    val minPitchDeg: Float = 40f,
    val maxPitchDeg: Float = 70f,
    val rollTolDeg: Float = 3f,
    val steadySpreadDeg: Float = 0.3f,
    val holdMs: Long = 2000L
) {
    private var okSinceMs: Long = -1L

    fun reset() { okSinceMs = -1L }

    fun evaluate(tilt: CameraTilt?, nowMs: Long): AimState {
        if (tilt == null) {
            okSinceMs = -1L
            return AimState(0f, 0f, false, false, false, 0f, false, "Sensorler hazirlaniyor… telefonu yavasca tutun")
        }
        val pitchOk = tilt.pitchDeg in minPitchDeg..maxPitchDeg
        val rollOk = abs(tilt.rollDeg) <= rollTolDeg
        val steadyOk = tilt.spreadDeg <= steadySpreadDeg
        val allOk = pitchOk && rollOk && steadyOk
        if (allOk) { if (okSinceMs < 0) okSinceMs = nowMs } else okSinceMs = -1L
        val progress = if (okSinceMs < 0) 0f else ((nowMs - okSinceMs).toFloat() / holdMs).coerceIn(0f, 1f)
        val ready = allOk && progress >= 1f
        val message = when {
            tilt.pitchDeg < minPitchDeg -> "Telefonu gokyuzune dogru kaldirin (%d° - %d° arasi)".format(minPitchDeg.toInt(), maxPitchDeg.toInt())
            tilt.pitchDeg > maxPitchDeg -> "Biraz asagi indirin (%d° - %d° arasi)".format(minPitchDeg.toInt(), maxPitchDeg.toInt())
            !rollOk -> if (tilt.rollDeg > 0) "Telefonu sola yatirin: cizgiyi yatay yapin" else "Telefonu saga yatirin: cizgiyi yatay yapin"
            !steadyOk -> "Titriyor: sabitleyin (tripod veya bir yere dayayin)"
            !ready -> "Sabit tutun…"
            else -> "Hazir! Simdi cekin"
        }
        return AimState(tilt.pitchDeg, tilt.rollDeg, pitchOk, rollOk, steadyOk, progress, ready, message)
    }
}
