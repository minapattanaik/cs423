package com.example.cs423application

import kotlin.math.*
data class GPoint(val x: Float, val y: Float)

private data class ProtractorTemplate(val name: String, val vector: FloatArray)

/**
 * protractor recognizer: https://depts.washington.edu/acelab/proj/dollar/protractor.pdf
 * implements steps as follows:
 *      1. resample: n = 16 evenly spaced points
 *      2. vectorize: translate to centroid, rotate by angle, l2-normalize
 *      3. recognize: optimal-cosine-distance against stored template
 *
 * returns Pair(name, score) where score = 1 / cosineDistance
 * higher score = better match; threshold ~2.0 utilized
 */
object ProtractorRecognizer {

    private const val N = 16

    private val TEMPLATES: List<ProtractorTemplate> by lazy {
        listOf(
            // clockwise
            ProtractorTemplate("rectangle", buildRectangleVector(clockwise = true)),
            // counterwise
            ProtractorTemplate("rectangle", buildRectangleVector(clockwise = false)),
            // first diagonal: top-left to bottom-right
            ProtractorTemplate("x", buildXVector(leftToRight = true)),
            // first diagonal: top-right to bottom-left
            ProtractorTemplate("x", buildXVector(leftToRight = false))
        )
    }

    fun recognize(rawPoints: List<GPoint>): Pair<String, Float> {
        if (rawPoints.size < 4) return Pair("unknown", 0f)

        val candidate = vectorize(resample(rawPoints, N), oSensitive = false)

        var maxScore = 0f
        var bestName = "unknown"

        for (t in TEMPLATES) {
            val dist  = optimalCosineDistance(t.vector, candidate)
            val score = if (dist == 0f) Float.MAX_VALUE else 1f / dist
            if (score > maxScore) { maxScore = score; bestName = t.name }
        }

        return Pair(bestName, maxScore)
    }

    /**
     * rectangle template
     */
    private fun buildRectangleVector(clockwise: Boolean): FloatArray {
        val raw = mutableListOf<GPoint>()
        val s   = 8
        if (clockwise) {
            for (i in 0..s)      raw.add(GPoint(i.toFloat() / s, 0f))            // top
            for (i in 1..s)      raw.add(GPoint(1f, i.toFloat() / s))            // right
            for (i in 1..s)      raw.add(GPoint(1f - i.toFloat() / s, 1f))       // bottom
            for (i in 1 until s) raw.add(GPoint(0f, 1f - i.toFloat() / s))       // left
        } else {
            for (i in 0..s)      raw.add(GPoint(0f, i.toFloat() / s))            // left
            for (i in 1..s)      raw.add(GPoint(i.toFloat() / s, 1f))            // bottom
            for (i in 1..s)      raw.add(GPoint(1f, 1f - i.toFloat() / s))       // right
            for (i in 1 until s) raw.add(GPoint(1f - i.toFloat() / s, 0f))       // top
        }
        return vectorize(resample(raw, N), oSensitive = false)
    }

    /**
     * X gesture: two diagonal strokes concatenated.
     * leftToRight = true  → stroke1: top-left→bottom-right, stroke2: top-right→bottom-left
     * leftToRight = false → stroke1: top-right→bottom-left, stroke2: top-left→bottom-right
     *
     * The two strokes are concatenated into one point sequence so the single-stroke
     * Protractor algorithm can match them.
     */
    private fun buildXVector(leftToRight: Boolean): FloatArray {
        val raw = mutableListOf<GPoint>()
        val steps = 8

        if (leftToRight) {
            // stroke 1: (0,0) → (1,1)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                raw.add(GPoint(t, t))
            }
            // stroke 2: (1,0) → (0,1)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                raw.add(GPoint(1f - t, t))
            }
        } else {
            // stroke 1: (1,0) → (0,1)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                raw.add(GPoint(1f - t, t))
            }
            // stroke 2: (0,0) → (1,1)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                raw.add(GPoint(t, t))
            }
        }

        return vectorize(resample(raw, N), oSensitive = false)
    }

    private fun resample(points: List<GPoint>, n: Int): List<GPoint> {
        val totalLen = pathLength(points)
        if (totalLen == 0f) return List(n) { points.first() }

        val I        = totalLen / (n - 1)
        var D        = 0f
        val newPts   = mutableListOf(points[0])
        val pts      = points.toMutableList()
        var i        = 1

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
     * oSensitive = false: fully rotation-invariant (delta = –indicativeAngle).
     * oSensitive = true : snap to nearest 45° multiple.
     */
    private fun vectorize(points: List<GPoint>, oSensitive: Boolean): FloatArray {
        // centroid
        val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size

        val t = points.map { GPoint(it.x - cx, it.y - cy) }

        // indicative angle from first resampled point
        val indicativeAngle = atan2(t[0].y, t[0].x)
        val delta = if (oSensitive) {
            val base = (PI.toFloat() / 4f) *
                floor((indicativeAngle + PI.toFloat() / 8f) / (PI.toFloat() / 4f))
            base - indicativeAngle
        } else {
            -indicativeAngle
        }

        val cosD = cos(delta)
        val sinD = sin(delta)
        val vec  = FloatArray(2 * t.size)
        var sum  = 0f

        for ((idx, p) in t.withIndex()) {
            val nx = p.x * cosD - p.y * sinD
            val ny = p.y * cosD + p.x * sinD
            vec[2 * idx]     = nx
            vec[2 * idx + 1] = ny
            sum += nx * nx + ny * ny
        }

        val mag = sqrt(sum)
        if (mag > 0f) for (k in vec.indices) vec[k] /= mag
        return vec
    }

    /**
     * solution taken directly from paper
     */
    private fun optimalCosineDistance(v: FloatArray, w: FloatArray): Float {
        var a = 0f
        var b = 0f
        var i = 0
        while (i < v.size) {
            a += v[i] * w[i]     + v[i + 1] * w[i + 1]
            b += v[i] * w[i + 1] - v[i + 1] * w[i]
            i += 2
        }
        val angle = if (a == 0f) (PI / 2).toFloat() else atan(b / a)
        return acos((a * cos(angle) + b * sin(angle)).coerceIn(-1f, 1f))
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
