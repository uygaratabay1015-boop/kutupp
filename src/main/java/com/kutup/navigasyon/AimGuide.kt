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
    val message: String,
    val spreadDeg: Float = 0f
)

/**
 * "Telefonu su aciyla tut" rehberi (iPhone kamera su terazisi mantigi). Saf mantik, Android'siz.
 *
 * Hedef aralik gorus acisina gore secilir: karenin en alt kenari ufuktan en az [EDGE_MARGIN_DEG]
 * derece yukarida kalmali (ufka yakin yildizlarda atmosfer kirilmasi/sis plaka cozumunu bozar).
 * Ustte 80 dereceyi gecmeyiz: dike yakinken roll tanimsizlasir ve elde tutmak zorlasir.
 * Egim sensoru hatasi pitch'ten bagimsiz (1° ~ 111 km), bu yuzden pitch secimi ikincil;
 * asil olan titremesizlik ([steadySpreadDeg]).
 */
class AimGuide(
    minPitchDeg: Float = 50f,
    maxPitchDeg: Float = 80f,
    val rollTolDeg: Float = 6f,
    val steadySpreadDeg: Float = 0.35f,
    val holdMs: Long = 1000L,
    val graceMs: Long = 400L
) {
    var minPitchDeg: Float = minPitchDeg
        private set
    var maxPitchDeg: Float = maxPitchDeg
        private set

    /** Uzun kenar gorus acisina (derece) gore hedef araligi ayarlar. */
    fun setFov(longSideFovDeg: Float) {
        val lo = (longSideFovDeg / 2f + EDGE_MARGIN_DEG).coerceIn(45f, 65f)
        minPitchDeg = lo
        maxPitchDeg = 85f
    }

    private var okSinceMs: Long = -1L
    private var lastOkMs: Long = -1L

    fun reset() { okSinceMs = -1L; lastOkMs = -1L }

    companion object { const val EDGE_MARGIN_DEG = 22f }

    fun evaluate(tilt: CameraTilt?, nowMs: Long): AimState {
        if (tilt == null) {
            okSinceMs = -1L; lastOkMs = -1L
            return AimState(0f, 0f, false, false, false, 0f, false, "Sensorler hazirlaniyor… telefonu yavasca tutun")
        }
        val pitchOk = tilt.pitchDeg in minPitchDeg..maxPitchDeg
        val rollOk = abs(tilt.rollDeg) <= rollTolDeg
        val steadyOk = tilt.spreadDeg <= steadySpreadDeg
        val allOk = pitchOk && rollOk && steadyOk
        if (allOk) {
            if (okSinceMs < 0) okSinceMs = nowMs
            lastOkMs = nowMs
        } else if (lastOkMs >= 0 && nowMs - lastOkMs > graceMs) {
            // Kisa bir sapmada ilerleme sifirlanmaz (el dogal titremesi)
            okSinceMs = -1L; lastOkMs = -1L
        }
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
        return AimState(tilt.pitchDeg, tilt.rollDeg, pitchOk, rollOk, steadyOk, progress, ready, message, tilt.spreadDeg)
    }
}
