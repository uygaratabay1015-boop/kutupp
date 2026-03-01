package com.kutup.navigasyon

import android.graphics.Bitmap

/**
 * Yildiz Tespiti - OpenCV olmadan
 *
 * Yerel tepe + yerel arka plan farki (prominence) ile
 * dusuk isikta yildiz adayi bulur.
 */
data class Star(
    val x: Float,
    val y: Float,
    val brightness: Float
)

class StarDetector {

    fun detectStars(bitmap: Bitmap): List<Star> {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return emptyList()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(width * height)
        for (i in pixels.indices) {
            gray[i] = grayOf(pixels[i])
        }

        // Summed-area table for fast local mean.
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 1..height) {
            var rowSum = 0L
            for (x in 1..width) {
                rowSum += gray[(y - 1) * width + (x - 1)]
                integral[y * (width + 1) + x] = integral[(y - 1) * (width + 1) + x] + rowSum
            }
        }

        val rawCandidates = mutableListOf<Star>()
        val minLuma = 45
        val minProminence = 12.0
        val backgroundRadius = 4 // 9x9 window

        for (y in backgroundRadius until (height - backgroundRadius)) {
            for (x in backgroundRadius until (width - backgroundRadius)) {
                val idx = y * width + x
                val luma = gray[idx]
                if (luma < minLuma) continue
                if (!isLocalPeak(gray, width, x, y, luma)) continue

                val x0 = x - backgroundRadius
                val y0 = y - backgroundRadius
                val x1 = x + backgroundRadius
                val y1 = y + backgroundRadius
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val localMean = sumRect(integral, width, x0, y0, x1, y1).toDouble() / area
                val prominence = luma - localMean

                if (prominence >= minProminence) {
                    rawCandidates.add(Star(x.toFloat(), y.toFloat(), luma.toFloat()))
                }
            }
        }

        // Non-maximum suppression: keep brightest star in a small radius.
        val sorted = rawCandidates.sortedByDescending { it.brightness }
        val filtered = mutableListOf<Star>()
        val radius = 4f
        val radiusSq = radius * radius

        for (candidate in sorted) {
            var tooClose = false
            for (kept in filtered) {
                val dx = candidate.x - kept.x
                val dy = candidate.y - kept.y
                if (dx * dx + dy * dy <= radiusSq) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose) {
                filtered.add(candidate)
                if (filtered.size >= 400) break
            }
        }

        return filtered
    }

    private fun sumRect(
        integral: LongArray,
        width: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int
    ): Long {
        val w = width + 1
        val xa = x0
        val ya = y0
        val xb = x1 + 1
        val yb = y1 + 1
        return integral[yb * w + xb] - integral[ya * w + xb] - integral[yb * w + xa] + integral[ya * w + xa]
    }

    private fun isLocalPeak(
        gray: IntArray,
        width: Int,
        x: Int,
        y: Int,
        value: Int
    ): Boolean {
        for (ny in (y - 1)..(y + 1)) {
            for (nx in (x - 1)..(x + 1)) {
                if (nx == x && ny == y) continue
                if (gray[ny * width + nx] > value) return false
            }
        }
        return true
    }

    fun getBrightestStars(stars: List<Star>, count: Int): List<Star> {
        return stars.sortedByDescending { it.brightness }.take(count)
    }

    fun getTopStars(stars: List<Star>, count: Int): List<Star> {
        return stars.sortedBy { it.y }.take(count)
    }

    private fun grayOf(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
