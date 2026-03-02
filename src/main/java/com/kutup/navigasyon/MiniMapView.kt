package com.kutup.navigasyon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class MapMarker(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val color: Int,
    val radiusPx: Float
)

/**
 * Simple offline world map (equirectangular grid) that plots markers.
 * No network tile usage.
 */
class MiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E1A2B") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#35506E")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AB4D0")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D7E7FF")
        textSize = 26f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var markers: List<MapMarker> = emptyList()
    private var userLabel: String = ""

    fun setMapData(markers: List<MapMarker>, userLabel: String) {
        this.markers = markers
        this.userLabel = userLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Graticule every 30 degrees.
        for (lon in -150..150 step 30) {
            val x = lonToX(lon.toDouble(), w)
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        for (lat in -60..60 step 30) {
            val y = latToY(lat.toDouble(), h)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        canvas.drawRect(0f, 0f, w, h, framePaint)

        for (m in markers) {
            val x = lonToX(m.longitude, w)
            val y = latToY(m.latitude, h)
            markerPaint.color = m.color
            canvas.drawCircle(x, y, m.radiusPx, markerPaint)
        }

        if (userLabel.isNotBlank()) {
            canvas.drawText(userLabel, 10f, h - 12f, textPaint)
        }
    }

    private fun lonToX(lon: Double, width: Float): Float {
        val clamped = min(180.0, max(-180.0, lon))
        return (((clamped + 180.0) / 360.0) * width).toFloat()
    }

    private fun latToY(lat: Double, height: Float): Float {
        val clamped = min(90.0, max(-90.0, lat))
        return (((90.0 - clamped) / 180.0) * height).toFloat()
    }
}

