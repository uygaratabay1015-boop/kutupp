package com.kutup.navigasyon

import kotlin.math.abs
import kotlin.math.hypot

data class SouthPoleCandidate(
    val pole: Star,
    val score: Float
)

class SouthernCrossFinder {

    fun findSouthCelestialPole(
        stars: List<Star>,
        imageHeight: Int,
        imageWidth: Int
    ): Pair<Star, Float> {
        val candidates = findSouthCelestialPoleCandidates(stars, imageHeight, imageWidth, maxCandidates = 1)
        val first = candidates.firstOrNull()
        if (first != null) return Pair(first.pole, first.score)
        val fallback = stars.maxByOrNull { it.brightness } ?: Star(imageWidth / 2f, imageHeight / 2f, 0f)
        return Pair(fallback, 0f)
    }

    fun findSouthCelestialPoleCandidates(
        stars: List<Star>,
        imageHeight: Int,
        imageWidth: Int,
        maxCandidates: Int = 8
    ): List<SouthPoleCandidate> {
        val bright = stars.sortedByDescending { it.brightness }.take(80)
        if (bright.size < 4) return emptyList()

        val all = mutableListOf<SouthPoleCandidate>()

        for (a in bright.indices) for (b in bright.indices) {
            if (a == b) continue
            val s1 = bright[a]
            val s2 = bright[b]
            if (s2.y <= s1.y) continue

            val dx = s2.x - s1.x
            val dy = s2.y - s1.y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len < imageHeight * 0.03f || len > imageHeight * 0.42f) continue

            val midX = (s1.x + s2.x) * 0.5f
            val midY = (s1.y + s2.y) * 0.5f

            var bestPerpScore = 0f
            for (c in bright.indices) for (d in bright.indices) {
                if (c == d || c == a || c == b || d == a || d == b) continue
                val c1 = bright[c]
                val c2 = bright[d]
                val cdx = c2.x - c1.x
                val cdy = c2.y - c1.y
                val clen = hypot(cdx.toDouble(), cdy.toDouble()).toFloat()
                if (clen < imageHeight * 0.01f || clen > imageHeight * 0.20f) continue

                val dot = abs(dx * cdx + dy * cdy) / (len * clen)
                val perp = (1f - dot).coerceIn(0f, 1f)

                val cmidX = (c1.x + c2.x) * 0.5f
                val cmidY = (c1.y + c2.y) * 0.5f
                val midDist = hypot((cmidX - midX).toDouble(), (cmidY - midY).toDouble()).toFloat()
                val nearMid = (1f - (midDist / (0.22f * imageHeight))).coerceIn(0f, 1f)

                val perpScore = 0.6f * perp + 0.4f * nearMid
                if (perpScore > bestPerpScore) bestPerpScore = perpScore
            }

            val k = 4.5f
            val poleX = s1.x + k * dx
            val poleY = s1.y + k * dy

            val verticality = (1f - (abs(dx) / len)).coerceIn(0f, 1f)
            val brightness = ((s1.brightness + s2.brightness) / (2f * 255f)).coerceIn(0f, 1f)
            val inFrameSoft = if (
                poleX >= -imageWidth * 0.5f && poleX <= imageWidth * 1.5f &&
                poleY >= -imageHeight * 0.5f && poleY <= imageHeight * 1.8f
            ) 1f else 0f

            val score = (0.35f * verticality + 0.25f * brightness + 0.20f * inFrameSoft + 0.20f * bestPerpScore)
                .coerceIn(0f, 1f)

            val candidate = SouthPoleCandidate(
                pole = Star(
                    x = poleX.coerceIn(0f, (imageWidth - 1).toFloat()),
                    y = poleY.coerceIn(0f, (imageHeight - 1).toFloat()),
                    brightness = maxOf(s1.brightness, s2.brightness)
                ),
                score = score
            )
            all.add(candidate)
        }

        if (all.isEmpty()) return emptyList()

        val deduped = mutableListOf<SouthPoleCandidate>()
        for (cand in all.sortedByDescending { it.score }) {
            val tooClose = deduped.any {
                val dx = it.pole.x - cand.pole.x
                val dy = it.pole.y - cand.pole.y
                dx * dx + dy * dy <= 14f * 14f
            }
            if (!tooClose) deduped.add(cand)
            if (deduped.size >= maxCandidates) break
        }

        return deduped
    }
}
