package com.aceinandroid.inkflow.core

/**
 * Pure KMP pagination & sentence/word selection boundary algorithms.
 * Shared across Android (Compose TextMeasurer) and future iOS (TextKit 2 / CoreText).
 */
data class LineMetrics(
    val lineIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val top: Float,
    val bottom: Float,
)

sealed interface PageItem {
    val startOffset: Int
    val endOffset: Int
    val lineIndex: Int
    val top: Float
    val bottom: Float

    data class TextSlice(
        override val startOffset: Int,
        override val endOffset: Int,
        override val lineIndex: Int,
        override val top: Float,
        override val bottom: Float,
    ) : PageItem

    /** One indivisible illustration in the chapter's UTF-16 coordinate space. */
    data class ImageBlock(
        val key: String,
        override val startOffset: Int,
        override val endOffset: Int,
        override val lineIndex: Int,
        override val top: Float,
        override val bottom: Float,
    ) : PageItem
}

data class MixedPage(val slice: PageSlice, val items: List<PageItem>)
data class MixedPagination(val pages: List<MixedPage>, val summary: PaginationSummary)
data class ImageSize(val width: Float, val height: Float)

object TextPaginationCore {
    /** Fit before measuring the placeholder: even a portrait image occupies at most one page. */
    fun fitImage(width: Float, height: Float, viewportWidth: Float, viewportHeight: Float): ImageSize {
        require(viewportWidth.isFinite() && viewportWidth > 0 && viewportHeight.isFinite() && viewportHeight > 0)
        val w = width.takeIf { it.isFinite() && it > 0 } ?: viewportWidth
        val h = height.takeIf { it.isFinite() && it > 0 } ?: viewportWidth * 0.75f
        val scale = minOf(1f, viewportWidth / w, viewportHeight / h)
        return ImageSize(w * scale, h * scale)
    }

    /** Inputs have already been measured in a single document coordinate space. */
    fun paginateItems(items: List<PageItem>, viewportHeight: Float, totalLength: Int): MixedPagination {
        val length = totalLength.coerceAtLeast(0)
        if (items.isEmpty()) {
            val summary = paginateLines(emptyList(), viewportHeight, length)
            return MixedPagination(listOf(MixedPage(summary.pages.first(), emptyList())), summary)
        }
        val pages = mutableListOf<MixedPage>()
        var first = 0
        while (first < items.size) {
            var last = first
            while (last + 1 < items.size && items[last + 1].bottom - items[first].top <= viewportHeight) last++
            val content = items.subList(first, last + 1).toList()
            val start = content.first().startOffset.coerceIn(0, length)
            val slice = PageSlice(pages.size, start, content.last().endOffset.coerceIn(start, length),
                content.first().lineIndex, content.last().lineIndex)
            pages += MixedPage(slice, content)
            first = last + 1
        }
        return MixedPagination(pages, PaginationSummary(pages.map { it.slice }, length))
    }

    /**
     * Groups pre-measured text lines into non-overlapping viewport pages.
     * Guarantees every page has at least 1 line so pagination never stalls even on oversized lines.
     */
    fun paginateLines(
        lines: List<LineMetrics>,
        viewportHeight: Float,
        totalLength: Int,
    ): PaginationSummary {
        if (lines.isEmpty() || viewportHeight <= 0f) {
            return PaginationSummary(
                pages = listOf(PageSlice(0, 0, totalLength.coerceAtLeast(0), 0, 0)),
                totalLength = totalLength.coerceAtLeast(0),
            )
        }
        val pages = mutableListOf<PageSlice>()
        var pageStartIdx = 0
        while (pageStartIdx < lines.size) {
            val firstLine = lines[pageStartIdx]
            val pageTop = firstLine.top
            var pageEndIdx = pageStartIdx
            while (pageEndIdx + 1 < lines.size) {
                val candidate = lines[pageEndIdx + 1]
                if (candidate.bottom - pageTop <= viewportHeight) {
                    pageEndIdx++
                } else {
                    break
                }
            }
            val lastLine = lines[pageEndIdx]
            pages += PageSlice(
                pageIndex = pages.size,
                startOffset = firstLine.startOffset.coerceIn(0, totalLength),
                endOffset = lastLine.endOffset.coerceIn(firstLine.startOffset, totalLength),
                startLine = firstLine.lineIndex,
                endLine = lastLine.lineIndex,
            )
            pageStartIdx = pageEndIdx + 1
        }
        return PaginationSummary(pages = pages, totalLength = totalLength)
    }
}

