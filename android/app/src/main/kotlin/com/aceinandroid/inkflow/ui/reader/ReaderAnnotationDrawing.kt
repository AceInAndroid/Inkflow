package com.aceinandroid.inkflow.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.EpubText
import com.aceinandroid.inkflow.StoredAnnotation
import com.aceinandroid.inkflow.core.ReaderTransientHighlight
import kotlin.math.max
import kotlin.math.min

internal fun readerAnnotationSpans(epub: EpubText?, annotations: List<StoredAnnotation>): List<AnnotationSpan> {
    if (epub == null) return emptyList()
    return annotations.mapNotNull { note ->
        if (note.type !in setOf("highlight", "underline", "wavy")) return@mapNotNull null
        val range = epub.offsetsForRange(note.cfi) ?: return@mapNotNull null
        val rgb = note.color.removePrefix("#").toLongOrNull(16)?.and(0xFFFFFF) ?: 0xF6C453
        AnnotationSpan(range.first, range.last + 1, note.type, Color(0xFF000000 or rgb), note)
    }
}

internal fun readerAnnotationAtOffset(spans: List<AnnotationSpan>, offset: Int): StoredAnnotation? =
    spans.lastOrNull { offset >= it.start && offset < it.end && (it.annotation?.id ?: -1) > 0 }?.annotation

/** Reject padding and line-end whitespace which TextLayoutResult otherwise snaps to a character. */
internal fun readerAnnotationAtPosition(
    layout: TextLayoutResult, spans: List<AnnotationSpan>, point: Offset, sourceOffset: Int = 0,
): StoredAnnotation? {
    if (layout.layoutInput.text.isEmpty() || point.y < 0 || point.y >= layout.size.height) return null
    val cursor = layout.getOffsetForPosition(point)
    // TextLayoutResult returns an insertion position, including the trailing half of a glyph.
    for (offset in listOf(cursor, cursor - 1).filter { it in layout.layoutInput.text.indices }) {
        val box = layout.getBoundingBox(offset)
        if (box.width > 0f && point.x >= box.left && point.x <= box.right && point.y >= box.top && point.y <= box.bottom) {
            readerAnnotationAtOffset(spans, sourceOffset + offset)?.let { return it }
        }
    }
    return null
}

internal data class ReaderAnnotationRun(val line: Int, val left: Float, val right: Float)

/** Glyph boxes handle RTL, bidi and surrogate pairs without connecting disjoint visual runs. */
internal fun readerAnnotationRuns(
    layout: TextLayoutResult, start: Int, end: Int, firstLine: Int = 0, lastLine: Int = layout.lineCount - 1,
): List<ReaderAnnotationRun> {
    val text = layout.layoutInput.text.text
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    if (from == to || firstLine > lastLine) return emptyList()
    val runs = mutableListOf<ReaderAnnotationRun>()
    val first = max(layout.getLineForOffset(from), firstLine)
    val last = min(layout.getLineForOffset(to - 1), lastLine)
    for (line in first..last) {
        val boxes = (max(from, layout.getLineStart(line)) until min(to, layout.getLineEnd(line)))
            .asSequence().filter { text[it] != '\n' && text[it] != '\r' && text[it] != '\uFFFC' }
            .map { layout.getBoundingBox(it) }.filter { it.width > 0f }.sortedBy { it.left }.toList()
        boxes.forEach { box ->
            val previous = runs.lastOrNull()
            if (previous != null && previous.line == line && box.left <= previous.right + .5f)
                runs[runs.lastIndex] = previous.copy(right = max(previous.right, box.right))
            else runs += ReaderAnnotationRun(line, box.left, box.right)
        }
    }
    return runs
}

internal fun readerAnnotationInk(color: Color, highContrast: Boolean, background: Color): Color =
    if (highContrast) {
        if (background.luminance() < .5f) Color.White else Color.Black
    } else color

/** Real curved strokes, with bounded amplitude; no replacement glyphs or layout spans. */
internal fun readerWavePath(left: Float, right: Float, y: Float, amplitude: Float, wavelength: Float): Path {
    val path = Path()
    if (right <= left || amplitude <= 0f || wavelength <= 0f) return path
    path.moveTo(left, y)
    var x = left
    var sign = 1f
    while (x < right) {
        val next = min(x + wavelength / 2f, right)
        val fraction = (next - x) / (wavelength / 2f)
        path.cubicTo(x + (next - x) / 3f, y + sign * amplitude * fraction,
            x + (next - x) * 2f / 3f, y + sign * amplitude * fraction, next, y)
        sign = -sign
        x = next
    }
    return path
}

