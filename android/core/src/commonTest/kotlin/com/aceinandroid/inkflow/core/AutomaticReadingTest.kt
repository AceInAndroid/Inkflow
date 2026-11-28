package com.aceinandroid.inkflow.core

import kotlin.test.*

class AutomaticReadingTest {
    @Test fun independentlyOwnedPausesMustAllBeReleased() {
        var state = AutomaticReadingState(enabled = true)
        AutomaticReadingPause.entries.forEach { state = state.pause(it, true) }
        AutomaticReadingPause.entries.dropLast(1).forEach {
            state = state.pause(it, false)
            assertFalse(state.advancing)
        }
        state = state.pause(AutomaticReadingPause.entries.last(), false)
        assertTrue(state.advancing)
        assertFalse(state.copy(enabled = false).advancing)
    }

    @Test fun repeatedPauseAndReleaseAreIdempotent() {
        val state = AutomaticReadingState(true).pause(AutomaticReadingPause.SELECTION, true)
        assertEquals(state, state.pause(AutomaticReadingPause.SELECTION, true))
        assertEquals(state, state.pause(AutomaticReadingPause.DIALOG, false))
        assertTrue(state.pause(AutomaticReadingPause.SELECTION, false).advancing)
    }

    @Test fun sentenceProgressSkipsWhitespaceAndImagesAndStopsAtTheEnd() {
        val text = " \n\uFFFC第一句。\n 第二句！"
        val first = AutomaticReading.sentenceAtOrAfter(text, 0)!!
        assertEquals("第一句。", text.substring(first.start, first.end))
        val next = AutomaticReading.sentenceAtOrAfter(text, first.end)!!
        assertEquals("第二句！", text.substring(next.start, next.end))
        assertNull(AutomaticReading.sentenceAtOrAfter(text, next.end))
        assertNull(AutomaticReading.sentenceAtOrAfter(text, Int.MAX_VALUE))
        assertNull(AutomaticReading.sentenceAtOrAfter(" \uFFFC\n", -1))
    }

    @Test fun timingScalesWithRealSentenceLengthAndSelectedSpeed() {
        val short = AutomaticReading.dwellMillis("阅读。", 1f)
        assertTrue(AutomaticReading.dwellMillis("阅读正文中的完整句子。", 1f) > short)
        assertTrue(AutomaticReading.dwellMillis("Read a complete sentence.", 0.5f) >
            AutomaticReading.dwellMillis("Read a complete sentence.", 3f))
        assertEquals(120_000L, AutomaticReading.dwellMillis("书".repeat(100_000), 0.5f))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 3.1f).forEach { speed ->
            assertFailsWith<IllegalArgumentException> { AutomaticReading.dwellMillis("text", speed) }
        }
    }
}
