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
        val candidates = stars.sortedByDescending { it.brightness }.take(60)
        if (candidates.size < 4) {
            val fallback = candidates.firstOrNull() ?: Star(imageWidth / 2f, imageHeight / 2f, 0f)
            return Pair(fallback, 0f)
        }

        var bestScore = -1f
        var bestPole = Star(imageWidth / 2f, imageHeight / 2f, 0f)

        for (a in candidates.indices) for (b in candidates.indices) {
            if (a == b) continue
            val s1 = candidates[a]
            val s2 = candidates[b]

            // Uzun eksen icin s2'nin s1'e gore daha "asagida" olmasini tercih et.
            if (s2.y <= s1.y) continue

            val dx = s2.x - s1.x
            val dy = s2.y - s1.y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len < imageHeight * 0.03f || len > imageHeight * 0.40f) continue

            // Kisa eksen icin eksene yakin orta noktada ve yaklasik dik cift ara.
            val midX = (s1.x + s2.x) * 0.5f
            val midY = (s1.y + s2.y) * 0.5f

            var bestPerpScore = 0f
            for (c in candidates.indices) for (d in candidates.indices) {
                if (c == d || c == a || c == b || d == a || d == b) continue
                val c1 = candidates[c]
                val c2 = candidates[d]
                val cdx = c2.x - c1.x
                val cdy = c2.y - c1.y
                val clen = hypot(cdx.toDouble(), cdy.toDouble()).toFloat()
                if (clen < imageHeight * 0.01f || clen > imageHeight * 0.18f) continue

                val dot = abs(dx * cdx + dy * cdy) / (len * clen) // 0 -> dik
                val perp = (1f - dot).coerceIn(0f, 1f)
                val cmidX = (c1.x + c2.x) * 0.5f
                val cmidY = (c1.y + c2.y) * 0.5f
                val midDist = hypot((cmidX - midX).toDouble(), (cmidY - midY).toDouble()).toFloat()
                val nearMid = (1f - (midDist / (0.20f * imageHeight))).coerceIn(0f, 1f)

                val perpScore = 0.6f * perp + 0.4f * nearMid
                if (perpScore > bestPerpScore) bestPerpScore = perpScore
            }

            // Crux uzun eksenini 4.5 kat uzatarak SCP yaklasigi.
            val k = 4.5f
            val poleX = s1.x + k * dx
            val poleY = s1.y + k * dy

            val verticality = (1f - (abs(dx) / len)).coerceIn(0f, 1f)
            val brightness = ((s1.brightness + s2.brightness) / (2f * 255f)).coerceIn(0f, 1f)
            val inFrame = if (
                poleX >= -imageWidth * 0.5f &&
                poleX <= imageWidth * 1.5f &&
                poleY >= -imageHeight * 0.5f &&
                poleY <= imageHeight * 1.8f
            ) 1f else 0f

            val score = 0.35f * verticality + 0.25f * brightness + 0.20f * inFrame + 0.20f * bestPerpScore
            if (score > bestScore) {
                bestScore = score
                bestPole = Star(
                    x = poleX.coerceIn(0f, (imageWidth - 1).toFloat()),
                    y = poleY.coerceIn(0f, (imageHeight - 1).toFloat()),
                    brightness = maxOf(s1.brightness, s2.brightness)
                )
            }
        } // axis loops

        return Pair(bestPole, bestScore.coerceAtLeast(0f))
    }
}
