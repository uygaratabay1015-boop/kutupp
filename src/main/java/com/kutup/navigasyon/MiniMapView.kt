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
 * Offline world map renderer (no internet):
 * - continent silhouettes
 * - station markers
 * - optional estimated latitude line
 * - pinch zoom + pan
 */
class MiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0D2743") }
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3C7A4F") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A6E94")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val polarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBD4EE")
        strokeWidth = 1.8f
        style = Paint.Style.STROKE
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D2E5FA")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0F7FF")
        textSize = 22f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAF4FF")
        textSize = 18f
    }

    private var markers: List<MapMarker> = emptyList()
    private var footerLabel: String = ""
    private var estimatedLatitude: Double? = null
    private var highlightedName: String? = null

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
        listOf(72.0 to -168.0, 66.0 to -150.0, 58.0 to -135.0, 50.0 to -125.0, 42.0 to -120.0, 35.0 to -117.0, 30.0 to -112.0, 25.0 to -108.0, 20.0 to -102.0, 15.0 to -95.0, 10.0 to -87.0, 15.0 to -82.0, 23.0 to -80.0, 32.0 to -79.0, 41.0 to -73.0, 49.0 to -66.0, 57.0 to -62.0, 63.0 to -74.0, 70.0 to -96.0),
        listOf(12.0 to -82.0, 8.0 to -76.0, 3.0 to -72.0, -6.0 to -68.0, -18.0 to -64.0, -30.0 to -61.0, -42.0 to -65.0, -53.0 to -71.0, -47.0 to -75.0, -35.0 to -74.0, -20.0 to -73.0, -5.0 to -77.0),
        listOf(71.0 to -10.0, 67.0 to 8.0, 64.0 to 24.0, 61.0 to 40.0, 58.0 to 60.0, 56.0 to 85.0, 53.0 to 105.0, 50.0 to 125.0, 45.0 to 140.0, 38.0 to 145.0, 32.0 to 130.0, 28.0 to 112.0, 23.0 to 98.0, 19.0 to 84.0, 14.0 to 70.0, 12.0 to 52.0, 8.0 to 38.0, 4.0 to 30.0, 7.0 to 14.0, 16.0 to 3.0, 28.0 to -8.0, 42.0 to -10.0, 56.0 to -10.0),
        listOf(36.0 to -18.0, 33.0 to -6.0, 28.0 to 6.0, 22.0 to 18.0, 15.0 to 28.0, 7.0 to 36.0, -2.0 to 42.0, -11.0 to 39.0, -20.0 to 33.0, -29.0 to 26.0, -35.0 to 18.0, -33.0 to 8.0, -29.0 to -2.0, -20.0 to -10.0, -6.0 to -14.0, 9.0 to -10.0, 20.0 to -8.0),
        listOf(-11.0 to 113.0, -18.0 to 126.0, -24.0 to 136.0, -30.0 to 146.0, -39.0 to 148.0, -43.0 to 136.0, -40.0 to 123.0, -31.0 to 114.0, -22.0 to 112.0),
        listOf(82.0 to -52.0, 78.0 to -42.0, 73.0 to -34.0, 67.0 to -30.0, 62.0 to -38.0, 61.0 to -48.0, 66.0 to -55.0, 73.0 to -60.0),
        listOf(-62.0 to -180.0, -64.0 to -130.0, -66.0 to -80.0, -68.0 to -30.0, -69.0 to 20.0, -68.0 to 70.0, -66.0 to 120.0, -64.0 to 170.0, -62.0 to 180.0, -90.0 to 180.0, -90.0 to -180.0)
    )

    fun setMapData(
        markers: List<MapMarker>,
        footerLabel: String,
        estimatedLatitude: Double? = null,
        highlightedName: String? = null
    ) {
        this.markers = markers
        this.footerLabel = footerLabel
        this.estimatedLatitude = estimatedLatitude
        this.highlightedName = highlightedName
        invalidate()
    }

    fun getMarkers(): List<MapMarker> = markers
    fun getUserLabel(): String = footerLabel

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val mapRect = RectF(0f, 0f, w, h)
        canvas.drawRect(mapRect, oceanPaint)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scaleFactor, scaleFactor, w / 2f, h / 2f)

        drawGrid(canvas, w, h)
        drawContinents(canvas, w, h)
        drawPolarLines(canvas, w, h)
        drawEstimatedLatitude(canvas, w, h)
        drawMarkers(canvas, w, h)

        canvas.restore()

        canvas.drawRect(mapRect, framePaint)
        if (footerLabel.isNotBlank()) {
            canvas.drawText(footerLabel, 10f, h - 10f, textPaint)
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

    private fun drawPolarLines(canvas: Canvas, w: Float, h: Float) {
        val northPolar = latToY(66.56, h)
        val southPolar = latToY(-66.56, h)
        canvas.drawLine(0f, northPolar, w, northPolar, polarPaint)
        canvas.drawLine(0f, southPolar, w, southPolar, polarPaint)
    }

    private fun drawEstimatedLatitude(canvas: Canvas, w: Float, h: Float) {
        val lat = estimatedLatitude ?: return
        val y = latToY(lat, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF6B6B")
            strokeWidth = 2.5f
        }
        canvas.drawLine(0f, y, w, y, p)
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

            if (m.name == highlightedName || m.name == "Tahmini") {
                canvas.drawText(m.name, x + 8f, y - 8f, labelPaint)
            }
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
            val full = MiniMapView(context)
            full.setMapData(markers, userLabel)
            full.resetView()
            dialog.setContentView(full)
            full.setOnLongClickListener {
                dialog.dismiss()
                true
            }
            dialog.show()
        }
    }
}
