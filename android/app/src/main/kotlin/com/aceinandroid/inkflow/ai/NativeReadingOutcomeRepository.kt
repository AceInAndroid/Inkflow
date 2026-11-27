package com.aceinandroid.inkflow.ai

import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.BookParser
import com.aceinandroid.inkflow.EpubCfi
import org.json.JSONObject
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
            val source = storedSource(bookId, row.safeString("chapter_href"), row.safeString("cfi"),
                row.safeString("selected_text"), snapshot)
            if (source != null && row.safeString("status") in setOf("unresolved", "resolved", "ignored") &&
                CoachDifficultyType.entries.any { it.id == row.safeString("difficulty_type") })
                result += ReadingOutcome(row.getString("id"), bookId, ReadingOutcomeKind.DIFFICULTY,
                    row.getString("selected_text"), row.getString("status"), row.getLong("updated_at"), source)
        }
        cards(bookId, snapshot).forEach { card ->
            result += ReadingOutcome(card.id, bookId, ReadingOutcomeKind.CARD, card.question, "active",
                card.updatedAt, card.sources.first(), card.nextReviewAt)
        }
        return result
    }

    fun goals(bookId: Long): List<CoachGoal> = ReadingSql.rows(store.readableDatabase,
        "tb_reading_goals", "book_id = ?", arrayOf(bookId.toString())).mapNotNull { row ->
        if (row.safeString("status") !in setOf("active", "completed", "abandoned")) return@mapNotNull null
        CoachGoal(row.getString("id"), bookId, CoachGoalDraft(row.getString("title"), row.getString("range_json"),
            if (row.isNull("time_budget_minutes")) null else row.getInt("time_budget_minutes"), row.getString("criteria_json")),
            row.getDouble("progress"), row.getString("status"), row.getLong("updated_at"), ReadingSql.hash(row))
    }.sortedByDescending { it.updatedAt }

    fun activeGoal(bookId: Long): CoachGoal? = goals(bookId).singleOrNull { it.status == "active" }

    /** Validates the actual file, CFI, quote, exact cursor and progress. No full-view grant is accepted. */
    fun difficultyEvidence(bookId: Long, source: BookMaterialSource, snapshot: ReadingContextSnapshot): ReadingEvidence? = runCatching {
        val book = store.all().single { it.id == bookId }
        require(book.title == snapshot.bookTitle)
        val actual = requireNotNull(storedSource(bookId, source.chapterHref, source.cfi, source.text, snapshot))
        require(source.chapterIndex == actual.chapterIndex && source.endOffset == actual.endOffset)
        require(NativeBookSourceResolver(store).resolve(bookId, source, snapshot) != null)
        CoachSelectionEvidence.capture(source.copy(chapterTitle = actual.chapterTitle,
            progress = maxOf(source.progress, actual.progress)), snapshot, book.progress)
    }.getOrNull()

    fun cards(bookId: Long, snapshot: ReadingContextSnapshot): List<CoachCard> {
        val db = store.readableDatabase
        val book = store.all().singleOrNull { it.id == bookId } ?: return emptyList()
        require(book.title == snapshot.bookTitle)
        return ReadingSql.rows(db, "tb_reading_knowledge_cards", "book_id = ? AND status = 'active'", arrayOf(bookId.toString()))
            .mapNotNull { row ->
                if (row.getInt("review_stage") !in 1..5 || row.getLong("next_review_at") < 0 ||
                    listOf("hard_count", "remembered_count", "mastered_count").any { row.getInt(it) < 0 }) return@mapNotNull null
                val refs = ReadingSql.rows(db, "tb_reading_card_sources", "card_id = ?", arrayOf(row.getString("id")))
                if (refs.isEmpty()) return@mapNotNull null
                val memories = refs.map { ref ->
                    ReadingSql.rows(db, "tb_reading_memory_sources", "id = ? AND book_id = ?",
                        arrayOf(ref.getString("source_id"), bookId.toString())).singleOrNull() ?: return@mapNotNull null
                }
                if (memories.any { it.safeString("source_type") !in setOf("difficulty", "annotation", "readingNote") }) return@mapNotNull null
                val safe = memories.map { memory -> storedSource(bookId, memory.safeString("chapter_href"),
                    memory.safeString("cfi"), memory.safeString("text_snapshot"), snapshot) ?: return@mapNotNull null }
                val aggregate = JSONObject().put("card", row).put("refs", ReadingSql.array(refs)).put("sources", ReadingSql.array(memories))
                CoachCard(row.getString("id"), bookId, row.getString("question"), row.getString("answer"), row.getInt("review_stage"),
                    row.getLong("next_review_at"), row.getInt("hard_count"), row.getInt("remembered_count"), row.getInt("mastered_count"),
                    row.getLong("updated_at"), safe, ReadingSql.hash(aggregate))
            }
    }

    fun cardReviews(bookId: Long, cardId: String, snapshot: ReadingContextSnapshot): List<CoachCardReview> {
        if (cards(bookId, snapshot).none { it.id == cardId }) return emptyList()
        return ReadingSql.rows(store.readableDatabase, "tb_reading_card_reviews", "book_id = ? AND card_id = ?",
            arrayOf(bookId.toString(), cardId)).mapNotNull { row ->
            val rating = runCatching { CoachCardRating.fromId(row.getString("rating")) }.getOrNull() ?: return@mapNotNull null
            CoachCardReview(row.getString("id"), cardId, rating, row.getInt("previous_stage"), row.getInt("next_stage"),
                row.getLong("previous_review_at"), row.getLong("next_review_at"), row.getLong("reviewed_at"))
        }.sortedByDescending { it.reviewedAt }
    }

    /** Legacy sources without an exact usable CFI remain hidden; never guess by matching repeated text. */
    private fun storedSource(bookId: Long, href: String, cfi: String, text: String,
                             snapshot: ReadingContextSnapshot): BookMaterialSource? = runCatching {
        require(snapshot.chapterIndex >= 0 && snapshot.readOffset >= 0 && text.isNotBlank() && cfi.isNotBlank())
        val book = store.all().single { it.id == bookId }
        require(book.title == snapshot.bookTitle && snapshot.readingProgress.isFinite() && snapshot.readingProgress in 0.0..1.0)
        val document = BookParser.inspect(book.file, book.title)
        val native = Regex("native:chapter:([0-9]+):([0-9]+)").matchEntire(cfi)
        val index = if (book.format == "epub") requireNotNull(EpubCfi.chapterIndex(cfi, document.chapters))
            else requireNotNull(native).groupValues[1].toInt()
        require(index in 0..snapshot.chapterIndex)
        val chapter = requireNotNull(document.chapters.getOrNull(index))
        require(chapter.path == href)
        val epub = if (book.format == "epub") BookParser.readEpubText(book.file, chapter) else null
        val start = if (epub != null) requireNotNull(epub.offsetsForRange(cfi)?.first ?: epub.offsetForCfi(cfi))
            else requireNotNull(native).groupValues[2].toInt()
        require(start >= 0 && text.length <= Int.MAX_VALUE - start)
        val end = start + text.length
        if (epub != null) epub.offsetsForRange(cfi)?.let { require(it.last + 1 == end) }
        require(index < snapshot.chapterIndex || end <= snapshot.readOffset)
        val chapterText = epub?.text ?: BookParser.readChapter(book.file, chapter)
        require(end <= chapterText.length && chapterText.substring(start, end) == text)
        val progress = (index + end.toDouble() / chapterText.length.coerceAtLeast(1)) / document.chapters.size
        val source = BookMaterialSource(href, chapter.title, cfi, text, progress, index, end)
        requireNotNull(NativeBookSourceResolver(store).resolve(bookId, source, snapshot))
    }.getOrNull()
}
