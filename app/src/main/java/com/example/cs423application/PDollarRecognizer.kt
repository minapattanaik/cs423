package com.example.cs423application

import kotlin.math.*

data class GPoint(val x: Float, val y: Float)

private data class PointCloud(val name: String, val points: List<GPoint>)

/**
 * $P point-cloud recognizer
 * implements pipeline as follows:
 *      1. resample (N points along path)
 *      2. scale (uniform scale so max(w, h) of bounding box = 1, aspect ratio preserved)
 *      3. translate (centroid to origin)
 *      4. match (greedy against stored templates)
 *
 * n=64 for finer shape resolution
 *
 * rectangle templates cover three aspect ratios (landscape 2:1, square 1:1, portrait 1:2)
 */

// TODO: edit rectangle threshold to avoid triggering on circle (will ask on piazza)
object PDollarRecognizer {

    private const val N = 64      // point cloud size — higher N = finer shape resolution
    private const val E = 0.5f   // exponent for step size in GreedyCloudMatch

    private val TEMPLATES: List<PointCloud> by lazy {
        listOf(
            // square
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = true,  height = 1.0f))),
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = false, height = 1.0f))),
            // portrait 1:2
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = true,  height = 2.0f))),
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = false, height = 2.0f))),
            // landscape 2:1
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = true,  height = 0.5f))),
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = false, height = 0.5f))),
            // tall portrait 1:3
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = true,  height = 3.0f))),
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = false, height = 3.0f))),
            // wide landscape 3:1
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = true,  height = 0.33f))),
            PointCloud("rectangle", prepare(rectangleRaw(clockwise = false, height = 0.33f))),
            // X gesture — two-stroke, all four starting corners
            PointCloud("x",         prepare(xRaw(startCorner = Corner.TOP_LEFT))),
            PointCloud("x",         prepare(xRaw(startCorner = Corner.TOP_RIGHT))),
            PointCloud("x",         prepare(xRaw(startCorner = Corner.BOTTOM_LEFT))),
            PointCloud("x",         prepare(xRaw(startCorner = Corner.BOTTOM_RIGHT))),
            // X gesture — single-stroke, connected via each of the four sides
            PointCloud("x",         prepare(xSingleStrokeRaw(Connect.RIGHT))),
            PointCloud("x",         prepare(xSingleStrokeRaw(Connect.LEFT))),
            PointCloud("x",         prepare(xSingleStrokeRaw(Connect.BOTTOM))),
            PointCloud("x",         prepare(xSingleStrokeRaw(Connect.TOP))),
            // arrow gestures (dir inferred from raw points)
            PointCloud("arrow",     prepare(arrowRaw(rightPointing = true))),
            PointCloud("arrow",     prepare(arrowRaw(rightPointing = false)))
        )
    }

    fun recognize(rawPoints: List<GPoint>): Pair<String, Float> {
        if (rawPoints.size < 4) return Pair("unknown", 0f)

        val candidate = prepare(rawPoints)

        var minDist  = Float.MAX_VALUE
        var bestName = "unknown"

        for (t in TEMPLATES) {
            val d = greedyCloudMatch(candidate, t.points)
            if (d < minDist) { minDist = d; bestName = t.name }
        }

        val score = if (minDist == 0f) Float.MAX_VALUE else 1f / minDist
        return Pair(bestName, score)
    }

    /**
     * tests step = floor(n^(1-e)) starting offsets; for each offset runs
     * CloudDistance in both directions and keeps minimum
     */
    private fun greedyCloudMatch(points: List<GPoint>, template: List<GPoint>): Float {
        val n    = points.size
        val step = floor(n.toFloat().pow(1f - E)).toInt().coerceAtLeast(1)
        var min  = Float.MAX_VALUE
        var i    = 0
        while (i < n) {
            val d1 = cloudDistance(points,   template, i)
            val d2 = cloudDistance(template, points,   i)
            min = minOf(min, d1, d2)
            i += step
        }
        return min
    }

    /**
     * weighted nearest-neighbor sum starting at [start], cycling through pts1
     * w = 1 − ((i − start + n) mod n)/n, so earlier pairs count more
     */
    private fun cloudDistance(pts1: List<GPoint>, pts2: List<GPoint>, start: Int): Float {
        val n       = pts1.size
        val matched = BooleanArray(n)
        var sum     = 0f
        var i       = start
        do {
            var nearest = -1
            var minD    = Float.MAX_VALUE
            for (j in pts2.indices) {
                if (!matched[j]) {
                    val d = dist(pts1[i], pts2[j])
                    if (d < minD) { minD = d; nearest = j }
                }
            }
            matched[nearest] = true
            val weight = 1f - ((i - start + n) % n).toFloat() / n
            sum += weight * minD
            i = (i + 1) % n
        } while (i != start)
        return sum
    }

    // normalization taken from reference JS
    private fun prepare(raw: List<GPoint>): List<GPoint> =
        translateToCentroid(scaleUniform(resample(raw, N)))

    private fun scaleUniform(points: List<GPoint>): List<GPoint> {
        val minX  = points.minOf { it.x }
        val maxX  = points.maxOf { it.x }
        val minY  = points.minOf { it.y }
        val maxY  = points.maxOf { it.y }
        val size  = maxOf(maxX - minX, maxY - minY).coerceAtLeast(1e-6f)
        return points.map { GPoint((it.x - minX) / size, (it.y - minY) / size) }
    }

    private fun translateToCentroid(points: List<GPoint>): List<GPoint> {
        val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        return points.map { GPoint(it.x - cx, it.y - cy) }
    }

    private fun resample(points: List<GPoint>, n: Int): List<GPoint> {
        val totalLen = pathLength(points)
        if (totalLen == 0f) return List(n) { points.first() }

        val I      = totalLen / (n - 1)
        var D      = 0f
        val newPts = mutableListOf(points[0])
        val pts    = points.toMutableList()
        var i      = 1

        while (i < pts.size) {
            val d = dist(pts[i - 1], pts[i])
            if (D + d >= I) {
                val t = (I - D) / d
                val q = GPoint(
                    pts[i - 1].x + t * (pts[i].x - pts[i - 1].x),
                    pts[i - 1].y + t * (pts[i].y - pts[i - 1].y)
                )
                newPts.add(q)
                pts.add(i, q)
                D = 0f
                i++
            } else {
                D += d
                i++
            }
        }
        while (newPts.size < n) newPts.add(pts.last())
        return newPts.take(n)
    }

    /**
     * rectangle raw point sequence (before normalization).
     * [height] controls aspect ratio with width fixed at 1.0:
     *   height = 0.5 -> landscape (2:1),  height = 1.0 -> square,  height = 2.0 -> portrait (1:2)
     */
    private fun rectangleRaw(clockwise: Boolean, height: Float = 1.0f): List<GPoint> {
        val raw = mutableListOf<GPoint>()
        val s   = 8
        if (clockwise) {
            for (i in 0..s)      raw.add(GPoint(i.toFloat() / s, 0f))
            for (i in 1..s)      raw.add(GPoint(1f, i.toFloat() / s * height))
            for (i in 1..s)      raw.add(GPoint(1f - i.toFloat() / s, height))
            for (i in 1 until s) raw.add(GPoint(0f, height - i.toFloat() / s * height))
        } else {
            for (i in 0..s)      raw.add(GPoint(0f, i.toFloat() / s * height))
            for (i in 1..s)      raw.add(GPoint(i.toFloat() / s, height))
            for (i in 1..s)      raw.add(GPoint(1f, height - i.toFloat() / s * height))
            for (i in 1 until s) raw.add(GPoint(1f - i.toFloat() / s, 0f))
        }
        return raw
    }

    private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * x gesture (2 diagonal strokes)
     * 4 variants cover all possible starting corners a user might choose:
     *   TOP_LEFT: \ -> /
     *   TOP_RIGHT: / -> \
     *   BOTTOM_LEFT: / -> \
     *   BOTTOM_RIGHT: \ -> /
     */
    private fun xRaw(startCorner: Corner): List<GPoint> {
        val raw = mutableListOf<GPoint>()
        val s   = 8
        when (startCorner) {
            Corner.TOP_LEFT -> {
                // stroke 1: top-left → bottom-right (\)
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(t, t)) }
                // stroke 2: top-right → bottom-left (/)
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(1f - t, t)) }
            }
            Corner.TOP_RIGHT -> {
                // stroke 1: top-right → bottom-left (/)
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(1f - t, t)) }
                // stroke 2: top-left → bottom-right (\)
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(t, t)) }
            }
            Corner.BOTTOM_LEFT -> {
                // stroke 1: bottom-left → top-right (/)
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(t, 1f - t)) }
                // stroke 2: bottom-right → top-left (\)
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(1f - t, 1f - t)) }
            }
            Corner.BOTTOM_RIGHT -> {
                // stroke 1: bottom-right → top-left (\)
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(1f - t, 1f - t)) }
                // stroke 2: bottom-left → top-right (/)
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(t, 1f - t)) }
            }
        }
        return raw
    }

    private enum class Connect { RIGHT, LEFT, BOTTOM, TOP }

    /**
     * single-stroke X: both diagonals as one continuous path, connected by a corner jog
     * RIGHT: TL→BR→TR→BL  LEFT: TR→BL→TL→BR  BOTTOM: TL→BR→BL→TR  TOP: BL→TR→TL→BR
     */
    private fun xSingleStrokeRaw(connect: Connect): List<GPoint> {
        val raw = mutableListOf<GPoint>()
        val s   = 8
        when (connect) {
            Connect.RIGHT -> {
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(t,       t      )) } // TL→BR
                for (i in 1..s) { val t = i.toFloat() / s; raw.add(GPoint(1f,      1f - t )) } // BR→TR
                for (i in 1..s) { val t = i.toFloat() / s; raw.add(GPoint(1f - t,  t      )) } // TR→BL
            }
            Connect.LEFT -> {
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(1f - t,  t      )) } // TR→BL
                for (i in 1..s) { val t = i.toFloat() / s; raw.add(GPoint(0f,      1f - t )) } // BL→TL
                for (i in 1..s) { val t = i.toFloat() / s; raw.add(GPoint(t,       t      )) } // TL→BR
            }
            Connect.BOTTOM -> {
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(t,       t      )) } // TL→BR
                for (i in 1..s) { val t = i.toFloat() / s; raw.add(GPoint(1f - t,  1f     )) } // BR→BL
                for (i in 1..s) { val t = i.toFloat() / s; raw.add(GPoint(t,       1f - t )) } // BL→TR
            }
            Connect.TOP -> {
                for (i in 0..s) { val t = i.toFloat() / s; raw.add(GPoint(t,       1f - t )) } // BL→TR
                for (i in 1..s) { val t = i.toFloat() / s; raw.add(GPoint(1f - t,  0f     )) } // TR→TL
                for (i in 1..s) { val t = i.toFloat() / s; raw.add(GPoint(t,       t      )) } // TL→BR
            }
        }
        return raw
    }

    /**
     * arrow gesture raw point sequence
     * line horizontal, then upper barb back to tip, then lower barb
     * [rightPointing] = true  -> ->  (line l->r, arrowhead at right)
     * [rightPointing] = false -> <-  (line r->l, arrowhead at left)
     */
    private fun arrowRaw(rightPointing: Boolean): List<GPoint> {
        val raw = mutableListOf<GPoint>()
        val s = 10
        val b = 4
        if (rightPointing) {
            for (i in 0..s) raw.add(GPoint(i.toFloat() / s, 0.5f))
            for (i in 1..b) raw.add(GPoint(1f - i * 0.08f, 0.5f - i * 0.08f))
            for (i in b downTo 1) raw.add(GPoint(1f - i * 0.08f, 0.5f - i * 0.08f))
            for (i in 1..b) raw.add(GPoint(1f - i * 0.08f, 0.5f + i * 0.08f))
        } else {
            for (i in 0..s) raw.add(GPoint(1f - i.toFloat() / s, 0.5f))
            for (i in 1..b) raw.add(GPoint(i * 0.08f, 0.5f - i * 0.08f))
            for (i in b downTo 1) raw.add(GPoint(i * 0.08f, 0.5f - i * 0.08f))
            for (i in 1..b) raw.add(GPoint(i * 0.08f, 0.5f + i * 0.08f))
        }
        return raw
    }

    private fun dist(a: GPoint, b: GPoint): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun pathLength(points: List<GPoint>): Float {
        var len = 0f
        for (i in 1 until points.size) len += dist(points[i - 1], points[i])
        return len
    }
}
