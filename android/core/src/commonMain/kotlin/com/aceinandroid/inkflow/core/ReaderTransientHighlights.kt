package com.aceinandroid.inkflow.core

/** Ephemeral paint only: never an annotation, a reading position, or an AI authorization. */
enum class ReaderHighlightKind(val priority: Int) {
    AUTO_READING(10), NARRATION(20), SEARCH(30), AI_SOURCE(40),
}

data class ReaderTransientHighlight(
    val bookId: Long,
    val chapterIndex: Int,
    val range: TextSelectionRange,
    val kind: ReaderHighlightKind,
    val expiresAtMillis: Long? = null,
)

/** One lifecycle per producer; explicit navigation takes precedence over playback following. */
class ReaderTransientHighlights(private val bookId: Long) {
    private val values = mutableMapOf<ReaderHighlightKind, ReaderTransientHighlight>()

    fun put(highlight: ReaderTransientHighlight, chapterLength: Int) {
        require(highlight.bookId == bookId && highlight.chapterIndex >= 0)
        require(highlight.range.start >= 0 && highlight.range.end <= chapterLength &&
            highlight.range.start < highlight.range.end)
        values[highlight.kind] = highlight
    }

    fun clear(kind: ReaderHighlightKind) { values.remove(kind) }
    fun clear() { values.clear() }

    fun visible(chapterIndex: Int, nowMillis: Long): ReaderTransientHighlight? {
        values.entries.removeAll { (_, value) -> value.expiresAtMillis?.let { nowMillis >= it } == true }
        return values.values.filter { it.chapterIndex == chapterIndex }.maxByOrNull { it.kind.priority }
    }
}
