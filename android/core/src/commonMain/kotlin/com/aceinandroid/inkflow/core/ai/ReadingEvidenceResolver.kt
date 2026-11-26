package com.aceinandroid.inkflow.core.ai

data class ReadingEvidence(
    val chapterHref: String, val chapterTitle: String, val exactText: String,
    val startOffset: Int, val endOffset: Int, val sourceProgress: Double,
    val chunkId: String, val contentHash: String, val chapterIndex: Int = -1,
)

/** Deterministic matching only. No keyword matching, model repair, or matches outside the chunk. */
class ReadingEvidenceResolver {
    fun resolve(chapterText: String, quote: String, chunk: ReadingChunk,
                chapterTitle: String, sourceProgress: Double, chapterIndex: Int = -1): ReadingEvidence? {
        if (!sourceProgress.isFinite() || sourceProgress !in 0.0..1.0 ||
            chunk.startOffset < 0 || chunk.endOffset > chapterText.length ||
            chunk.endOffset <= chunk.startOffset) return null
        val text = chapterText.substring(chunk.startOffset, chunk.endOffset)
        if (text != chunk.content || readingContentHash(text) != chunk.contentHash) return null
        val stripped = stripWrapper(quote.trim())
        if (stripped.count { !it.isWhitespace() } < 4) return null
        var start = text.indexOf(stripped)
        var end = start + stripped.length
        if (start < 0) {
            val normalized = normalize(text)
            val needle = normalize(stripped).text
            if (needle.count { !it.isWhitespace() } < 4) return null
            val match = normalized.text.indexOf(needle)
            if (match < 0) return null
            start = normalized.starts[match]
            end = normalized.ends[match + needle.length - 1]
        }
        return ReadingEvidence(chunk.chapterHref, chapterTitle, text.substring(start, end),
            chunk.startOffset + start, chunk.startOffset + end, sourceProgress, chunk.id, chunk.contentHash, chapterIndex)
    }

    private fun stripWrapper(text: String): String {
        val pairs = listOf("「" to "」", "『" to "』", "“" to "”", "\"" to "\"")
        for ((left, right) in pairs) if (text.startsWith(left) && text.endsWith(right) && text.length > 2)
            return text.substring(1, text.length - 1).trim()
        return text
    }

    private data class Normalized(val text: String, val starts: List<Int>, val ends: List<Int>)
    private fun normalize(source: String): Normalized {
        val text = StringBuilder()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        for ((index, char) in source.withIndex()) {
            val mapped = when {
                char.isWhitespace() || char == '\u3000' -> ' '
                char.code in 0xFF01..0xFF5E -> (char.code - 0xFEE0).toChar()
                char in "“”「」『』" -> '"'
                char in "‘’" -> '\''
                char == '。' -> '.'
                char == '、' -> ','
                else -> char
            }
            if (mapped == ' ' && text.lastOrNull() == ' ') { ends[ends.lastIndex] = index + 1; continue }
            text.append(mapped); starts += index; ends += index + 1
        }
        return Normalized(text.toString(), starts, ends)
    }
}
