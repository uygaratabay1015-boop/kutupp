package com.kutup.navigasyon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Kamera onizlemesinin ustunde: seviye cizgisi, aci olcegi ve "hazir" halkasi. */
class AimGuideView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var state: AimState? = null
    private var guide: AimGuide = AimGuide()
    private val d = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * d; textAlign = Paint.Align.CENTER; setShadowLayer(4f * d, 0f, 0f, Color.BLACK) }
    private val arc = RectF()

    fun setGuide(g: AimGuide) { guide = g }

    fun update(s: AimState?) { state = s; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val good = if (s.ready) Color.rgb(60, 220, 90) else Color.rgb(255, 214, 10)
        val rollColor = if (s.rollOk) Color.rgb(60, 220, 90) else Color.rgb(255, 214, 10)

        // Sabit hedef cizgisi (beyaz, iki yan kisa cizgi)
        line.color = Color.argb(200, 255, 255, 255)
        val half = 90f * d
        canvas.drawLine(cx - 150f * d, cy, cx - half - 20f * d, cy, line)
        canvas.drawLine(cx + half + 20f * d, cy, cx + 150f * d, cy, line)

        // Hareketli seviye cizgisi: cihaz saat yonunde r donunce ufuk ekranda -r doner
        canvas.save()
        canvas.rotate(-s.rollDeg, cx, cy)
        line.color = rollColor
        canvas.drawLine(cx - half, cy, cx + half, cy, line)
        canvas.restore()

        // Hazir halkasi
        ring.color = Color.argb(90, 255, 255, 255)
        val r = 38f * d
        arc.set(cx - r, cy - r - 70f * d, cx + r, cy + r - 70f * d)
        canvas.drawArc(arc, 0f, 360f, false, ring)
        ring.color = good
        canvas.drawArc(arc, -90f, 360f * s.holdProgress, false, ring)

        // Yukseklik olcegi (sag kenar): 0..90 derece, yesil bant hedef araligi
        val barX = w - 28f * d
        val top = h * 0.2f; val bottom = h * 0.8f
        fun yOf(deg: Float) = bottom - (deg.coerceIn(0f, 90f) / 90f) * (bottom - top)
        line.color = Color.argb(120, 255, 255, 255)
        canvas.drawLine(barX, top, barX, bottom, line)
        line.color = Color.argb(220, 60, 220, 90)
        line.strokeWidth = 8f * d
        canvas.drawLine(barX, yOf(guide.maxPitchDeg), barX, yOf(guide.minPitchDeg), line)
        line.strokeWidth = 3f * d
        fill.color = if (s.pitchOk) Color.rgb(60, 220, 90) else Color.rgb(255, 214, 10)
        canvas.drawCircle(barX - 14f * d, yOf(s.pitchDeg), 7f * d, fill)
        text.textSize = 12f * d
        canvas.drawText("%d°".format(s.pitchDeg.toInt()), barX - 14f * d, yOf(s.pitchDeg) - 12f * d, text)

        // Mesaj
        text.textSize = 15f * d
        canvas.drawText(s.message, cx, h * 0.12f, text)
        text.textSize = 12f * d
        canvas.drawText("Egim %d°  Yatay %d°  Titreme %.2f°".format(s.pitchDeg.toInt(), s.rollDeg.toInt(), s.spreadDeg), cx, h * 0.12f + 22f * d, text)
    }
}
