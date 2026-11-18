package dev.sounddrop.modem.payload

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A point of a sketch on the [SketchCodec.GRID] × [SketchCodec.GRID] grid. */
public data class SketchPoint(
    /** Column, `0` is the left edge. */
    val x: Int,
    /** Row, `0` is the top edge. */
    val y: Int,
)

/**
 * Compact binary format for line drawings, so a doodle fits into a single frame.
 *
 * Each stroke is `[count] [x] [y]` followed by `count - 1` bytes with a signed 4-bit x and y
 * step each. Longer moves are split into several steps, so the format is lossless on the grid.
 */
public object SketchCodec {
    /** Resolution of the drawing grid. */
    public const val GRID: Int = 64

    private const val MAX_STEP = 7
    private const val MAX_POINTS_PER_STROKE = 255
    private const val MAX_TOLERANCE = 0.08

    /** Serialises [strokes]; every point must lie on the grid. */
    public fun encode(strokes: List<List<SketchPoint>>): ByteArray {
        val out = mutableListOf<Byte>()
        for (stroke in strokes.map(::withSmallSteps).flatMap { it.chunked(MAX_POINTS_PER_STROKE) }) {
            if (stroke.isEmpty()) continue
            out += stroke.size.toByte()
            out += stroke[0].x.toByte()
            out += stroke[0].y.toByte()
            for (i in 1 until stroke.size) {
                val dx = stroke[i].x - stroke[i - 1].x
                val dy = stroke[i].y - stroke[i - 1].y
                out += (((dx and 0x0F) shl 4) or (dy and 0x0F)).toByte()
            }
        }
        return out.toByteArray()
    }

    /** Parses a sketch, or returns `null` if the bytes are malformed. */
    public fun decode(bytes: ByteArray): List<List<SketchPoint>>? {
        val strokes = mutableListOf<List<SketchPoint>>()
        var i = 0
        while (i < bytes.size) {
            val count = bytes[i].toInt() and 0xFF
            if (count == 0 || i + 2 + count > bytes.size) return null
            var x = bytes[i + 1].toInt() and 0xFF
            var y = bytes[i + 2].toInt() and 0xFF
            val stroke = mutableListOf(SketchPoint(x, y))
            for (k in 1 until count) {
                val step = bytes[i + 2 + k].toInt()
                x += signedNibble(step shr 4)
                y += signedNibble(step)
                stroke += SketchPoint(x, y)
            }
            if (stroke.any { it.x !in 0 until GRID || it.y !in 0 until GRID }) return null
            strokes += stroke
            i += 2 + count
        }
        return strokes
    }

    /**
     * Converts free-hand strokes with coordinates in `0..1` to grid strokes whose encoding fits in
     * [maxBytes], simplifying more aggressively (Ramer–Douglas–Peucker) until it does. Strokes at the
     * end are dropped only if even the coarsest simplification is too large.
     */
    public fun fit(
        strokes: List<List<Pair<Float, Float>>>,
        maxBytes: Int,
    ): List<List<SketchPoint>> {
        var tolerance = 0.004
        while (true) {
            val quantised = strokes.map { quantise(simplify(it, tolerance)) }.filter { it.isNotEmpty() }
            if (encode(quantised).size <= maxBytes) return quantised
            if (tolerance > MAX_TOLERANCE) return dropUntilFits(quantised, maxBytes)
            tolerance *= 1.5
        }
    }

    private fun dropUntilFits(
        strokes: List<List<SketchPoint>>,
        maxBytes: Int,
    ): List<List<SketchPoint>> {
        val kept = strokes.toMutableList()
        while (kept.isNotEmpty() && encode(kept).size > maxBytes) kept.removeAt(kept.lastIndex)
        return kept
    }

    private fun quantise(points: List<Pair<Float, Float>>): List<SketchPoint> =
        points
            .map { (x, y) ->
                SketchPoint(
                    (x * (GRID - 1)).roundToInt().coerceIn(0, GRID - 1),
                    (y * (GRID - 1)).roundToInt().coerceIn(0, GRID - 1),
                )
            }.fold(mutableListOf()) { acc, p ->
                if (acc.lastOrNull() != p) acc += p
                acc
            }

    /** Ramer–Douglas–Peucker line simplification. */
    internal fun simplify(
        points: List<Pair<Float, Float>>,
        tolerance: Double,
    ): List<Pair<Float, Float>> {
        if (points.size < 3) return points
        val first = points.first()
        val last = points.last()
        var farthest = 0
        var maxDistance = 0.0
        for (i in 1 until points.lastIndex) {
            val d = distanceToSegment(points[i], first, last)
            if (d > maxDistance) {
                maxDistance = d
                farthest = i
            }
        }
        if (maxDistance <= tolerance) return listOf(first, last)
        return simplify(points.subList(0, farthest + 1), tolerance).dropLast(1) +
            simplify(points.subList(farthest, points.size), tolerance)
    }

    private fun distanceToSegment(
        p: Pair<Float, Float>,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
    ): Double {
        val dx = (b.first - a.first).toDouble()
        val dy = (b.second - a.second).toDouble()
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot(p.first - a.first.toDouble(), p.second - a.second.toDouble())
        val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }

    /** Inserts intermediate points so that no step is larger than [MAX_STEP] in x or y. */
    private fun withSmallSteps(stroke: List<SketchPoint>): List<SketchPoint> {
        if (stroke.isEmpty()) return stroke
        val out = mutableListOf(stroke[0])
        for (next in stroke.drop(1)) {
            val from = out.last()
            val steps = maxOf(1, (maxOf(abs(next.x - from.x), abs(next.y - from.y)) + MAX_STEP - 1) / MAX_STEP)
            for (s in 1..steps) {
                out +=
                    SketchPoint(
                        from.x + ((next.x - from.x) * s.toDouble() / steps).roundToInt(),
                        from.y + ((next.y - from.y) * s.toDouble() / steps).roundToInt(),
                    )
            }
        }
        return out
    }

    private fun signedNibble(value: Int): Int {
        val n = value and 0x0F
        return if (n >= 8) n - 16 else n
    }
}
