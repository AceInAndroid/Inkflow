package com.aceinandroid.inkflow.core

/** Shared selection rules for UTF-16 pairs, combining marks and joined emoji (not full UAX #29). */
internal object TextCharacterBoundaries {
    fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        return if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate())
            0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
        else high.code
    }

    fun nextCodePoint(text: String, index: Int): Int =
        index + if (codePointAt(text, index) > 0xFFFF) 2 else 1

    fun previousCodePoint(text: String, index: Int): Int =
        if (index >= 2 && text[index - 1].isLowSurrogate() && text[index - 2].isHighSurrogate()) index - 2 else index - 1

    private fun joins(text: String, start: Int, code: Int, regionalCount: Int): Boolean {
        val mark = text[start].category in setOf(
            CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)
        return mark || code in 0xFE00..0xFE0F || code in 0x1F3FB..0x1F3FF ||
            code in 0xE0100..0xE01EF || code in 0xE0020..0xE007F || code == 0x200D ||
            (start > 0 && text[start - 1] == '\u200D') ||
            (code in 0x1F1E6..0x1F1FF && regionalCount % 2 == 1) ||
            (code == 10 && start > 0 && text[start - 1] == '\r')
    }

    fun all(text: String): List<Int> = buildList {
        add(0)
        var index = 0
        var regionalCount = 0
        while (index < text.length) {
            val start = index
            val code = codePointAt(text, index)
            index = nextCodePoint(text, index)
            if (start > 0 && !joins(text, start, code, regionalCount)) add(start)
            regionalCount = if (code in 0x1F1E6..0x1F1FF) regionalCount + 1 else 0
        }
        if (last() != text.length) add(text.length)
    }

    fun isBoundary(text: String, index: Int): Boolean {
        if (index == 0 || index == text.length) return true
        if (text[index].isLowSurrogate() && text[index - 1].isHighSurrogate()) return false
        val code = codePointAt(text, index)
        var regionalCount = 0
        if (code in 0x1F1E6..0x1F1FF) {
            var previous = index
            while (previous > 0) {
                previous = previousCodePoint(text, previous)
                if (codePointAt(text, previous) !in 0x1F1E6..0x1F1FF) break
                regionalCount++
            }
        }
        return !joins(text, index, code, regionalCount)
    }

    /** Scan only the touched cluster; no chapter-wide allocation on a word-selection request. */
    fun rangeAt(text: String, offset: Int): TextSelectionRange {
        var start = offset.coerceIn(0, text.lastIndex)
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
        var end = nextCodePoint(text, start)
        while (!isBoundary(text, start)) start = previousCodePoint(text, start)
        while (!isBoundary(text, end)) end = nextCodePoint(text, end)
        return TextSelectionRange(start, end)
    }
}
