package com.aceinandroid.inkflow.core.ai

import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import kotlin.test.*

class CoachActionTypesTest {
    private val source = BookMaterialSource("one", "One", "native:chapter:0:10", "Exact original selection", .4, 0, 34)
    private val snapshot = ReadingContextSnapshot("Book", "Author", "One", selectedText = source.text,
        readingProgress = .6, progressPercent = 60, chapterIndex = 0, readOffset = 40)

    @Test fun onlyRegisteredActionIdsCanExecute() {
        listOf(CoachActionTypes.SAVE_GOAL, CoachActionTypes.SAVE_DIFFICULTY, CoachActionTypes.REVIEW_CARD)
            .forEach { assertEquals(it, CoachActionTypes.requireKnown(it)) }
        listOf("", "future.action", "reading_goal_save ").forEach {
            assertFailsWith<IllegalArgumentException> { CoachActionTypes.requireKnown(it) }
        }
    }
    @Test fun ratingsUseExistingStableStoredIdsAndRejectUnknowns() {
        assertEquals(listOf("hard", "remembered", "mastered"), CoachCardRating.entries.map { it.id })
        CoachCardRating.entries.forEach { assertEquals(it, CoachCardRating.fromId(it.id)) }
        listOf("easy", "HARD", "").forEach { assertFailsWith<IllegalArgumentException> { CoachCardRating.fromId(it) } }
    }
    @Test fun difficultyTypesMatchExistingDomainAndFailClosed() {
        assertEquals(listOf("concept", "argument", "background", "question", "later"), CoachDifficultyType.entries.map { it.id })
        assertFailsWith<IllegalArgumentException> { CoachDifficultyType.fromId("future") }
    }
    @Test fun allFiveIntervalsAreTransparentAndOneBased() {
        for (stage in 1..5) {
            val result = CoachCardScheduler.schedule(maxOf(stage - 1, 1), CoachCardRating.REMEMBERED, 1000)
            val next = maxOf(stage, 2)
            assertEquals(next, result.stage)
            assertEquals(CoachCardScheduler.intervals[next - 1], result.intervalDays)
            assertEquals(1000 + result.intervalDays * 86_400_000L, result.nextReviewAt)
        }
        assertEquals(listOf(1, 3, 7, 14, 30), CoachCardScheduler.intervals)
    }
    @Test fun gradesFollowFlutterMinusOnePlusOnePlusTwoAndClamp() {
        assertEquals(1, CoachCardScheduler.schedule(1, CoachCardRating.HARD, 0).stage)
        assertEquals(1, CoachCardScheduler.schedule(2, CoachCardRating.HARD, 0).stage)
        assertEquals(3, CoachCardScheduler.schedule(2, CoachCardRating.REMEMBERED, 0).stage)
        assertEquals(4, CoachCardScheduler.schedule(2, CoachCardRating.MASTERED, 0).stage)
        assertEquals(5, CoachCardScheduler.schedule(4, CoachCardRating.MASTERED, 0).stage)
        assertEquals(5, CoachCardScheduler.schedule(5, CoachCardRating.REMEMBERED, 0).stage)
    }
    @Test fun invalidStagesAndClockOverflowCannotCreateSchedules() {
        listOf(0, 6, Int.MAX_VALUE).forEach { stage ->
            assertFailsWith<IllegalArgumentException> { CoachCardScheduler.schedule(stage, CoachCardRating.HARD, 0) }
        }
        assertFailsWith<IllegalArgumentException> { CoachCardScheduler.schedule(1, CoachCardRating.HARD, -1) }
        assertFailsWith<IllegalArgumentException> { CoachCardScheduler.schedule(5, CoachCardRating.MASTERED, Long.MAX_VALUE) }
    }
    @Test fun goalDraftKeepsOpaqueRangeAndCriteriaAndValidatesBasicLimits() {
        val draft = CoachGoalDraft("Read actively", "{\"chapter\":2}", 20, "[{\"text\":\"Explain it\"}]")
        assertSame(draft, draft.validate())
        assertFailsWith<IllegalArgumentException> { CoachGoalDraft(" ").validate() }
        assertFailsWith<IllegalArgumentException> { draft.copy(timeBudgetMinutes = 0).validate() }
        assertFailsWith<IllegalArgumentException> { draft.copy(rangeJson = "x".repeat(32001)).validate() }
    }
    @Test fun selectionEvidenceRetainsExactOffsetsAndPortableIdentity() {
        val evidence = CoachSelectionEvidence.capture(source, snapshot, .8)
        assertEquals(source.text, evidence.exactText)
        assertEquals(10, evidence.startOffset)
        assertEquals(34, evidence.endOffset)
        assertEquals(readingContentHash(source.text), evidence.contentHash)
        assertEquals(evidence, CoachSelectionEvidence.capture(source, snapshot, .8))
        assertNotEquals(evidence.chunkId, CoachSelectionEvidence.capture(source.copy(endOffset = 35), snapshot, .8).chunkId)
    }
    @Test fun unreadOffsetsAndChaptersAreRejectedEvenWithLowClaimedProgress() {
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source.copy(endOffset = 41, progress = .1), snapshot, .8) }
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source.copy(chapterIndex = 1, progress = .1), snapshot, .8) }
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source, snapshot.copy(readOffset = -1), .8) }
    }
    @Test fun bothBookAndCapturedProgressBoundTheEvidence() {
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source, snapshot, .3) }
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source, snapshot.copy(readingProgress = .3), .8) }
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source.copy(progress = Double.NaN), snapshot, .8) }
    }
    @Test fun selectionMustBeExplicitExactAndInsideCapturedBoundary() {
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source, snapshot.copy(selectedText = "other"), .8) }
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source, snapshot.copy(selectionWithinBoundary = false), .8) }
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source.copy(cfi = ""), snapshot, .8) }
        assertFailsWith<IllegalArgumentException> { CoachSelectionEvidence.capture(source.copy(endOffset = 1), snapshot, .8) }
    }
}
