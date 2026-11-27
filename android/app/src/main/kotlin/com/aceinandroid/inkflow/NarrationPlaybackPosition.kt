package com.aceinandroid.inkflow

import java.text.BreakIterator
import java.util.Locale

/** Half-open UTF-16 offsets into the exact text passed to NativeNarrator.play. */
data class NarrationTextRange(val start: Int, val end: Int)
enum class NarrationPositionPrecision { ENGINE_RANGE, CHUNK_BOUNDARY }
data class NarrationPosition(
    val offset: Int,
    val sourceLength: Int,
    val precision: NarrationPositionPrecision,
    val spokenRange: NarrationTextRange? = null,
    val sentence: NarrationTextRange? = null,
    val paragraph: NarrationTextRange? = null,
    val sourceId: String? = null,
)

/** The backend receives at most one real paragraph; offsets still refer to the original source. */
internal fun narrationParagraphRange(source: String, offset: Int): NarrationTextRange? {
    var start = offset.coerceIn(0, source.length)
    if (start in 1 until source.length && source[start].isLowSurrogate() && source[start - 1].isHighSurrogate()) start--
    while (start < source.length && (source[start].isWhitespace() || source[start] == '\uFFFC')) start++
    if (start == source.length) return null
    val newline = source.indexOfAny(charArrayOf('\n', '\r', '\u2028', '\u2029'), start)
    return NarrationTextRange(start, if (newline < 0) source.length else newline + 1)
}

/** No clock-to-text conversion: boundaries identify source paragraphs, never timed sentences. */
internal class NarrationPositionTracker {
    private var source = ""
    private var sourceId: String? = null
    private var utterance: String? = null
    private var chunk = NarrationTextRange(0, 0)
    private val sentences = BreakIterator.getSentenceInstance(Locale.ROOT)

    fun reset(text: String = "", sourceId: String? = null) {
        source = text
        this.sourceId = sourceId
        sentences.setText(text)
        invalidateUtterance()
    }
    fun invalidateUtterance() { utterance = null }
    fun start(id: String, start: Int, end: Int): NarrationPosition {
        require(id.isNotBlank() && start in 0..source.length && end in start..source.length)
        utterance = id
        chunk = NarrationTextRange(start, end)
        return boundary(start)
    }
    fun boundary(offset: Int): NarrationPosition {
        val actual = offset.coerceIn(0, source.length)
        return NarrationPosition(actual, source.length, NarrationPositionPrecision.CHUNK_BOUNDARY,
            paragraph = paragraphAt(actual), sourceId = sourceId)
    }
    private fun paragraphAt(offset: Int): NarrationTextRange? {
        if (offset !in source.indices) return null
        val separators = charArrayOf('\n', '\r', '\u2028', '\u2029')
        val start = if (offset == 0) 0 else source.lastIndexOfAny(separators, offset - 1) + 1
        val end = source.indexOfAny(separators, offset).takeIf { it >= 0 } ?: source.length
        return trim(start, end).takeIf { it.start < it.end }
    }

    fun range(id: String, start: Int, end: Int): NarrationPosition? {
        if (id != utterance || start < 0 || end <= start || end > chunk.end - chunk.start) return null
        val from = chunk.start + start
        val until = chunk.start + end
        if (splitsSurrogate(from) || splitsSurrogate(until)) return null
        val sentenceStart = sentences.preceding(from + 1).takeIf { it != BreakIterator.DONE } ?: 0
        val sentenceEnd = sentences.following(from).takeIf { it != BreakIterator.DONE } ?: source.length
        return NarrationPosition(from, source.length, NarrationPositionPrecision.ENGINE_RANGE,
            NarrationTextRange(from, until), trim(sentenceStart, sentenceEnd), paragraphAt(from), sourceId)
    }
    private fun splitsSurrogate(offset: Int) = offset in 1 until source.length &&
        source[offset].isLowSurrogate() && source[offset - 1].isHighSurrogate()
    private fun trim(start: Int, end: Int): NarrationTextRange {
        var from = start
        var until = end
        while (from < until && source[from].isWhitespace()) from++
        while (until > from && source[until - 1].isWhitespace()) until--
        return NarrationTextRange(from, until)
    }
}

/** A sleep deadline uses monotonic time and never represents playback progress. */
internal class NarrationSleepTimer {
    private var deadline: Long? = null
    fun set(minutes: Int?, now: Long) {
        require(minutes == null || minutes in 1..240)
        deadline = minutes?.let { now + it * 60_000L }
    }
    fun remaining(now: Long): Long? = deadline?.let { (it - now).coerceAtLeast(0) }
    fun clear() { deadline = null }
}

internal data class NarrationExcerpt(val sourceRange: NarrationTextRange, val highlightRange: NarrationTextRange)
internal fun narrationExcerpt(source: String, position: NarrationPosition?, maxChars: Int = 280): NarrationExcerpt? {
    require(maxChars > 0)
    if (position == null || source.length != position.sourceLength) return null
    val paragraph = position.paragraph ?: return null
    val spoken = position.spokenRange ?: paragraph
    if (spoken.start !in source.indices || spoken.end !in spoken.start + 1..source.length ||
        paragraph.start !in 0..spoken.start || paragraph.end !in spoken.start + 1..source.length) return null
    if (position.offset !in 0..source.length) return null
    val anchor = if (position.spokenRange != null) spoken.start else position.offset
    var start = maxOf(paragraph.start, anchor - maxChars / 3)
    var end = minOf(paragraph.end.toLong(), if (position.precision == NarrationPositionPrecision.ENGINE_RANGE)
        maxOf(start.toLong() + maxChars, spoken.end.toLong()) else start.toLong() + maxChars).toInt()
    if (start >= end) return null
    if (start in 1 until source.length && source[start].isLowSurrogate() && source[start - 1].isHighSurrogate()) start--
    if (end in 1 until source.length && source[end].isLowSurrogate() && source[end - 1].isHighSurrogate()) end++
    return NarrationExcerpt(NarrationTextRange(start, end), NarrationTextRange(maxOf(spoken.start, start), minOf(spoken.end, end)))
}
