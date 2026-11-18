package dev.sounddrop.modem.payload

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchCodecTest {
    private val square =
        listOf(
            listOf(
                SketchPoint(10, 10),
                SketchPoint(50, 10),
                SketchPoint(50, 50),
                SketchPoint(10, 50),
                SketchPoint(10, 10),
            ),
        )

    @Test
    fun roundTripKeepsEveryCorner() {
        val decoded = SketchCodec.decode(SketchCodec.encode(square))!!
        assertEquals(1, decoded.size)
        for (corner in square.single()) assertTrue(corner in decoded.single(), "missing $corner")
    }

    @Test
    fun stepsAreAtMostSevenCells() {
        val decoded = SketchCodec.decode(SketchCodec.encode(square))!!.single()
        decoded.zipWithNext().forEach { (a, b) ->
            assertTrue(kotlin.math.abs(a.x - b.x) <= 7 && kotlin.math.abs(a.y - b.y) <= 7)
        }
    }

    @Test
    fun freehandDrawingIsSimplifiedToFitTheFrame() {
        // A wobbly circle with 2000 points and a few extra scribbles.
        val circle =
            (0..2000).map { i ->
                0.5f + 0.4f * cos(i * 2 * PI / 2000).toFloat() to
                    0.5f + 0.4f * sin(i * 2 * PI / 2000).toFloat()
            }
        val scribbles =
            (0 until 6).map { s ->
                (0..300).map { i ->
                    i / 300f to
                        (s / 6f + 0.05f * sin(i / 10.0).toFloat())
                }
            }
        val fitted = SketchCodec.fit(listOf(circle) + scribbles, maxBytes = 159)
        val bytes = SketchCodec.encode(fitted)
        assertTrue(bytes.size <= 159, "encoded ${bytes.size} bytes")
        assertTrue(fitted.isNotEmpty())
        val decoded = SketchCodec.decode(bytes)!!
        assertEquals(bytes.toList(), SketchCodec.encode(decoded).toList())
        for ((original, received) in fitted.zip(decoded)) assertTrue(received.containsAll(original))
    }

    @Test
    fun sketchPayloadRoundTrips() {
        val payload = Payload.Sketch(SketchCodec.decode(SketchCodec.encode(square))!!)
        assertEquals(payload, Payload.decode(payload.type, payload.encode()))
    }

    @Test
    fun truncatedDataIsRejected() {
        val bytes = SketchCodec.encode(square)
        assertNull(SketchCodec.decode(bytes.copyOf(bytes.size - 2)))
    }
}
