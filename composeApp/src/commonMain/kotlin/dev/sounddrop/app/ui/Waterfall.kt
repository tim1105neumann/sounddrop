package dev.sounddrop.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Colour map from silence (dark) through violet and magenta to loud (yellow). */
private val COLOR_STOPS =
    listOf(
        Color(0xFF0B0914),
        Color(0xFF2A1659),
        Color(0xFF7F52FF),
        Color(0xFFE44FA8),
        Color(0xFFFF8A3D),
        Color(0xFFFFE27A),
    )

/** Maps a loudness value in `0..1` to a colour. */
fun heatColor(value: Float): Color {
    val scaled = value.coerceIn(0f, 1f) * (COLOR_STOPS.size - 1)
    val index = scaled.toInt().coerceAtMost(COLOR_STOPS.size - 2)
    return lerp(COLOR_STOPS[index], COLOR_STOPS[index + 1], scaled - index)
}

/**
 * A scrolling spectrogram image. New columns are drawn once into a ring-buffer bitmap, so adding
 * a column costs one column of pixels instead of a full redraw.
 */
@Stable
class WaterfallState(
    private val historyColumns: Int = 400,
) {
    private var bitmap: ImageBitmap? = null
    private var canvas: Canvas? = null
    private var writeColumn = 0
    private val paint = Paint()

    /** Increments whenever the image changes, so readers recompose. */
    var version by mutableIntStateOf(0)
        private set

    /** Appends one spectrogram column (index 0 = lowest frequency). */
    fun push(column: FloatArray) {
        if (bitmap?.height != column.size) {
            bitmap = ImageBitmap(historyColumns, column.size).also { canvas = Canvas(it) }
            writeColumn = 0
        }
        val target = canvas ?: return
        val height = column.size
        for (i in column.indices) {
            paint.color = heatColor(column[i])
            val y = (height - 1 - i).toFloat()
            target.drawRect(writeColumn.toFloat(), y, writeColumn + 1f, y + 1f, paint)
        }
        writeColumn = (writeColumn + 1) % historyColumns
        version++
    }

    internal fun draw(scope: DrawScope) {
        val image = bitmap ?: return
        // Oldest part (right of the write position) first, then the newest part.
        val older = historyColumns - writeColumn
        val scaleX = scope.size.width / historyColumns
        with(scope) {
            drawImage(
                image,
                srcOffset = IntOffset(writeColumn, 0),
                srcSize = IntSize(older, image.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize((older * scaleX).toInt() + 1, size.height.toInt()),
                filterQuality = FilterQuality.None,
            )
            if (writeColumn > 0) {
                drawImage(
                    image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(writeColumn, image.height),
                    dstOffset = IntOffset((older * scaleX).toInt(), 0),
                    dstSize = IntSize((writeColumn * scaleX).toInt() + 1, size.height.toInt()),
                    filterQuality = FilterQuality.None,
                )
            }
        }
    }
}

/** Live spectrogram fed through [state]. */
@Composable
fun Waterfall(
    state: WaterfallState,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        drawRect(COLOR_STOPS.first(), Offset.Zero, size)
        // Reading the version subscribes this draw block to new columns.
        if (state.version >= 0) state.draw(this)
    }
}

/** Renders a complete spectrogram (columns over time) into a bitmap. */
fun spectrogramBitmap(columns: List<FloatArray>): ImageBitmap? {
    val height = columns.firstOrNull()?.size ?: return null
    val image = ImageBitmap(columns.size, height)
    val canvas = Canvas(image)
    val paint = Paint()
    columns.forEachIndexed { x, column ->
        for (i in column.indices) {
            paint.color = heatColor(column[i])
            val y = (height - 1 - i).toFloat()
            canvas.drawRect(x.toFloat(), y, x + 1f, y + 1f, paint)
        }
    }
    return image
}

/** Draws [image] stretched over the whole area without smoothing. */
fun DrawScope.drawStretched(image: ImageBitmap) {
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = FilterQuality.None,
    )
}
