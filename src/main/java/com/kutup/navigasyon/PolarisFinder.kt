package com.kutup.navigasyon

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class StarScore(
    val star: Star,
    val heightScore: Float,
    val brightnessScore: Float,
    val isolationScore: Float,
    val totalScore: Float
)

class PolarisFinder {

    fun findPolaris(stars: List<Star>, imageHeight: Int, imageWidth: Int): Pair<Star, Float> {
        val scored = scoreStarsInternal(stars, imageHeight, imageWidth)
        val best = scored.firstOrNull()
        return if (best != null) {
            Pair(best.star, best.totalScore)
        } else {
            Pair(stars.maxByOrNull { it.brightness } ?: Star(0f, 0f, 0f), 0f)
        }
    }

    fun scoreStars(stars: List<Star>, imageHeight: Int): List<StarScore> {
        // Legacy API kept for call sites that only pass height.
        return scoreStarsInternal(stars, imageHeight, imageHeight)
    }

    fun scoreStars(stars: List<Star>, imageHeight: Int, imageWidth: Int): List<StarScore> {
        return scoreStarsInternal(stars, imageHeight, imageWidth)
    }

    private fun scoreStarsInternal(stars: List<Star>, imageHeight: Int, imageWidth: Int): List<StarScore> {
        val candidates = if (stars.size > 30) {
            stars.sortedByDescending { it.brightness }.take(30)
        } else {
            stars
        }
        if (candidates.isEmpty()) return emptyList()

        return candidates.map { star ->
            val centerY = imageHeight / 2f
            val verticalPosition = (centerY - star.y) / imageHeight.toFloat()
            val heightScore = (verticalPosition + 0.5f).coerceIn(0f, 1f)
            val brightnessScore = (star.brightness / 255f).coerceIn(0f, 1f)
            val isolationScore = calculateIsolationScore(star, candidates, imageHeight, imageWidth)
            val companionScore = calculateCompanionScore(star, candidates, imageHeight, imageWidth)

            val totalScore =
                (0.30f * heightScore) +
                (0.25f * brightnessScore) +
                (0.20f * isolationScore) +
                (0.25f * companionScore)

            StarScore(
                star = star,
                heightScore = heightScore,
                brightnessScore = brightnessScore,
                isolationScore = isolationScore,
                totalScore = totalScore.coerceIn(0f, 1f)
            )
        }.sortedByDescending { it.totalScore }
    }

    private fun calculateIsolationScore(star: Star, allStars: List<Star>, imageHeight: Int, imageWidth: Int): Float {
        val distances = allStars
            .asSequence()
            .filter { it != star }
            .map { other -> hypot((star.x - other.x).toDouble(), (star.y - other.y).toDouble()) }
            .sorted()
            .toList()
        if (distances.isEmpty()) return 0f

        val avgDistance = if (distances.size >= 5) {
            distances.take(5).average()
        } else {
            distances.average()
        }

        val diagonal = hypot(imageWidth.toDouble(), imageHeight.toDouble()).coerceAtLeast(1.0)
        val normalized = (avgDistance / diagonal).toFloat().coerceIn(0f, 1f)

        // Polaris-like candidates tend to be neither isolated noise nor dense clusters.
        val expected = 0.08f
        val tolerance = 0.07f
        return (1f - abs(normalized - expected) / tolerance).coerceIn(0f, 1f)
    }

    private fun calculateCompanionScore(star: Star, allStars: List<Star>, imageHeight: Int, imageWidth: Int): Float {
        val diagonal = hypot(imageWidth.toDouble(), imageHeight.toDouble()).toFloat().coerceAtLeast(1f)
        val minR = diagonal * 0.02f
        val maxR = diagonal * 0.20f
        var nearby = 0
        var brightCompanions = 0

        for (other in allStars) {
            if (other == star) continue
            val dx = star.x - other.x
            val dy = star.y - other.y
            val d = sqrt(dx * dx + dy * dy)
            if (d in minR..maxR) {
                nearby += 1
                if (other.brightness >= 80f) brightCompanions += 1
            }
        }

        val countScore = when {
            nearby in 2..6 -> 1f
            nearby == 1 || nearby == 7 -> 0.75f
            nearby == 8 -> 0.55f
            else -> 0.25f
        }
        val brightScore = (brightCompanions / 3f).coerceIn(0f, 1f)
        val verticalBias = (1f - (star.y / imageHeight.toFloat())).coerceIn(0f, 1f)
        return (0.45f * countScore + 0.35f * brightScore + 0.20f * verticalBias).coerceIn(0f, 1f)
    }
}
