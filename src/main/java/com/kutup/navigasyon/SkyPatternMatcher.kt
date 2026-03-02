package com.kutup.navigasyon

import org.json.JSONArray
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.hypot

data class PatternMatchResult(
    val confidence: Float,
    val matchedPattern: String
)

data class SkyPatternTemplate(
    val name: String,
    val hemisphere: String,
    val triangleRatios: List<Float>,
    val quadRatios: List<Float>,
    val visibleMonths: Set<Int>
)

/**
 * Heuristic constellation pattern matcher:
 * - compares normalized triangle/quad edge ratios
 * - weights score by current month visibility window
 */
class SkyPatternMatcher(private val templates: List<SkyPatternTemplate>) {

    fun match(stars: List<Star>, hemisphereMode: String, month: Int): PatternMatchResult {
        if (stars.size < 4 || templates.isEmpty()) return PatternMatchResult(0f, "-")

        val top = stars.sortedByDescending { it.brightness }.take(14)
        val observedTriangleRatios = buildTriangleRatios(top)
        val observedQuadRatios = buildQuadRatios(top)
        if (observedTriangleRatios.isEmpty()) return PatternMatchResult(0f, "-")

        var bestScore = 0f
        var bestName = "-"

        for (t in templates) {
            if (!t.hemisphere.equals(hemisphereMode, ignoreCase = true)) continue

            val triScore = compareRatioSets(observedTriangleRatios, t.triangleRatios)
            val quadScore = compareRatioSets(observedQuadRatios, t.quadRatios)
            var score = 0.75f * triScore + 0.25f * quadScore

            score += if (t.visibleMonths.contains(month)) 0.12f else -0.08f
            score = score.coerceIn(0f, 1f)

            if (score > bestScore) {
                bestScore = score
                bestName = t.name
            }
        }

        return PatternMatchResult(bestScore, bestName)
    }

    private fun buildTriangleRatios(stars: List<Star>): List<Float> {
        val ratios = mutableListOf<Float>()
        for (i in 0 until stars.size) {
            for (j in i + 1 until stars.size) {
                for (k in j + 1 until stars.size) {
                    val d1 = dist(stars[i], stars[j])
                    val d2 = dist(stars[j], stars[k])
                    val d3 = dist(stars[i], stars[k])
                    val edges = listOf(d1, d2, d3).sorted()
                    if (edges[2] < 1f) continue
                    ratios.add((edges[0] / edges[2]).coerceIn(0f, 1f))
                    ratios.add((edges[1] / edges[2]).coerceIn(0f, 1f))
                    if (ratios.size >= 20) return ratios
                }
            }
        }
        return ratios
    }

    private fun buildQuadRatios(stars: List<Star>): List<Float> {
        if (stars.size < 4) return emptyList()
        val ratios = mutableListOf<Float>()
        for (a in 0 until stars.size) {
            for (b in a + 1 until stars.size) {
                for (c in b + 1 until stars.size) {
                    for (d in c + 1 until stars.size) {
                        val ds = listOf(
                            dist(stars[a], stars[b]),
                            dist(stars[a], stars[c]),
                            dist(stars[a], stars[d]),
                            dist(stars[b], stars[c]),
                            dist(stars[b], stars[d]),
                            dist(stars[c], stars[d])
                        ).sorted()
                        if (ds.last() < 1f) continue
                        ratios.add((ds.first() / ds.last()).coerceIn(0f, 1f))
                        ratios.add((ds[2] / ds.last()).coerceIn(0f, 1f))
                        if (ratios.size >= 12) return ratios
                    }
                }
            }
        }
        return ratios
    }

    private fun compareRatioSets(observed: List<Float>, expected: List<Float>): Float {
        if (observed.isEmpty() || expected.isEmpty()) return 0f
        var sum = 0f
        for (e in expected) {
            var best = 1f
            for (o in observed) {
                val diff = abs(o - e)
                if (diff < best) best = diff
            }
            sum += (1f - best).coerceIn(0f, 1f)
        }
        return (sum / expected.size).coerceIn(0f, 1f)
    }

    private fun dist(a: Star, b: Star): Float {
        return hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    }

    companion object {
        fun fromJson(json: String): SkyPatternMatcher {
            val arr = JSONArray(json)
            val list = mutableListOf<SkyPatternTemplate>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tri = mutableListOf<Float>()
                val qa = mutableListOf<Float>()
                val months = mutableSetOf<Int>()

                val triArr = o.getJSONArray("triangleRatios")
                for (j in 0 until triArr.length()) tri.add(triArr.getDouble(j).toFloat())

                val qArr = o.getJSONArray("quadRatios")
                for (j in 0 until qArr.length()) qa.add(qArr.getDouble(j).toFloat())

                val mArr = o.getJSONArray("visibleMonths")
                for (j in 0 until mArr.length()) months.add(mArr.getInt(j))

                list.add(
                    SkyPatternTemplate(
                        name = o.getString("name"),
                        hemisphere = o.getString("hemisphere"),
                        triangleRatios = tri,
                        quadRatios = qa,
                        visibleMonths = months
                    )
                )
            }
            return SkyPatternMatcher(list)
        }

        fun currentMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    }
}

