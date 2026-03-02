package com.kutup.navigasyon

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
 * Offline world map with simple continent silhouettes + markers.
 * Supports pan/zoom and fullscreen expand.
 */
class MiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#07111F") }
    private val seaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#10263F") }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E6B45") }
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
        textSize = 24f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var markers: List<MapMarker> = emptyList()
    private var userLabel: String = ""

    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 5f)
            invalidate()
            return true
        }
    })

    private val continents = listOf(
        // North America
        listOf(
            72.0 to -168.0, 60.0 to -140.0, 50.0 to -125.0, 35.0 to -117.0, 25.0 to -110.0,
            15.0 to -100.0, 10.0 to -85.0, 20.0 to -80.0, 35.0 to -78.0, 50.0 to -65.0,
            60.0 to -75.0, 70.0 to -100.0, 72.0 to -130.0
        ),
        // South America
        listOf(
            12.0 to -82.0, 5.0 to -75.0, -5.0 to -70.0, -20.0 to -65.0, -35.0 to -60.0,
            -50.0 to -65.0, -55.0 to -72.0, -40.0 to -76.0, -20.0 to -74.0, -2.0 to -78.0
        ),
        // Eurasia
        listOf(
            70.0 to -10.0, 65.0 to 20.0, 60.0 to 60.0, 55.0 to 100.0, 50.0 to 130.0,
            45.0 to 145.0, 35.0 to 140.0, 30.0 to 120.0, 25.0 to 100.0, 20.0 to 80.0,
            15.0 to 60.0, 10.0 to 45.0, 5.0 to 35.0, 0.0 to 25.0, 5.0 to 10.0,
            15.0 to -5.0, 35.0 to -10.0, 50.0 to -10.0
        ),
        // Africa
        listOf(
            35.0 to -15.0, 30.0 to 10.0, 20.0 to 30.0, 10.0 to 40.0, 0.0 to 45.0,
            -10.0 to 40.0, -20.0 to 32.0, -30.0 to 20.0, -35.0 to 10.0, -30.0 to 0.0,
            -20.0 to -10.0, -5.0 to -15.0, 10.0 to -10.0
        ),
        // Australia
        listOf(
            -12.0 to 113.0, -18.0 to 128.0, -25.0 to 140.0, -33.0 to 151.0,
            -40.0 to 145.0, -42.0 to 130.0, -35.0 to 117.0, -25.0 to 113.0
        ),
        // Greenland
        listOf(
            82.0 to -52.0, 78.0 to -40.0, 72.0 to -30.0, 65.0 to -35.0,
            60.0 to -45.0, 62.0 to -55.0, 70.0 to -60.0
        ),
        // Antarctica (band)
        listOf(
            -62.0 to -180.0, -64.0 to -120.0, -66.0 to -60.0, -68.0 to 0.0,
            -66.0 to 60.0, -64.0 to 120.0, -62.0 to 180.0, -90.0 to 180.0, -90.0 to -180.0
        )
    )

    fun setMapData(markers: List<MapMarker>, userLabel: String) {
        this.markers = markers
        this.userLabel = userLabel
        invalidate()
    }

    fun getMarkers(): List<MapMarker> = markers
    fun getUserLabel(): String = userLabel

    fun resetView() {
        scaleFactor = 1f
        offsetX = 0f
        offsetY = 0f
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && isPanning) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    offsetX += dx
                    offsetY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val mapRect = RectF(0f, 0f, w, h)
        canvas.drawRect(mapRect, seaPaint)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scaleFactor, scaleFactor, w / 2f, h / 2f)

        drawGrid(canvas, w, h)
        drawContinents(canvas, w, h)
        drawMarkers(canvas, w, h)

        canvas.restore()
        canvas.drawRect(mapRect, framePaint)

        if (userLabel.isNotBlank()) {
            canvas.drawText(userLabel, 10f, h - 10f, textPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        for (lon in -150..150 step 30) {
            val x = lonToX(lon.toDouble(), w)
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        for (lat in -60..60 step 30) {
            val y = latToY(lat.toDouble(), h)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
    }

    private fun drawContinents(canvas: Canvas, w: Float, h: Float) {
        for (poly in continents) {
            if (poly.isEmpty()) continue
            val path = Path()
            val first = poly.first()
            path.moveTo(lonToX(first.second, w), latToY(first.first, h))
            for (i in 1 until poly.size) {
                val p = poly[i]
                path.lineTo(lonToX(p.second, w), latToY(p.first, h))
            }
            path.close()
            canvas.drawPath(path, landPaint)
        }
    }

    private fun drawMarkers(canvas: Canvas, w: Float, h: Float) {
        for (m in markers) {
            val x = lonToX(m.longitude, w)
            val y = latToY(m.latitude, h)
            markerPaint.color = m.color
            canvas.drawCircle(x, y, m.radiusPx, markerPaint)
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

    companion object {
        fun showFullscreenMap(context: Context, markers: List<MapMarker>, userLabel: String) {
            val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val fullView = MiniMapView(context)
            fullView.setBackgroundColor(Color.parseColor("#04101C"))
            fullView.setMapData(markers, userLabel)
            fullView.resetView()
            dialog.setContentView(fullView)
            fullView.setOnLongClickListener {
                dialog.dismiss()
                true
            }
            dialog.show()
        }
    }
}

