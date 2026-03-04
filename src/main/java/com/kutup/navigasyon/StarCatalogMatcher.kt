package com.kutup.navigasyon

import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class CatalogStar(
    val name: String,
    val raDeg: Double,
    val decDeg: Double,
    val mag: Double,
    val hemisphere: String
)

private data class CatalogPoint(
    val x: Float,
    val y: Float,
    val weight: Float
)

class StarCatalogMatcher private constructor(
    private val northHashes: List<List<Float>>,
    private val southHashes: List<List<Float>>
) {

    fun match(stars: List<Star>, hemisphereMode: String): Float {
        if (stars.size < 4) return 0f
        val observed = buildObservedQuadHashes(stars)
        if (observed.isEmpty()) return 0f
        val expected = if (hemisphereMode.equals("north", ignoreCase = true)) northHashes else southHashes
        if (expected.isEmpty()) return 0f
        return compareHashSets(observed, expected)
    }

    private fun buildObservedQuadHashes(stars: List<Star>): List<List<Float>> {
        val top = stars.sortedByDescending { it.brightness }.take(22)
        val hashes = mutableListOf<List<Float>>()
        for (a in 0 until top.size) {
            for (b in a + 1 until top.size) {
                for (c in b + 1 until top.size) {
                    for (d in c + 1 until top.size) {
                        val h = quadHashFromStars(top[a], top[b], top[c], top[d])
                        if (h != null) hashes.add(h)
                        if (hashes.size >= 40) return hashes
                    }
                }
            }
        }
        return hashes
    }

    private fun quadHashFromStars(s1: Star, s2: Star, s3: Star, s4: Star): List<Float>? {
        val ds = listOf(
            dist(s1.x, s1.y, s2.x, s2.y),
            dist(s1.x, s1.y, s3.x, s3.y),
            dist(s1.x, s1.y, s4.x, s4.y),
            dist(s2.x, s2.y, s3.x, s3.y),
            dist(s2.x, s2.y, s4.x, s4.y),
            dist(s3.x, s3.y, s4.x, s4.y)
        ).sorted()
        val longest = ds.last()
        if (longest < 1f) return null
        return listOf(
            (ds[0] / longest).coerceIn(0f, 1f),
            (ds[1] / longest).coerceIn(0f, 1f),
            (ds[2] / longest).coerceIn(0f, 1f),
            (ds[3] / longest).coerceIn(0f, 1f)
        )
    }

    private fun compareHashSets(observed: List<List<Float>>, expected: List<List<Float>>): Float {
        var sum = 0f
        for (eh in expected) {
            var best = Float.MAX_VALUE
            for (oh in observed) {
                var diff = 0f
                for (i in eh.indices) diff += abs(eh[i] - oh[i])
                if (diff < best) best = diff
            }
            val local = (1f - best / eh.size.toFloat()).coerceIn(0f, 1f)
            sum += local
        }
        return (sum / expected.size.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        fun fromJson(json: String): StarCatalogMatcher {
            val arr = JSONArray(json)
            val north = mutableListOf<CatalogStar>()
            val south = mutableListOf<CatalogStar>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val star = CatalogStar(
                    name = o.getString("name"),
                    raDeg = o.getDouble("raDeg"),
                    decDeg = o.getDouble("decDeg"),
                    mag = o.getDouble("mag"),
                    hemisphere = o.getString("hemisphere")
                )
                if (star.hemisphere.equals("north", ignoreCase = true)) north.add(star) else south.add(star)
            }
            val northHashes = buildTemplateHashes(north, northMode = true)
            val southHashes = buildTemplateHashes(south, northMode = false)
            return StarCatalogMatcher(northHashes, southHashes)
        }

        private fun buildTemplateHashes(catalog: List<CatalogStar>, northMode: Boolean): List<List<Float>> {
            val points = catalog
                .sortedBy { it.mag }
                .take(24)
                .map { toPolarPlane(it, northMode) }
            val hashes = mutableListOf<List<Float>>()
            for (a in 0 until points.size) {
                for (b in a + 1 until points.size) {
                    for (c in b + 1 until points.size) {
                        for (d in c + 1 until points.size) {
                            val h = quadHash(points[a], points[b], points[c], points[d])
                            if (h != null) hashes.add(h)
                            if (hashes.size >= 70) return hashes
                        }
                    }
                }
            }
            return hashes
        }

        private fun toPolarPlane(star: CatalogStar, northMode: Boolean): CatalogPoint {
            val raRad = Math.toRadians(star.raDeg)
            val poleDist = if (northMode) {
                (90.0 - star.decDeg).coerceAtLeast(0.0)
            } else {
                (90.0 + star.decDeg).coerceAtLeast(0.0)
            }
            val r = poleDist.toFloat()
            val x = (r * cos(raRad)).toFloat()
            val y = (r * sin(raRad)).toFloat()
            val w = (4.5 - star.mag).toFloat().coerceIn(0.2f, 4.5f)
            return CatalogPoint(x, y, w)
        }

        private fun quadHash(p1: CatalogPoint, p2: CatalogPoint, p3: CatalogPoint, p4: CatalogPoint): List<Float>? {
            val ds = listOf(
                dist(p1.x, p1.y, p2.x, p2.y),
                dist(p1.x, p1.y, p3.x, p3.y),
                dist(p1.x, p1.y, p4.x, p4.y),
                dist(p2.x, p2.y, p3.x, p3.y),
                dist(p2.x, p2.y, p4.x, p4.y),
                dist(p3.x, p3.y, p4.x, p4.y)
            ).sorted()
            val longest = ds.last()
            if (longest < 0.01f) return null
            return listOf(
                (ds[0] / longest).coerceIn(0f, 1f),
                (ds[1] / longest).coerceIn(0f, 1f),
                (ds[2] / longest).coerceIn(0f, 1f),
                (ds[3] / longest).coerceIn(0f, 1f)
            )
        }

        private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            return hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
        }
    }
}
