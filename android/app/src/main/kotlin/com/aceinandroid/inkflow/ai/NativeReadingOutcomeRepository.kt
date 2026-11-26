package com.aceinandroid.inkflow.ai

import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.*

/** Existing v22 outcomes. Unverifiable legacy sources stay opaque rather than leaking on rewind. */
class NativeReadingOutcomeRepository(private val store: BookStore) {
    fun load(bookId: Long, snapshot: ReadingContextSnapshot): List<ReadingOutcome> {
        val db = store.readableDatabase
        val book = ReadingSql.rows(db, "tb_books", "id = ? AND is_deleted = 0", arrayOf(bookId.toString())).singleOrNull() ?: return emptyList()
        require(book.getString("title") == snapshot.bookTitle)
        val boundary = minOf(book.getDouble("reading_percentage"), snapshot.readingProgress)
        val materials = BookWikiRepository(store).materials(bookId, boundary, snapshot)
        val sources = materials.flatMap { it.sources }
        val result = mutableListOf<ReadingOutcome>()
        ReadingSql.rows(db, "tb_reading_goals", "book_id = ?", arrayOf(bookId.toString())).filter {
            it.getString("status") in setOf("active", "completed", "abandoned") }.forEach {
            result += ReadingOutcome(it.getString("id"), bookId, ReadingOutcomeKind.GOAL, it.getString("title"), it.getString("status"), it.getLong("updated_at"))
        }
        ReadingSql.rows(db, "tb_reading_checkpoints", "book_id = ?", arrayOf(bookId.toString())).forEach { row ->
            val source = sources.firstOrNull { it.chapterHref == row.getString("chapter_href") &&
                it.chapterIndex >= 0 && it.chapterIndex < snapshot.chapterIndex }
            if (source != null && row.getDouble("progress") in 0.0..boundary && row.getString("status") in setOf("pending", "completed", "skipped"))
                result += ReadingOutcome(row.getString("id"), bookId, ReadingOutcomeKind.CHECKPOINT, row.getString("chapter_title"),
                    row.getString("status"), row.getLong("updated_at"), source)
        }
        ReadingSql.rows(db, "tb_reading_difficulties", "book_id = ?", arrayOf(bookId.toString())).forEach { row ->
            val text = row.getString("selected_text")
            val source = sources.firstOrNull { text.isNotBlank() && it.text.contains(text) &&
                (it.cfi.isNotBlank() && it.cfi == row.safeString("cfi") || it.chapterHref.isNotBlank() && it.chapterHref == row.safeString("chapter_href")) }
            if (source != null && row.getString("status") in setOf("unresolved", "resolved", "ignored"))
                result += ReadingOutcome(row.getString("id"), bookId, ReadingOutcomeKind.DIFFICULTY, text,
                    row.getString("status"), row.getLong("updated_at"), source)
        }
        ReadingSql.rows(db, "tb_reading_knowledge_cards", "book_id = ? AND status = 'active'", arrayOf(bookId.toString())).forEach { row ->
            val refs = ReadingSql.rows(db, "tb_reading_card_sources", "card_id = ?", arrayOf(row.getString("id")))
            val safe = refs.mapNotNull { ref ->
                val memory = ReadingSql.rows(db, "tb_reading_memory_sources", "id = ? AND book_id = ?",
                    arrayOf(ref.getString("source_id"), bookId.toString())).singleOrNull()
                if (memory == null) null else sources.firstOrNull { source ->
                    val text = memory.safeString("text_snapshot")
                    text.isNotBlank() && source.text.contains(text) && (source.cfi.isNotBlank() && source.cfi == memory.safeString("cfi") ||
                        source.chapterHref.isNotBlank() && source.chapterHref == memory.safeString("chapter_href"))
                }
            }
            if (refs.isNotEmpty() && safe.size == refs.size)
                result += ReadingOutcome(row.getString("id"), bookId, ReadingOutcomeKind.CARD, row.getString("question"), "active",
                    row.getLong("updated_at"), safe.first(), row.getLong("next_review_at"))
        }
        return result
    }
}
