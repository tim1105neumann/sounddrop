package dev.sounddrop.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.sounddrop.app.Screen

/** Small line icons drawn in code, so the app needs no icon font on any platform. */
@Composable
fun NavIcon(
    screen: Screen,
    modifier: Modifier = Modifier,
) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round)
        when (screen) {
            Screen.Send -> {
                // Speaker with sound waves.
                val body =
                    Path().apply {
                        moveTo(s * 0.12f, s * 0.38f)
                        lineTo(s * 0.3f, s * 0.38f)
                        lineTo(s * 0.5f, s * 0.2f)
                        lineTo(s * 0.5f, s * 0.8f)
                        lineTo(s * 0.3f, s * 0.62f)
                        lineTo(s * 0.12f, s * 0.62f)
                        close()
                    }
                drawPath(body, color, style = stroke)
                for (r in listOf(0.18f, 0.32f)) {
                    drawArc(
                        color,
                        startAngle = -50f,
                        sweepAngle = 100f,
                        useCenter = false,
                        topLeft = Offset(s * (0.5f - r), s * (0.5f - r)),
                        size = Size(s * 2 * r, s * 2 * r),
                        style = stroke,
                    )
                }
            }

            Screen.Listen -> {
                // Microphone.
                drawRoundRect(
                    color,
                    topLeft = Offset(s * 0.36f, s * 0.08f),
                    size = Size(s * 0.28f, s * 0.5f),
                    cornerRadius = CornerRadius(s * 0.14f),
                    style = stroke,
                )
                drawArc(
                    color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(s * 0.22f, s * 0.3f),
                    size = Size(s * 0.56f, s * 0.42f),
                    style = stroke,
                )
                drawLine(color, Offset(s * 0.5f, s * 0.72f), Offset(s * 0.5f, s * 0.9f), stroke.width, StrokeCap.Round)
            }

            Screen.Lab -> {
                // Flask.
                val flask =
                    Path().apply {
                        moveTo(s * 0.4f, s * 0.1f)
                        lineTo(s * 0.4f, s * 0.42f)
                        lineTo(s * 0.16f, s * 0.86f)
                        lineTo(s * 0.84f, s * 0.86f)
                        lineTo(s * 0.6f, s * 0.42f)
                        lineTo(s * 0.6f, s * 0.1f)
                    }
                drawPath(flask, color, style = stroke)
                drawLine(color, Offset(s * 0.32f, s * 0.1f), Offset(s * 0.68f, s * 0.1f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(s * 0.3f, s * 0.64f), Offset(s * 0.7f, s * 0.64f), stroke.width, StrokeCap.Round)
            }

            Screen.About -> {
                drawCircle(color, radius = s * 0.4f, style = stroke)
                drawLine(color, Offset(s * 0.5f, s * 0.45f), Offset(s * 0.5f, s * 0.72f), stroke.width, StrokeCap.Round)
                drawCircle(color, radius = s * 0.06f, center = Offset(s * 0.5f, s * 0.3f))
            }
        }
    }
}
