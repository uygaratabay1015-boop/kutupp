package com.kutup.navigasyon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolverStabilityTest {

    @Test
    fun latitudeSolver_isDeterministic_forSameInput() {
        val solver = LatitudeSolver()
        val candidates = listOf(
            122.4f to 0.92f,
            126.1f to 0.87f,
            124.7f to 0.83f,
            123.8f to 0.80f
        )

        val baseline = solver.calculateLatitudeFromCandidates(
            candidateYsWithWeight = candidates,
            imageHeight = 960,
            verticalFov = 53.8f,
            cameraPitchDeg = 7.2f,
            southernHemisphere = false
        )

        repeat(20) {
            val next = solver.calculateLatitudeFromCandidates(
                candidateYsWithWeight = candidates,
                imageHeight = 960,
                verticalFov = 53.8f,
                cameraPitchDeg = 7.2f,
                southernHemisphere = false
            )
            assertEquals(baseline.latitude, next.latitude, 0.0001f)
            assertEquals(baseline.errorMargin, next.errorMargin, 0.0001f)
            assertEquals(baseline.lowerBound, next.lowerBound, 0.0001f)
            assertEquals(baseline.upperBound, next.upperBound, 0.0001f)
        }
    }

    @Test
    fun skyPatternMatcher_prefersNorthUrsaTemplate_whenUrsaLikeGeometryGiven() {
        val matcher = SkyPatternMatcher(
            listOf(
                SkyPatternTemplate(
                    name = "UrsaLike",
                    hemisphere = "north",
                    triangleRatios = listOf(0.35f, 0.60f, 0.42f, 0.73f),
                    quadRatios = listOf(0.22f, 0.47f, 0.66f),
                    quadHashes = listOf(listOf(0.20f, 0.34f, 0.48f, 0.66f)),
                    visibleMonths = (1..12).toSet(),
                    peakDay = 200,
                    daySpread = 60
                ),
                SkyPatternTemplate(
                    name = "CassiopeiaLike",
                    hemisphere = "north",
                    triangleRatios = listOf(0.45f, 0.78f, 0.39f, 0.70f),
                    quadRatios = listOf(0.18f, 0.44f, 0.69f),
                    quadHashes = listOf(listOf(0.17f, 0.31f, 0.44f, 0.69f)),
                    visibleMonths = (1..12).toSet(),
                    peakDay = 200,
                    daySpread = 60
                )
            )
        )

        val stars = listOf(
            Star(100f, 80f, 255f),
            Star(140f, 100f, 210f),
            Star(180f, 120f, 200f),
            Star(220f, 145f, 190f),
            Star(160f, 70f, 170f),
            Star(200f, 90f, 160f)
        )

        val result = matcher.match(stars = stars, hemisphereMode = "north", dayOfYear = 200f)
        assertTrue("Expected non-empty pattern match", result.matchedPattern.isNotBlank())
        assertTrue("Expected confidence above minimum", result.confidence > 0.20f)
    }
}