/** Shared by the paged Canvas and the streaming BasicTextField decoration. Offsets stay UTF-16. */
internal fun DrawScope.drawReaderAnnotations(
    layout: TextLayoutResult, spans: List<AnnotationSpan>, highContrast: Boolean, background: Color,
    underlay: Boolean, sourceOffset: Int = 0, visibleStart: Int = sourceOffset,
    visibleEnd: Int = sourceOffset + layout.layoutInput.text.length,
    firstLine: Int = 0, lastLine: Int = layout.lineCount - 1,
) {
    val stroke = if (highContrast) 1.75.dp.toPx() else 1.25.dp.toPx()
    spans.forEach { span ->
        if (span.type !in setOf("highlight", "underline", "wavy") ||
            (span.type == "highlight" && !highContrast) != underlay) return@forEach
        val start = max(span.start, visibleStart) - sourceOffset
        val end = min(span.end, visibleEnd) - sourceOffset
        if (end <= start) return@forEach
        val ink = readerAnnotationInk(span.color, highContrast, background)
        readerAnnotationRuns(layout, start, end, firstLine, lastLine).forEach { run ->
            val top = layout.getLineTop(run.line)
            val bottom = layout.getLineBottom(run.line)
            val baseline = layout.getLineBaseline(run.line)
            clipRect(left = run.left, top = top, right = run.right, bottom = bottom) {
                if (span.type == "highlight") {
                    if (highContrast) drawRect(ink, Offset(run.left + stroke / 2, top + stroke / 2),
                        Size((run.right - run.left - stroke).coerceAtLeast(0f),
                            (bottom - top - stroke).coerceAtLeast(0f)), style = Stroke(stroke))
                    else drawRect(ink.copy(alpha = .35f), Offset(run.left, top), Size(run.right - run.left, bottom - top))
                } else {
                    val amplitude = min(1.5.dp.toPx(), ((bottom - baseline - stroke) / 2f).coerceAtLeast(.25f))
                    val y = min(baseline + amplitude + stroke, bottom - amplitude - stroke / 2f)
                    if (span.type == "wavy") drawPath(readerWavePath(run.left, run.right, y, amplitude,
                        max(6.dp.toPx(), amplitude * 4f)), ink, style = Stroke(stroke, cap = StrokeCap.Round))
                    else drawLine(ink, Offset(run.left, y), Offset(run.right, y), strokeWidth = stroke)
                }
            }
        }
    }
}

/** A transient overlay never alters text spans, saved marks, selection or pagination keys. */
internal fun DrawScope.drawReaderTransientHighlight(
    layout: TextLayoutResult, highlight: ReaderTransientHighlight?, highContrast: Boolean, background: Color,
    sourceOffset: Int = 0, visibleStart: Int = sourceOffset,
    visibleEnd: Int = sourceOffset + layout.layoutInput.text.length,
    firstLine: Int = 0, lastLine: Int = layout.lineCount - 1,
) {
    val range = highlight?.range ?: return
    val start = max(range.start, visibleStart) - sourceOffset
    val end = min(range.end, visibleEnd) - sourceOffset
    if (end <= start) return
    readerAnnotationRuns(layout, start, end, firstLine, lastLine).forEach { run ->
        drawReaderTransientRect(highContrast, background, run.left, layout.getLineTop(run.line),
            run.right, layout.getLineBottom(run.line))
    }
    // A source range may include an inline illustration. Use its measured placeholder rect.
    layout.layoutInput.placeholders.forEachIndexed { index, placeholder ->
        if (placeholder.start >= start && placeholder.start < end &&
            layout.getLineForOffset(placeholder.start) in firstLine..lastLine) {
            layout.placeholderRects.getOrNull(index)?.let {
                drawReaderTransientRect(highContrast, background, it.left, it.top, it.right, it.bottom)
            }
        }
    }
}

internal fun DrawScope.drawReaderTransientRect(
    highContrast: Boolean, background: Color, left: Float = 0f, top: Float = 0f,
    right: Float = size.width, bottom: Float = size.height,
) {
    if (right <= left || bottom <= top) return
    val ink = readerAnnotationInk(Color(0xFF2457C5), highContrast, background)
    if (highContrast) {
        val inset = min(1.75.dp.toPx(), min(right - left, bottom - top)) / 2f
        drawRect(ink, Offset(left + inset, top + inset),
            Size((right - left - inset * 2).coerceAtLeast(0f), (bottom - top - inset * 2).coerceAtLeast(0f)),
            style = Stroke(inset * 2))
    } else drawRect(ink.copy(alpha = .18f), Offset(left, top), Size(right - left, bottom - top))
}

/** Observe short taps before TextField consumes up; leave down, long-press and drag untouched. */
internal fun Modifier.readerAnnotationTap(
    layout: () -> TextLayoutResult?, spans: State<List<AnnotationSpan>>, sourceOffset: Int,
    selectionActive: State<Boolean>, onClick: State<(StoredAnnotation) -> Unit>,
): Modifier = pointerInput(sourceOffset) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val annotation = if (selectionActive.value) null else layout()?.let {
            readerAnnotationAtPosition(it, spans.value, down.position, sourceOffset)
        }
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                event.changes.size > 1) moved = true
            if (!change.pressed) {
                if (annotation != null && !moved && !change.isConsumed &&
                    change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                    change.consume()
                    onClick.value(annotation)
                }
                break
            }
        }
    }
}
