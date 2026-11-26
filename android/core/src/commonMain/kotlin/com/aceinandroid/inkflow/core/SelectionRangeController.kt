package com.aceinandroid.inkflow.core

/** Local range authority. Commands carry a revision so late callbacks cannot replace a newer range. */
class SelectionRangeController(private val text: String) {
    enum class Endpoint { START, END }
    enum class Unit { CHARACTER, WORD, SENTENCE }
    data class Snapshot(val session: Long, val revision: Long, val range: TextSelectionRange,
        val visiblePage: Int?, val canRetract: Boolean)

    var snapshot: Snapshot? = null
        private set
    private var session = 0L
    private var revision = 0L
    private val extensions = mutableListOf<Snapshot>()

    fun begin(range: TextSelectionRange, visiblePage: Int? = null): Snapshot? {
        val normalized = normalize(range) ?: return null
        session++
        extensions.clear()
        return publish(normalized, visiblePage)
    }

    fun clear() { snapshot = null; revision++; extensions.clear() }

    fun replace(range: TextSelectionRange, expectedRevision: Long): Snapshot? {
        val current = snapshot ?: return null
        if (current.revision != expectedRevision) return null
        val normalized = normalize(range) ?: return null
        return publish(normalized, current.visiblePage)
    }

    fun adjust(endpoint: Endpoint, unit: Unit, forward: Boolean, expectedRevision: Long): Snapshot? {
        val current = snapshot ?: return null
        if (current.revision != expectedRevision) return null
        val range = current.range
        val offset = if (endpoint == Endpoint.START) range.start else range.end
        val moved = when (unit) {
            Unit.CHARACTER -> if (forward) nextCharacter(offset) else previousCharacter(offset)
            Unit.WORD, Unit.SENTENCE -> {
                var probe = if (forward) offset else offset - 1
                while (probe in text.indices && text[probe].isWhitespace()) probe += if (forward) 1 else -1
                val boundary = if (unit == Unit.WORD) SentenceBoundaryResolver.wordRangeAt(text, probe)
                    else SentenceBoundaryResolver.sentenceRangeAt(text, probe)
                if (forward) boundary?.end?.takeIf { it > offset } ?: nextCharacter(offset)
                else boundary?.start?.takeIf { it < offset } ?: previousCharacter(offset)
            }
        }
        val adjusted = if (endpoint == Endpoint.START) TextSelectionRange(moved.coerceAtMost(previousCharacter(range.end)), range.end)
            else TextSelectionRange(range.start, moved.coerceAtLeast(nextCharacter(range.start)))
        if (adjusted == range) return current
        return publish(adjusted, current.visiblePage)
    }

    /** A rendered page may confirm that an adjusted endpoint is now visible. */
    fun confirmVisiblePage(page: PageSlice, expectedRevision: Long): Snapshot? {
        val current = snapshot ?: return null
        if (current.revision != expectedRevision || current.range.end - 1 !in page.startOffset until page.endOffset)
            return null
        return if (current.visiblePage == page.pageIndex) current else publish(current.range, page.pageIndex)
    }

    /** Only a caller-verified next page of this chapter may extend the range. No implicit navigation. */
    fun extendToPage(page: PageSlice, expectedRevision: Long): Snapshot? {
        val current = snapshot ?: return null
        if (current.revision != expectedRevision || page.pageIndex != (current.visiblePage ?: return null) + 1 ||
            page.startOffset < current.range.start || page.endOffset <= current.range.end || page.endOffset > text.length) return null
        extensions += current
        return publish(TextSelectionRange(current.range.start, page.endOffset), page.pageIndex)
    }

    fun retract(expectedRevision: Long): Snapshot? {
        val current = snapshot ?: return null
        if (current.revision != expectedRevision || extensions.isEmpty()) return null
        val previous = extensions.removeAt(extensions.lastIndex)
        return publish(previous.range, previous.visiblePage)
    }

    private fun normalize(range: TextSelectionRange): TextSelectionRange? {
        val normalized = range.normalized()
        val start = floorCharacter(normalized.start.coerceIn(0, text.length))
        val end = ceilCharacter(normalized.end.coerceIn(0, text.length))
        return TextSelectionRange(start, end).takeUnless { it.isEmpty }
    }

    private fun publish(range: TextSelectionRange, page: Int?): Snapshot =
        Snapshot(session, ++revision, range, page, extensions.isNotEmpty()).also { snapshot = it }

    // UTF-16 boundaries keep combining marks, surrogate pairs and joined emoji together.
    private val boundaries: List<Int> by lazy {
        buildList {
            add(0)
            var index = 0
            var regionalCount = 0
            while (index < text.length) {
                val start = index
                val high = text[index]
                val code = if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    index += 2
                    0x10000 + ((high.code - 0xD800) shl 10) + (text[index - 1].code - 0xDC00)
                } else { index++; high.code }
                val mark = high.category in setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)
                val regional = code in 0x1F1E6..0x1F1FF
                val joins = mark || code in 0xFE00..0xFE0F || code in 0x1F3FB..0x1F3FF || code in 0xE0100..0xE01EF || code in 0xE0020..0xE007F || code == 0x200D ||
                    (start > 0 && text[start - 1] == '\u200D') || (regional && regionalCount % 2 == 1) ||
                    (code == 10 && start > 0 && text[start - 1] == '\r')
                if (start > 0 && !joins) add(start)
                regionalCount = if (regional) regionalCount + 1 else 0
            }
            if (last() != text.length) add(text.length)
        }
    }
    private fun floorCharacter(offset: Int) = boundaries.last { it <= offset }
    private fun ceilCharacter(offset: Int) = boundaries.first { it >= offset }
    private fun nextCharacter(offset: Int) = boundaries.firstOrNull { it > offset } ?: text.length
    private fun previousCharacter(offset: Int) = boundaries.lastOrNull { it < offset } ?: 0
}
