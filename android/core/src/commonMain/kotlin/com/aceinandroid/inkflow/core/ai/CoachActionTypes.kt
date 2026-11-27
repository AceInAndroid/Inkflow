package com.aceinandroid.inkflow.core.ai

import com.aceinandroid.inkflow.core.ReadingContextSnapshot

/** Explicit local commands. These IDs never authorize a model request or a background task. */
object CoachActionTypes {
    const val SAVE_GOAL = "reading_goal_save"
    const val SAVE_DIFFICULTY = "reading_difficulty_save"
    const val REVIEW_CARD = "reading_memory_card_review"
    fun requireKnown(id: String): String = id.also {
        require(it in setOf(SAVE_GOAL, SAVE_DIFFICULTY, REVIEW_CARD)) { "Unknown coach action" }
    }
}

enum class CoachCardRating(val id: String) {
    HARD("hard"), REMEMBERED("remembered"), MASTERED("mastered");
    companion object {
        fun fromId(id: String): CoachCardRating = entries.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown card rating")
    }
}

enum class CoachDifficultyType(val id: String) {
    CONCEPT("concept"), ARGUMENT("argument"), BACKGROUND("background"), QUESTION("question"), LATER("later");
    companion object {
        fun fromId(id: String): CoachDifficultyType = entries.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown difficulty type")
    }
}

/** JSON fields use the existing v22/Flutter shapes and remain opaque to the UI. */
data class CoachGoalDraft(val title: String, val rangeJson: String = "{}",
                          val timeBudgetMinutes: Int? = null, val criteriaJson: String = "[]") {
    fun validate(): CoachGoalDraft = apply {
        require(title.isNotBlank() && title.length <= 500)
        require(timeBudgetMinutes == null || timeBudgetMinutes > 0)
        require(rangeJson.length <= 32_000 && criteriaJson.length <= 32_000)
    }
}

data class CoachGoal(val id: String, val bookId: Long, val draft: CoachGoalDraft,
                     val progress: Double, val status: String, val updatedAt: Long, val stateHash: String)
/** Existing memory-card/review tables are in full backups, but not the v1 ReadingAgentDelta table list.
 * Keep this model local until that shared protocol is explicitly extended; do not map it to tb_knowledge_cards. */
data class CoachCard(val id: String, val bookId: Long, val question: String, val answer: String,
                     val reviewStage: Int, val nextReviewAt: Long, val hardCount: Int,
                     val rememberedCount: Int, val masteredCount: Int, val updatedAt: Long,
                     val sources: List<BookMaterialSource>, val stateHash: String)
data class CoachCardReview(val id: String, val cardId: String, val rating: CoachCardRating,
                           val previousStage: Int, val nextStage: Int, val previousReviewAt: Long,
                           val nextReviewAt: Long, val reviewedAt: Long)
data class CoachCardSchedule(val stage: Int, val intervalDays: Int, val nextReviewAt: Long)

/** Matches reading_review_scheduler.dart: stages are one-based; all intervals are full days. */
object CoachCardScheduler {
    val intervals: List<Int> = listOf(1, 3, 7, 14, 30)
    fun schedule(currentStage: Int, rating: CoachCardRating, now: Long): CoachCardSchedule {
        require(currentStage in 1..5 && now >= 0)
        val delta = when (rating) { CoachCardRating.HARD -> -1; CoachCardRating.REMEMBERED -> 1; CoachCardRating.MASTERED -> 2 }
        val stage = (currentStage + delta).coerceIn(1, 5)
        val days = intervals[stage - 1]
        val interval = days.toLong() * 86_400_000
        require(now <= Long.MAX_VALUE - interval)
        return CoachCardSchedule(stage, days, now + interval)
    }
}

/** Called only after the native source resolver has verified the same slice in the real book. */
object CoachSelectionEvidence {
    fun capture(source: BookMaterialSource, snapshot: ReadingContextSnapshot, bookProgress: Double): ReadingEvidence {
        require(bookProgress.isFinite() && bookProgress in 0.0..1.0)
        require(snapshot.readingProgress.isFinite() && snapshot.readingProgress in 0.0..1.0)
        require(snapshot.selectionWithinBoundary && snapshot.selectedText == source.text)
        require(snapshot.chapterIndex >= 0 && snapshot.readOffset >= 0)
        require(source.text.isNotBlank() && source.text.length <= 10_000 && source.cfi.isNotBlank())
        require(source.chapterHref.isNotBlank() && source.chapterIndex in 0..snapshot.chapterIndex)
        require(source.endOffset >= source.text.length)
        require(source.chapterIndex < snapshot.chapterIndex || source.endOffset <= snapshot.readOffset)
        require(source.progress.isFinite() && source.progress in 0.0..minOf(bookProgress, snapshot.readingProgress))
        val start = source.endOffset - source.text.length
        val hash = readingContentHash(source.text)
        val id = "native.coach.selection.v1:${readingContentHash(source.chapterHref)}:$start:${source.endOffset}:$hash"
        return ReadingEvidence(source.chapterHref, source.chapterTitle, source.text, start, source.endOffset,
            source.progress, id, hash, source.chapterIndex)
    }
}
