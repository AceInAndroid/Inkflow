package com.aceinandroid.inkflow.core.ai

/** A bounded projection of persisted materials; never reads chapters or calls a provider. */
class ReadingContextPackBuilder(private val projector: BookWikiProjector = BookWikiProjector()) {
    fun build(bookId: Long, boundary: Double, materials: List<BookMaterial>, query: String = "",
              chapterHref: String = "", charBudget: Int = 4000, chapterIndex: Int = -1, readOffset: Int = -1): List<BookContextNode> {
        require(charBudget in 0..16000)
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        val ranked = projector.visible(bookId, boundary, materials, chapterIndex, readOffset).sortedByDescending { material ->
            (if (material.sources.any { it.chapterHref == chapterHref && chapterHref.isNotBlank() }) 100 else 0) +
                words.count { material.title.lowercase().contains(it) || material.summary.lowercase().contains(it) }
        }
        var remaining = charBudget
        return buildList {
            for (item in ranked) {
                if (remaining <= 0) break
                val source = item.sources.first()
                val citations = item.sources.mapIndexed { index, evidence -> AiCitation("${item.id}:$index", bookId, evidence) }
                val text = "[source:${citations.first().id}; ${item.epistemicStatus}; ${source.chapterTitle}; ${source.progress}] ${item.title}\n${item.summary}\n${item.body}".take(remaining)
                if (text.isBlank()) continue
                remaining -= text.length
                add(BookContextNode(text, (item.visibleFromProgress * 100).toInt(),
                    when { item.kind.startsWith("fiction.") -> "atlas"; item.kind.startsWith("note.") -> "note"; else -> "wiki" }, item.visibleFromProgress, citations))
            }
        }
    }
}
