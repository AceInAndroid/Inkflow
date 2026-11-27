package com.aceinandroid.inkflow.ui.ai

import com.aceinandroid.inkflow.core.ai.CoachCard
import com.aceinandroid.inkflow.core.ai.CoachGoalDraft
import org.junit.Assert.*
import org.junit.Test

class ReadingPracticeContentTest {
    @Test fun newGoalHasCompatibleDefaultsAndOptionalMinutes() {
        assertEquals(CoachGoalDraft("Read the argument"), practiceGoalDraft("  Read the argument  ", " "))
        assertEquals(25, practiceGoalDraft("Read", " 25 ")?.timeBudgetMinutes)
    }

    @Test fun editKeepsRangeAndCriteriaAndCanClearMinutes() {
        val old = CoachGoalDraft("Old", "{\"chapter\":2}", 20, "[{\"title\":\"Explain premise\"}]")
        assertEquals(old.copy(title = "New", timeBudgetMinutes = null), practiceGoalDraft("New", "", old))
        assertEquals(old.copy(title = "New", timeBudgetMinutes = 15), practiceGoalDraft("New", "15", old))
    }

    @Test fun emptyAndOverlongTitlesCannotBeSaved() {
        assertNull(practiceGoalDraft(" \n ", ""))
        assertNull(practiceGoalDraft("x".repeat(501), "10"))
        assertNotNull(practiceGoalDraft("x".repeat(500), "10"))
    }

    @Test fun invalidAndOverflowingMinutesCannotBeSaved() {
        listOf("0", "-1", "+1", "1.5", "abc", "1e3", "2147483648", "99999999999999999999", "１", "1 2").forEach {
            assertNull("Invalid minutes: $it", practiceGoalDraft("Read", it))
        }
        assertEquals(Int.MAX_VALUE, practiceGoalDraft("Read", Int.MAX_VALUE.toString())?.timeBudgetMinutes)
    }

    @Test fun dueFilterIncludesExactDeadlineAndSortsStably() {
        val cards = listOf(card("future", 101), card("b", 100), card("older", 5), card("a", 100))
        assertEquals(listOf("older", "a", "b"), practiceCardsForView(cards, true, 100).map { it.id })
        assertEquals(listOf("older", "a", "b", "future"), practiceCardsForView(cards, false, 100).map { it.id })
        assertEquals(listOf("future", "b", "older", "a"), cards.map { it.id })
    }

    @Test fun futureCardsBecomeDueWhenTimeAdvancesWithoutChangingStoredState() {
        val card = card("future", 500)
        assertTrue(practiceCardsForView(listOf(card), true, 499).isEmpty())
        assertEquals(listOf(card), practiceCardsForView(listOf(card), true, 500))
        assertTrue(practiceCardsForView(emptyList(), false, 500).isEmpty())
    }

    private fun card(id: String, due: Long) = CoachCard(id, 1, "Question", "Answer", 1, due,
        0, 0, 0, 1, emptyList(), "state-$id")
}
