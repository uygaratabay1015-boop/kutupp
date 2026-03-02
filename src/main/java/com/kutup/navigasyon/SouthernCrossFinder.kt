package com.kutup.navigasyon

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Guney Haci'ndan (Crux) Guney Gok Kutbu icin yaklasik referans noktasi uretir.
 *
 * Not: Bu astronomik olarak tam plate-solving degildir; sahada hizli yaklasik
 * konumlama icin kullanilir.
 */
class SouthernCrossFinder {

    fun findSouthCelestialPole(
        stars: List<Star>,
        imageHeight: Int,
        imageWidth: Int
    ): Pair<Star, Float> {
        val candidates = stars.sortedByDescending { it.brightness }.take(40)
        if (candidates.size < 2) {
            val fallback = candidates.firstOrNull() ?: Star(imageWidth / 2f, imageHeight / 2f, 0f)
            return Pair(fallback, 0f)
        }

        var bestScore = -1f
        var bestPole = Star(imageWidth / 2f, imageHeight / 2f, 0f)

        for (a in candidates.indices) {
            for (b in candidates.indices) {
                if (a == b) continue
                val s1 = candidates[a]
                val s2 = candidates[b]

                // Uzun eksen icin s2'nin s1'e gore daha "asagida" olmasini tercih et.
                if (s2.y <= s1.y) continue

                val dx = s2.x - s1.x
                val dy = s2.y - s1.y
                val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (len < imageHeight * 0.03f || len > imageHeight * 0.35f) continue

                // Crux uzun eksenini 4.5 kat uzatarak SCP yaklasigi.
                val k = 4.5f
                val poleX = s1.x + k * dx
                val poleY = s1.y + k * dy

                // Gok fotografina daha uygun cift icin puanlama.
                val verticality = (1f - (abs(dx) / len)).coerceIn(0f, 1f)
                val brightness = ((s1.brightness + s2.brightness) / (2f * 255f)).coerceIn(0f, 1f)

                val inFrame = if (
                    poleX >= -imageWidth * 0.5f &&
                    poleX <= imageWidth * 1.5f &&
                    poleY >= -imageHeight * 0.5f &&
                    poleY <= imageHeight * 1.8f
                ) 1f else 0f

                val score = 0.45f * verticality + 0.35f * brightness + 0.20f * inFrame
                if (score > bestScore) {
                    bestScore = score
                    bestPole = Star(
                        x = poleX.coerceIn(0f, (imageWidth - 1).toFloat()),
                        y = poleY.coerceIn(0f, (imageHeight - 1).toFloat()),
                        brightness = maxOf(s1.brightness, s2.brightness)
                    )
                }
            }
        }

        return Pair(bestPole, bestScore.coerceAtLeast(0f))
    }
}

