package dev.sounddrop.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.sounddrop.modem.payload.SketchCodec
import dev.sounddrop.modem.payload.SketchPoint

/**
 * A square drawing surface. Strokes are reported with coordinates in `0..1`, so the drawing does
 * not depend on the screen size.
 */
@Composable
fun SketchPad(
    strokes: List<List<Offset>>,
    onStrokeStart: (Offset) -> Unit,
    onStrokeMove: (Offset) -> Unit,
    preview: List<List<SketchPoint>>,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.primary
    val simplified = MaterialTheme.colorScheme.secondary
    Canvas(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStrokeStart(normalised(it, size.width.toFloat(), size.height.toFloat())) },
                    onDrag = { change, _ ->
                        change.consume()
                        onStrokeMove(normalised(change.position, size.width.toFloat(), size.height.toFloat()))
                    },
                )
            },
    ) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (points in strokes) {
            drawPath(
                pathOf(points.map { Offset(it.x * size.width, it.y * size.height) }),
                ink.copy(alpha = 0.35f),
                style = stroke,
            )
        }
        // What will actually be sent after simplification.
        drawGridStrokes(
            preview,
            simplified,
            preview.sumOf { it.size },
            Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Replays a received sketch stroke by stroke, the way it was drawn. */
@Composable
fun SketchReplay(
    strokes: List<List<SketchPoint>>,
    modifier: Modifier = Modifier,
) {
    val total = strokes.sumOf { it.size }
    val progress = remember(strokes) { Animatable(0f) }
    LaunchedEffect(strokes) { progress.animateTo(1f, tween(durationMillis = 1500, easing = LinearEasing)) }
    val color = MaterialTheme.colorScheme.secondary
    Canvas(
        modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        drawGridStrokes(
            strokes,
            color,
            (total * progress.value).toInt(),
            Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun DrawScope.drawGridStrokes(
    strokes: List<List<SketchPoint>>,
    color: Color,
    maxPoints: Int,
    stroke: Stroke,
) {
    val scale = size.width / (SketchCodec.GRID - 1)
    var remaining = maxPoints
    for (points in strokes) {
        if (remaining <= 0) return
        val visible = points.take(remaining)
        remaining -= visible.size
        val offsets = visible.map { Offset(it.x * scale, it.y * scale) }
        if (offsets.size == 1) {
            drawCircle(color, stroke.width / 2, offsets[0])
        } else {
            drawPath(pathOf(offsets), color, style = stroke)
        }
    }
}

private fun pathOf(points: List<Offset>): Path =
    Path().apply {
        points.firstOrNull()?.let { moveTo(it.x, it.y) }
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }

private fun normalised(
    position: Offset,
    width: Float,
    height: Float,
): Offset = Offset((position.x / width).coerceIn(0f, 1f), (position.y / height).coerceIn(0f, 1f))
