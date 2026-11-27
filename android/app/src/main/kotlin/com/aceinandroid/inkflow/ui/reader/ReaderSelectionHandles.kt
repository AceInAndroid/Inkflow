package com.aceinandroid.inkflow.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Point
import com.aceinandroid.inkflow.core.SelectionRangeController.Endpoint
import com.aceinandroid.inkflow.core.TextSelectionRange

/** Reverse the very same per-line translation used by ReaderPagePane.sourcePoint. */
internal fun readerSelectionHandles(
    layout: TextLayoutResult, panes: List<ReaderPagePane>, range: TextSelectionRange?, radius: Float,
): List<ReaderHandleTarget> {
    if (range == null || range.isEmpty || layout.layoutInput.text.isEmpty()) return emptyList()
    return Endpoint.entries.mapNotNull { endpoint ->
        val probe = if (endpoint == Endpoint.START) range.start else range.end - 1
        if (probe !in layout.layoutInput.text.indices) return@mapNotNull null
        val pane = panes.firstOrNull { probe in it.page.slice.startOffset until it.page.slice.endOffset }
            ?: return@mapNotNull null
        val line = layout.getLineForOffset(probe)
        val segment = pane.segments.firstOrNull { line in it.firstLine..it.lastLine } ?: return@mapNotNull null
        val box = layout.getBoundingBox(probe)
        val rtl = layout.getBidiRunDirection(probe) == ResolvedTextDirection.Rtl
        val leading = endpoint == Endpoint.START
        val x = pane.left + if (leading != rtl) box.left else box.right
        val y = layout.getLineBottom(line) + segment.translationY
        val anchor = Point(x.toDouble(), y.toDouble())
        ReaderHandleTarget(endpoint, anchor, Point(x.toDouble(), (y + radius).toDouble()))
    }
}

/** Dragging outside a glyph/paragraph clamps to the nearest real visible line and insertion boundary. */
internal fun readerHandleOffsetAt(layout: TextLayoutResult, panes: List<ReaderPagePane>, point: Offset): Int? {
    fun distance(value: Float, start: Float, end: Float): Float = when {
        value < start -> start - value
        value > end -> value - end
        else -> 0f
    }
    val pane = panes.minByOrNull { distance(point.x, it.left, it.left + it.width) } ?: return null
    val segment = pane.segments.minByOrNull { distance(point.y, it.top, it.bottom) } ?: return null
    val physical = Offset(point.x.coerceIn(pane.left, pane.left + pane.width - .1f),
        point.y.coerceIn(segment.top, (segment.bottom - .1f).coerceAtLeast(segment.top)))
    val source = pane.sourcePoint(layout, physical) ?: return null
    return layout.getOffsetForPosition(source).coerceIn(pane.page.slice.startOffset, pane.page.slice.endOffset)
}

/** Interactive layer is outside bodyLayer, so a lens never records itself, menus or another page. */
@Composable
internal fun ReaderSelectionHandles(
    targets: List<ReaderHandleTarget>, radius: Float, color: Color,
    focus: Point?, bodyLayer: GraphicsLayer?, eInk: Boolean,
    lensRadius: Float, lensGap: Float, background: Color,
) {
    Canvas(Modifier.fillMaxSize()) {
        targets.forEach { target ->
            val anchor = target.anchor.readerOffset()
            val center = target.center.readerOffset()
            val drop = Path().apply {
                moveTo(anchor.x, anchor.y)
                lineTo(center.x - radius * .85f, center.y - radius * .5f)
                lineTo(center.x + radius * .85f, center.y - radius * .5f)
                close()
            }
            drawPath(drop, color)
            drawCircle(color, radius, center)
        }
        if (!eInk && focus != null && bodyLayer != null) {
            val center = readerMagnifierCenter(focus, size.width.toDouble(), size.height.toDouble(),
                lensRadius.toDouble(), lensGap.toDouble())?.readerOffset()
            if (center != null) {
                val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, lensRadius)) }
                clipPath(path) {
                    drawCircle(background, lensRadius, center)
                    // Deliberately 1:1. Draw the current body layer, not a captured bitmap or estimated text.
                    translate(center.x - focus.x.toFloat(), center.y - focus.y.toFloat()) { drawLayer(bodyLayer) }
                }
                drawCircle(color, lensRadius, center, style = Stroke(width = 2f))
            }
        }
    }
}
