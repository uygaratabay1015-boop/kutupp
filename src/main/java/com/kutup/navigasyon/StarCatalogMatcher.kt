package com.kutup.navigasyon

import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.atan2
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
    private val southHashes: List<List<Float>>,
    private val northPoints: List<CatalogPoint>,
    private val southPoints: List<CatalogPoint>
) {
    data class PlateSolveResult(
        val poleX: Float,
        val poleY: Float,
        val confidence: Float,
        val rmsErrorPx: Float
    )

    private data class Triplet(
        val i: Int,
        val j: Int,
        val k: Int,
        val ratio1: Float,
        val ratio2: Float
    )

    private data class SimilarityTransform(
        val scale: Float,
        val cosT: Float,
        val sinT: Float,
        val tx: Float,
        val ty: Float
    )

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

    fun estimatePolePoint(stars: List<Star>, hemisphereMode: String): PlateSolveResult? {
        if (stars.size < 6) return null

        val observed = stars
            .sortedByDescending { it.brightness }
            .take(18)
            .map { CatalogPoint(it.x, it.y, (it.brightness / 255f).coerceIn(0.2f, 1f)) }

        val template = if (hemisphereMode.equals("north", ignoreCase = true)) northPoints else southPoints
        if (observed.size < 6 || template.size < 6) return null

        val obsTriplets = buildTriplets(observed, maxTriplets = 45)
        val tplTriplets = buildTriplets(template, maxTriplets = 45)
        if (obsTriplets.isEmpty() || tplTriplets.isEmpty()) return null

        val diag = hypot(
            (observed.maxOf { it.x } - observed.minOf { it.x }).toDouble().coerceAtLeast(1.0),
            (observed.maxOf { it.y } - observed.minOf { it.y }).toDouble().coerceAtLeast(1.0)
        ).toFloat().coerceAtLeast(120f)
        val pairTolerancePx = (diag * 0.08f).coerceIn(12f, 60f)
        val thirdPointTolerancePx = (diag * 0.06f).coerceIn(10f, 40f)

        var best: PlateSolveResult? = null
        var bestScore = 0f

        for (ot in obsTriplets) {
            for (tt in tplTriplets) {
                val ratioDiff = abs(ot.ratio1 - tt.ratio1) + abs(ot.ratio2 - tt.ratio2)
                if (ratioDiff > 0.10f) continue

                val oPts = listOf(observed[ot.i], observed[ot.j], observed[ot.k])
                val tPts = listOf(template[tt.i], template[tt.j], template[tt.k])
                val perms = arrayOf(
                    intArrayOf(0, 1, 2),
                    intArrayOf(0, 2, 1),
                    intArrayOf(1, 0, 2),
                    intArrayOf(1, 2, 0),
                    intArrayOf(2, 0, 1),
                    intArrayOf(2, 1, 0)
                )

                for (perm in perms) {
                    val tA = tPts[0]
                    val tB = tPts[1]
                    val tC = tPts[2]
                    val oA = oPts[perm[0]]
                    val oB = oPts[perm[1]]
                    val oC = oPts[perm[2]]

                    val transform = estimateSimilarity(tA, tB, oA, oB) ?: continue
                    val predC = transformPoint(transform, tC)
                    val errC = dist(predC.first, predC.second, oC.x, oC.y)
                    if (errC > thirdPointTolerancePx) continue

                    val score = scoreTransform(transform, template, observed, pairTolerancePx)
                    if (score.confidence <= bestScore) continue

                    val pole = transformPoint(transform, CatalogPoint(0f, 0f, 1f))
                    bestScore = score.confidence
                    best = PlateSolveResult(
                        poleX = pole.first,
                        poleY = pole.second,
                        confidence = score.confidence,
                        rmsErrorPx = score.rms
                    )
                }
            }
        }

        return best?.takeIf { it.confidence >= 0.35f }
    }

    private data class TransformScore(val confidence: Float, val rms: Float)

    private fun scoreTransform(
        transform: SimilarityTransform,
        template: List<CatalogPoint>,
        observed: List<CatalogPoint>,
        tolPx: Float
    ): TransformScore {
        var inliers = 0
        var weightedError = 0f
        var weightedInlier = 0f

        for (tp in template) {
            val mapped = transformPoint(transform, tp)
            var best = Float.MAX_VALUE
            var obsWeight = 0f
            for (op in observed) {
                val d = dist(mapped.first, mapped.second, op.x, op.y)
                if (d < best) {
                    best = d
                    obsWeight = op.weight
                }
            }
            if (best <= tolPx) {
                inliers += 1
                val w = (0.5f + 0.5f * obsWeight) * tp.weight.coerceIn(0.2f, 1.4f)
                weightedInlier += w
                weightedError += best * w
            }
        }

        if (inliers < 4 || weightedInlier <= 0.001f) return TransformScore(0f, 999f)

        val rms = weightedError / weightedInlier
        val inlierRatio = inliers / template.size.toFloat()
        val rmsScore = (1f - rms / tolPx).coerceIn(0f, 1f)
        val confidence = (0.65f * inlierRatio + 0.35f * rmsScore).coerceIn(0f, 1f)
        return TransformScore(confidence, rms)
    }

    private fun estimateSimilarity(
        tA: CatalogPoint,
        tB: CatalogPoint,
        oA: CatalogPoint,
        oB: CatalogPoint
    ): SimilarityTransform? {
        val tdx = tB.x - tA.x
        val tdy = tB.y - tA.y
        val odx = oB.x - oA.x
        val ody = oB.y - oA.y
        val tLen = hypot(tdx.toDouble(), tdy.toDouble()).toFloat()
        val oLen = hypot(odx.toDouble(), ody.toDouble()).toFloat()
        if (tLen < 1e-3f || oLen < 1e-3f) return null

        val scale = (oLen / tLen).coerceIn(0.1f, 100f)
        val tAngle = atan2(tdy, tdx)
        val oAngle = atan2(ody, odx)
        val theta = oAngle - tAngle
        val cosT = cos(theta)
        val sinT = sin(theta)

        val xA = scale * (cosT * tA.x - sinT * tA.y)
        val yA = scale * (sinT * tA.x + cosT * tA.y)
        val tx = oA.x - xA
        val ty = oA.y - yA
        return SimilarityTransform(scale, cosT, sinT, tx, ty)
    }

    private fun transformPoint(transform: SimilarityTransform, p: CatalogPoint): Pair<Float, Float> {
        val x = transform.scale * (transform.cosT * p.x - transform.sinT * p.y) + transform.tx
        val y = transform.scale * (transform.sinT * p.x + transform.cosT * p.y) + transform.ty
        return Pair(x, y)
    }

    private fun buildTriplets(points: List<CatalogPoint>, maxTriplets: Int): List<Triplet> {
        val triplets = mutableListOf<Pair<Triplet, Float>>()
        for (i in 0 until points.size) {
            for (j in i + 1 until points.size) {
                for (k in j + 1 until points.size) {
                    val d1 = dist(points[i].x, points[i].y, points[j].x, points[j].y)
                    val d2 = dist(points[j].x, points[j].y, points[k].x, points[k].y)
                    val d3 = dist(points[k].x, points[k].y, points[i].x, points[i].y)
                    val sides = listOf(d1, d2, d3).sorted()
                    val longest = sides[2]
                    if (longest < 0.5f) continue
                    val ratio1 = (sides[0] / longest).coerceIn(0f, 1f)
                    val ratio2 = (sides[1] / longest).coerceIn(0f, 1f)
                    val area2 = abs(
                        (points[j].x - points[i].x) * (points[k].y - points[i].y) -
                            (points[j].y - points[i].y) * (points[k].x - points[i].x)
                    )
                    if (area2 < 0.5f) continue
                    triplets.add(Triplet(i, j, k, ratio1, ratio2) to longest)
                }
            }
        }
        return triplets
            .sortedByDescending { it.second }
            .take(maxTriplets)
            .map { it.first }
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
            val northPoints = buildTemplatePoints(north, northMode = true)
            val southPoints = buildTemplatePoints(south, northMode = false)
            return StarCatalogMatcher(northHashes, southHashes, northPoints, southPoints)
        }

        private fun buildTemplatePoints(catalog: List<CatalogStar>, northMode: Boolean): List<CatalogPoint> {
            return catalog
                .sortedBy { it.mag }
                .take(18)
                .map { toPolarPlane(it, northMode) }
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
