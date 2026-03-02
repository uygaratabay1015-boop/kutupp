package com.kutup.navigasyon

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class LatitudeResult(
    val latitude: Float,
    val lowerBound: Float,
    val upperBound: Float,
    val errorMargin: Float,
    val altitude: Float
)

class LatitudeSolver {

    fun calculateLatitudeFromCandidates(
        candidateYsWithWeight: List<Pair<Float, Float>>,
        imageHeight: Int,
        verticalFov: Float,
        cameraPitchDeg: Float,
        southernHemisphere: Boolean
    ): LatitudeResult {
        if (candidateYsWithWeight.isEmpty() || imageHeight <= 0) {
            return LatitudeResult(0f, -15f, 15f, 15f, 0f)
        }

        val samples = candidateYsWithWeight.map { (y, w) ->
            val altitude = yToAltitude(y, imageHeight, verticalFov, cameraPitchDeg)
            val lat = if (southernHemisphere) -abs(altitude) else altitude
            WeightedSample(lat.coerceIn(-89.9f, 89.9f), w.coerceAtLeast(0.01f))
        }

        val latitude = weightedMedian(samples)
        val mad = weightedMedianAbsoluteDeviation(samples, latitude)

        val fovUncertainty = 1.2f
        val tiltUncertainty = 1.8f
        val sampleSpread = mad * 1.35f
        val totalError = sqrt(fovUncertainty.pow(2) + tiltUncertainty.pow(2) + sampleSpread.pow(2)).coerceIn(1.2f, 12f)

        val altitudeAtMedian = if (southernHemisphere) abs(latitude) else latitude

        return LatitudeResult(
            latitude = round(latitude, 3),
            lowerBound = round(latitude - totalError, 3),
            upperBound = round(latitude + totalError, 3),
            errorMargin = round(totalError, 2),
            altitude = round(altitudeAtMedian, 3)
        )
    }

    private fun yToAltitude(
        y: Float,
        imageHeight: Int,
        verticalFov: Float,
        cameraPitchDeg: Float
    ): Float {
        val centerY = imageHeight / 2f
        val pixelOffset = centerY - y
        val degreesPerPixel = verticalFov / imageHeight
        val opticalAltitude = pixelOffset * degreesPerPixel
        return opticalAltitude + cameraPitchDeg
    }

    private data class WeightedSample(val value: Float, val weight: Float)

    private fun weightedMedian(samples: List<WeightedSample>): Float {
        val sorted = samples.sortedBy { it.value }
        val totalWeight = sorted.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.001f)
        var cumulative = 0f
        for (s in sorted) {
            cumulative += s.weight
            if (cumulative >= totalWeight * 0.5f) return s.value
        }
        return sorted.last().value
    }

    private fun weightedMedianAbsoluteDeviation(samples: List<WeightedSample>, center: Float): Float {
        val deviations = samples.map { WeightedSample(abs(it.value - center), it.weight) }
        return weightedMedian(deviations)
    }

    private fun round(value: Float, decimals: Int): Float {
        val multiplier = 10.0.pow(decimals.toDouble()).toFloat()
        return kotlin.math.round(value * multiplier) / multiplier
    }
}
