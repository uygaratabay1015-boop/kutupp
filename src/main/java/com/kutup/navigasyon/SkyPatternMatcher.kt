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
    val quadHashes: List<List<Float>>,
    val visibleMonths: Set<Int>,
    val peakDay: Int?,
    val daySpread: Int
)

class SkyPatternMatcher(private val templates: List<SkyPatternTemplate>) {

    fun match(stars: List<Star>, hemisphereMode: String, dayOfYear: Int): PatternMatchResult {
        if (stars.size < 4 || templates.isEmpty()) return PatternMatchResult(0f, "-")

        val top = stars.sortedByDescending { it.brightness }.take(20)
        val observedTriangleRatios = buildTriangleRatios(top)
        val observedQuadRatios = buildQuadRatios(top)
        val observedQuadHashes = buildQuadHashes(top)
        if (observedTriangleRatios.isEmpty()) return PatternMatchResult(0f, "-")

        var bestScore = 0f
        var bestName = "-"

        for (t in templates) {
            if (!t.hemisphere.equals(hemisphereMode, ignoreCase = true)) continue

            val triScore = compareRatioSets(observedTriangleRatios, t.triangleRatios)
            val quadScore = compareRatioSets(observedQuadRatios, t.quadRatios)
            val shapeScore = (0.72f * triScore + 0.28f * quadScore).coerceIn(0f, 1f)
            val geometricScore = compareQuadHashes(observedQuadHashes, t.quadHashes)

            val seasonalScore = seasonScore(t, dayOfYear)
            val score = (0.45f * shapeScore + 0.35f * geometricScore + 0.20f * seasonalScore).coerceIn(0f, 1f)

            if (score > bestScore) {
                bestScore = score
                bestName = t.name
            }
        }

        return PatternMatchResult(bestScore, bestName)
    }

    private fun buildQuadHashes(stars: List<Star>): List<List<Float>> {
        if (stars.size < 4) return emptyList()
        val hashes = mutableListOf<List<Float>>()
        for (a in 0 until stars.size) {
            for (b in a + 1 until stars.size) {
                for (c in b + 1 until stars.size) {
                    for (d in c + 1 until stars.size) {
                        val pts = listOf(stars[a], stars[b], stars[c], stars[d])
                        val ds = mutableListOf<Float>()
                        for (i in 0 until 4) {
                            for (j in i + 1 until 4) {
                                ds.add(dist(pts[i], pts[j]))
                            }
                        }
                        val sorted = ds.sorted()
                        val longest = sorted.last()
                        if (longest < 1f) continue
                        val h = listOf(
                            (sorted[0] / longest).coerceIn(0f, 1f),
                            (sorted[1] / longest).coerceIn(0f, 1f),
                            (sorted[2] / longest).coerceIn(0f, 1f),
                            (sorted[3] / longest).coerceIn(0f, 1f)
                        )
                        hashes.add(h)
                        if (hashes.size >= 30) return hashes
                    }
                }
            }
        }
        return hashes
    }

    private fun compareQuadHashes(observed: List<List<Float>>, expected: List<List<Float>>): Float {
        if (observed.isEmpty() || expected.isEmpty()) return 0f
        var sum = 0f
        for (eh in expected) {
            var best = Float.MAX_VALUE
            for (oh in observed) {
                if (oh.size != eh.size) continue
                var diff = 0f
                for (i in eh.indices) {
                    diff += abs(oh[i] - eh[i])
                }
                if (diff < best) best = diff
            }
            val localScore = (1f - (best / eh.size.toFloat())).coerceIn(0f, 1f)
            sum += localScore
        }
        return (sum / expected.size.toFloat()).coerceIn(0f, 1f)
    }

    private fun seasonScore(template: SkyPatternTemplate, dayOfYear: Int): Float {
        val peak = template.peakDay
        if (peak != null) {
            val distance = circularDayDistance(dayOfYear, peak)
            val spread = template.daySpread.coerceAtLeast(15)
            return (1f - (distance.toFloat() / spread.toFloat())).coerceIn(0f, 1f)
        }

        val month = dayToMonth(dayOfYear)
        return if (template.visibleMonths.contains(month)) 1f else 0.2f
    }

    private fun dayToMonth(dayOfYear: Int): Int {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_YEAR, dayOfYear.coerceIn(1, 366))
        return c.get(Calendar.MONTH) + 1
    }

    private fun circularDayDistance(a: Int, b: Int): Int {
        val days = 366
        val raw = abs(a - b)
        return minOf(raw, days - raw)
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
                    if (ratios.size >= 24) return ratios
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
                        if (ratios.size >= 16) return ratios
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

                val quadHashes = mutableListOf<List<Float>>()
                val hArr = o.optJSONArray("quadHashes")
                if (hArr != null) {
                    for (j in 0 until hArr.length()) {
                        val h = hArr.getJSONArray(j)
                        val one = mutableListOf<Float>()
                        for (k in 0 until h.length()) one.add(h.getDouble(k).toFloat())
                        if (one.isNotEmpty()) quadHashes.add(one)
                    }
                }

                val mArr = o.optJSONArray("visibleMonths")
                if (mArr != null) {
                    for (j in 0 until mArr.length()) months.add(mArr.getInt(j))
                }

                list.add(
                    SkyPatternTemplate(
                        name = o.getString("name"),
                        hemisphere = o.getString("hemisphere"),
                        triangleRatios = tri,
                        quadRatios = qa,
                        quadHashes = quadHashes,
                        visibleMonths = months,
                        peakDay = if (o.has("peakDay")) o.getInt("peakDay") else null,
                        daySpread = o.optInt("daySpread", 45)
                    )
                )
            }
            return SkyPatternMatcher(list)
        }

        fun currentDayOfYear(): Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    }
}
