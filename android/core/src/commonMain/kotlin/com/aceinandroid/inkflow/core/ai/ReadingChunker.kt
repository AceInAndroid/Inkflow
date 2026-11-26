package com.aceinandroid.inkflow.core.ai

/** UTF-16 offsets always address the original, unmodified chapter. */
data class ReadingChunk(
    val id: String, val chapterHref: String, val startOffset: Int, val endOffset: Int,
    val content: String, val contentHash: String, val pipelineVersion: String,
)

class ReadingChunker(val maxChars: Int = 6000, val pipelineVersion: String = "native.reading.v1") {
    init { require(maxChars >= 64 && pipelineVersion.isNotBlank()) }

    fun chunk(chapterHref: String, text: String): List<ReadingChunk> {
        require(chapterHref.isNotBlank())
        val result = mutableListOf<ReadingChunk>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length) {
                val floor = start + maxChars / 2
                val paragraph = text.lastIndexOf("\n\n", end - 2).takeIf { it >= floor }?.plus(2)
                val sentence = (end - 1 downTo floor).firstOrNull { text[it] in "。！？.!?\n" }?.plus(1)
                end = paragraph ?: sentence ?: end
                if (end > start && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            val content = text.substring(start, end)
            val hash = readingContentHash(content)
            if (content.isNotBlank()) result += ReadingChunk(
                "$pipelineVersion:${readingContentHash(chapterHref)}:$start:$end:$hash",
                chapterHref, start, end, content, hash, pipelineVersion,
            )
            start = end
        }
        return result
    }
}

/** Portable deterministic fingerprint, for invalidation and identity, never Evidence. */
fun readingContentHash(value: String): String {
    var hash = 14695981039346656037uL
    for (ch in value) { hash = (hash xor ch.code.toULong()) * 1099511628211uL }
    return hash.toString(16).padStart(16, '0')
}
