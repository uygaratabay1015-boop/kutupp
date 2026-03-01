package com.kutup.navigasyon

import android.graphics.Bitmap
import kotlin.math.hypot

/**
 * Yildiz Tespiti - OpenCV olmadan
 *
 * Bitmap piksellerinden parlak noktalari bulur ve kucuk bolgeleri
 * yildiz adayi olarak dondurur.
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
        if (width <= 0 || height <= 0) return emptyList()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val visited = BooleanArray(width * height)
        val stars = mutableListOf<Star>()

        val threshold = 180
        val minArea = 3
        val maxArea = 200

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (visited[idx]) continue

                val gray = grayOf(pixels[idx])
                if (gray < threshold) continue

                val queue = ArrayDeque<Int>()
                queue.addLast(idx)
                visited[idx] = true

                var area = 0
                var sumX = 0f
                var sumY = 0f
                var sumBrightness = 0f

                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    val cx = current % width
                    val cy = current / width
                    val cGray = grayOf(pixels[current])

                    if (cGray < threshold) continue

                    area++
                    sumX += cx
                    sumY += cy
                    sumBrightness += cGray

                    val x0 = maxOf(0, cx - 1)
                    val x1 = minOf(width - 1, cx + 1)
                    val y0 = maxOf(0, cy - 1)
                    val y1 = minOf(height - 1, cy + 1)

                    for (ny in y0..y1) {
                        for (nx in x0..x1) {
                            val nIdx = ny * width + nx
                            if (!visited[nIdx]) {
                                visited[nIdx] = true
                                queue.addLast(nIdx)
                            }
                        }
                    }
                }

                if (area in minArea..maxArea) {
                    val centerX = sumX / area
                    val centerY = sumY / area
                    val brightness = sumBrightness / area
                    stars.add(Star(centerX, centerY, brightness))
                }
            }
        }

        return stars
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
