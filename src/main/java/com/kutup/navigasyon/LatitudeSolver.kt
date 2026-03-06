package com.kutup.navigasyon

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

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
        val pseudoPoints = candidateYsWithWeight.map { (y, w) ->
            Triple(0.5f, y, w)
        }
        return calculateLatitudeFromCandidatePoints(
            candidatePointsWithWeight = pseudoPoints,
            imageWidth = 1,
            imageHeight = imageHeight,
            verticalFov = verticalFov,
            horizontalFov = verticalFov,
            cameraPitchDeg = cameraPitchDeg,
            cameraRollDeg = 0f,
            southernHemisphere = southernHemisphere
        )
    }

    fun calculateLatitudeFromCandidatePoints(
        candidatePointsWithWeight: List<Triple<Float, Float, Float>>,
        imageWidth: Int,
        imageHeight: Int,
        verticalFov: Float,
        horizontalFov: Float,
        cameraPitchDeg: Float,
        cameraRollDeg: Float,
        southernHemisphere: Boolean
    ): LatitudeResult {
        if (candidatePointsWithWeight.isEmpty() || imageHeight <= 0 || imageWidth <= 0) {
            return LatitudeResult(0f, -15f, 15f, 15f, 0f)
        }

        val rawSamples = candidatePointsWithWeight.map { (x, y, w) ->
            val sample = pointToAltitudeAndStability(
                x = x,
                y = y,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                verticalFov = verticalFov,
                horizontalFov = horizontalFov,
                cameraPitchDeg = cameraPitchDeg,
                cameraRollDeg = cameraRollDeg
            )
            val lat = if (southernHemisphere) -abs(sample.altitude) else sample.altitude
            WeightedSample(lat, (w * sample.stabilityWeight).coerceAtLeast(0.01f))
        }
        // Asiri ekran-disi projeksiyonlardan gelen yapay kutup sonuclarini ele.
        val physicallyPlausible = rawSamples.filter { sample ->
            val a = kotlin.math.abs(sample.value)
            a in 0f..90.5f
        }
        val samples = if (physicallyPlausible.size >= 2) physicallyPlausible else rawSamples

        val initialMedian = weightedMedian(samples)
        val initialMad = weightedMedianAbsoluteDeviation(samples, initialMedian).coerceAtLeast(0.4f)
        val inlierWindow = maxOf(2.5f, 2.5f * initialMad)
        val inliers = samples.filter { abs(it.value - initialMedian) <= inlierWindow }
        val stableSamples = if (inliers.size >= 3) inliers else samples

        val latitude = weightedMedian(stableSamples)
        val mad = weightedMedianAbsoluteDeviation(stableSamples, latitude)

        val fovUncertainty = 0.9f
        val tiltUncertainty = 1.2f
        val sampleSpread = mad * 0.95f
        val totalError = sqrt(fovUncertainty.pow(2) + tiltUncertainty.pow(2) + sampleSpread.pow(2)).coerceIn(0.9f, 8f)

        val clippedLat = latitude.coerceIn(-89.9f, 89.9f)
        val altitudeAtMedian = if (southernHemisphere) abs(clippedLat) else clippedLat

        return LatitudeResult(
            latitude = round(clippedLat, 3),
            lowerBound = round((clippedLat - totalError).coerceIn(-90f, 90f), 3),
            upperBound = round((clippedLat + totalError).coerceIn(-90f, 90f), 3),
            errorMargin = round(totalError, 2),
            altitude = round(altitudeAtMedian, 3)
        )
    }

    private data class AltitudeSample(
        val altitude: Float,
        val stabilityWeight: Float
    )

    private fun pointToAltitudeAndStability(
        x: Float,
        y: Float,
        imageWidth: Int,
        imageHeight: Int,
        verticalFov: Float,
        horizontalFov: Float,
        cameraPitchDeg: Float,
        cameraRollDeg: Float
    ): AltitudeSample {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return AltitudeSample(cameraPitchDeg, 0.2f)
        }

        val centerX = imageWidth / 2f
        val centerY = imageHeight / 2f
        val halfW = imageWidth / 2f
        val halfH = imageHeight / 2f
        val clampedVFov = verticalFov.coerceIn(5f, 170f)
        val clampedHFov = horizontalFov.coerceIn(5f, 170f)
        val fy = halfH / tan(Math.toRadians((clampedVFov / 2f).toDouble())).toFloat().coerceAtLeast(1e-3f)
        val fx = halfW / tan(Math.toRadians((clampedHFov / 2f).toDouble())).toFloat().coerceAtLeast(1e-3f)
        val nx = (x - centerX) / fx
        val ny = (centerY - y) / fy

        val rollRad = Math.toRadians(cameraRollDeg.toDouble())
        val upComponent = (ny * cos(rollRad) - nx * sin(rollRad)).toFloat()

        // Astrometry.net benzeri gnomonik mantik: goruntu duzleminden isina gecip aciyi atan ile hesapla.
        val upAngleDeg = Math.toDegrees(atan(upComponent.toDouble())).toFloat()
        val apparentAltitude = cameraPitchDeg + upAngleDeg

        val refraction = atmosphericRefractionDegrees(apparentAltitude)
        val correctedAltitude = apparentAltitude - refraction

        val radial = hypot(nx.toDouble(), ny.toDouble()).toFloat()
        val offAxisPenalty = (1f - radial / 1.35f).coerceIn(0.25f, 1f)
        val highTiltPenalty = (1f - abs(upComponent) / 2.8f).coerceIn(0.4f, 1f)
        val stability = (offAxisPenalty * highTiltPenalty).coerceIn(0.2f, 1f)
        return AltitudeSample(correctedAltitude, stability)
    }

    // Bennett approximation (arcminutes) converted to degrees.
    private fun atmosphericRefractionDegrees(apparentAltitude: Float): Float {
        if (apparentAltitude < -1f || apparentAltitude > 85f) return 0f
        val denom = tan(Math.toRadians((apparentAltitude + 10.3f / (apparentAltitude + 5.11f)).toDouble()))
        if (denom == 0.0) return 0f
        val arcMinutes = 1.02 / denom
        return (arcMinutes / 60.0).toFloat().coerceIn(0f, 1.2f)
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