/**
 * Resolves word, sentence, and paragraph boundaries for both CJK and Latin scripts.
 * Supports tap-to-word, swipe-to-sentence, and cross-paragraph selection expansion.
 */
object SentenceBoundaryResolver {
    private val sentenceTerminators = setOf(
        '.', '!', '?', '。', '！', '？', '…', ';', '；', '\n'
    )
    private val closingQuotes = setOf(
        '"', '\'', '”', '’', '」', '』', '》', ')', '）', '】', ']'
    )
    private val wordSeparators = setOf(
        ' ', '\t', '\n', '\r', '.', ',', '!', '?', ';', ':', '"', '\'',
        '(', ')', '[', ']', '{', '}', '/', '\\', '|', '<', '>',
        '，', '。', '！', '？', '；', '：', '、', '“', '”', '‘', '’',
        '（', '）', '《', '》', '「', '」', '『', '』', '【', '】', '—', '…', '\uFFFC'
    )

    fun sentenceRangeAt(text: String, offset: Int): TextSelectionRange? {
        if (text.isEmpty()) return null
        val anchor = offset.coerceIn(0, text.lastIndex)
        if (text[anchor] == '\n' || text[anchor] == '\uFFFC') return null

        var start = anchor
        while (start > 0) {
            val prev = text[start - 1]
            if (prev in sentenceTerminators || prev == '\uFFFC') break
            start--
        }
        while (start < text.length && text[start].isWhitespace()) start++

        var end = anchor + 1
        while (end < text.length) {
            val ch = text[end - 1]
            if (ch in sentenceTerminators) break
            if (text[end] == '\n' || text[end] == '\uFFFC') break
            end++
        }
        while (end < text.length && text[end] in closingQuotes) end++
        while (end > start && text[end - 1].isWhitespace()) end--

        return if (end > start) TextSelectionRange(start, end) else null
    }

    fun wordRangeAt(text: String, offset: Int): TextSelectionRange? {
        if (text.isEmpty()) return null
        val anchor = offset.coerceIn(0, text.lastIndex)
        val ch = text[anchor]
        if (ch.isWhitespace() || ch == '\uFFFC') return null

        if (isCjk(ch)) {
            // For CJK, select a 1-2 character candidate or stay on the single character.
            val end = if (anchor + 1 < text.length && isCjk(text[anchor + 1])) anchor + 2 else anchor + 1
            return TextSelectionRange(anchor, end)
        }

        if (ch in wordSeparators) return TextSelectionRange(anchor, anchor + 1)

        var start = anchor
        while (start > 0 && text[start - 1] !in wordSeparators && !isCjk(text[start - 1])) start--
        var end = anchor + 1
        while (end < text.length && text[end] !in wordSeparators && !isCjk(text[end])) end++
        return if (end > start) TextSelectionRange(start, end) else null
    }

    fun paragraphRangeAt(text: String, offset: Int): TextSelectionRange? {
        if (text.isEmpty()) return null
        val anchor = offset.coerceIn(0, text.lastIndex)
        var start = anchor
        while (start > 0 && text[start - 1] != '\n') start--
        while (start < text.length && text[start].isWhitespace() && text[start] != '\n') start++
        var end = anchor + 1
        while (end < text.length && text[end] != '\n') end++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (end > start) TextSelectionRange(start, end) else null
    }

    fun expandRange(text: String, current: TextSelectionRange, deltaStart: Int, deltaEnd: Int): TextSelectionRange {
        if (text.isEmpty()) return TextSelectionRange(0, 0)
        val norm = current.normalized()
        val newStart = (norm.start + deltaStart).coerceIn(0, text.length)
        val newEnd = (norm.end + deltaEnd).coerceIn(newStart, text.length)
        return TextSelectionRange(newStart, newEnd)
    }

    fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF
    }
}
