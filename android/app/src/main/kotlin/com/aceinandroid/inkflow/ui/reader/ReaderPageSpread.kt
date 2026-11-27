package com.aceinandroid.inkflow.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import com.aceinandroid.inkflow.core.MixedPage
import com.aceinandroid.inkflow.core.MixedPagination
import com.aceinandroid.inkflow.core.PaginationSummary

internal const val READER_SPREAD_LAYOUT_MODE = "two-page-spread"
internal const val READER_SPREAD_GUTTER_DP = 24

/** Public navigation still counts displayed viewports; no invented blank page or offsets. */
internal fun readerViewportPagination(leaves: PaginationSummary, columns: Int): PaginationSummary {
    require(columns in 1..2)
    if (columns == 1) return leaves
    return PaginationSummary(leaves.pages.chunked(columns).mapIndexed { index, group ->
        group.first().copy(pageIndex = index, endOffset = group.last().endOffset,
            endLine = group.last().endLine)
    }, leaves.totalLength)
}

/** Retain the exact character within a spread, including when it lies in the right pane. */
internal fun readerVisibleAnchor(
    previous: ReaderVisiblePageAnchor?, key: String, summary: PaginationSummary,
    pageIndex: Int, explicitOffset: Int? = null,
): ReaderVisiblePageAnchor {
    val page = summary.pages[pageIndex]
    val candidate = when {
        explicitOffset != null -> explicitOffset
        previous != null && (previous.layoutKey != key || previous.pageIndex == pageIndex) -> previous.offset
        else -> page.startOffset
    }
    val offset = if (candidate >= page.startOffset &&
        (candidate < page.endOffset || pageIndex == summary.pages.lastIndex && candidate == summary.totalLength))
        candidate else page.startOffset
    return ReaderVisiblePageAnchor(key, pageIndex, offset)
}

internal data class ReaderDrawSegment(
    val top: Float, val bottom: Float, val translationY: Float, val firstLine: Int, val lastLine: Int,
)

internal data class ReaderPagePane(
    val page: MixedPage, val left: Float, val width: Float, val segments: List<ReaderDrawSegment>,
) {
    val pageTop: Float get() = page.items.firstOrNull()?.top ?: 0f

    /** Geometry and hit tests use physical x coordinates, including RTL paragraphs. */
    fun sourcePoint(layout: TextLayoutResult, point: Offset): Offset? {
        if (point.x < left || point.x >= left + width ||
            segments.none { point.y >= it.top && point.y < it.bottom }) return null
        val y = point.y + pageTop
        val item = page.items.firstOrNull { y < it.bottom } ?: return null
        val sourceY = (y - item.top + layout.getLineTop(item.lineIndex)).coerceIn(
            layout.getLineTop(item.lineIndex), layout.getLineBottom(item.lineIndex) - .1f)
        return Offset(point.x - left, sourceY)
    }
}

internal fun readerPagePanes(
    layout: TextLayoutResult, leaves: MixedPagination, viewport: Int, columns: Int,
    paneWidth: Float, gutter: Float,
): List<ReaderPagePane> = (0 until columns).mapNotNull { column ->
    leaves.pages.getOrNull(viewport * columns + column)?.let { page ->
        val pageTop = page.items.firstOrNull()?.top ?: 0f
        val segments = mutableListOf<ReaderDrawSegment>()
        page.items.forEach { item ->
            val sourceTop = layout.getLineTop(item.lineIndex)
            val delta = item.top - sourceTop - pageTop
            val bottom = layout.getLineBottom(item.lineIndex) + delta
            val previous = segments.lastOrNull()
            if (previous != null && kotlin.math.abs(previous.translationY - delta) < .1f)
                segments[segments.lastIndex] = previous.copy(bottom = bottom, lastLine = item.lineIndex)
            else segments += ReaderDrawSegment(sourceTop + delta, bottom, delta, item.lineIndex, item.lineIndex)
        }
        ReaderPagePane(page, column * (paneWidth + gutter), paneWidth, segments)
    }
}
