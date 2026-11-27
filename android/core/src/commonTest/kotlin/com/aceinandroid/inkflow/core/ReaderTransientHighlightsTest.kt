package com.aceinandroid.inkflow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReaderTransientHighlightsTest {
    @Test fun explicitSourceExpiresBackToActualNarrationWithoutCrossChapterPaint() {
        val highlights = ReaderTransientHighlights(7)
        highlights.put(ReaderTransientHighlight(7, 1, TextSelectionRange(4, 9), ReaderHighlightKind.NARRATION), 100)
        highlights.put(ReaderTransientHighlight(7, 1, TextSelectionRange(20, 30), ReaderHighlightKind.AI_SOURCE, 500), 100)
        assertEquals(ReaderHighlightKind.AI_SOURCE, highlights.visible(1, 499)?.kind)
        assertNull(highlights.visible(2, 499))
        assertEquals(ReaderHighlightKind.NARRATION, highlights.visible(1, 500)?.kind)
        highlights.clear(ReaderHighlightKind.NARRATION)
        assertNull(highlights.visible(1, 501))
    }

    @Test fun rejectsOtherBooksAndInvalidSourceRanges() {
        val highlights = ReaderTransientHighlights(7)
        assertFailsWith<IllegalArgumentException> {
            highlights.put(ReaderTransientHighlight(8, 0, TextSelectionRange(0, 1), ReaderHighlightKind.SEARCH), 10)
        }
        assertFailsWith<IllegalArgumentException> {
            highlights.put(ReaderTransientHighlight(7, 0, TextSelectionRange(8, 11), ReaderHighlightKind.SEARCH), 10)
        }
        assertFailsWith<IllegalArgumentException> {
            highlights.put(ReaderTransientHighlight(7, 0, TextSelectionRange(5, 5), ReaderHighlightKind.SEARCH), 10)
        }
    }

    @Test fun clearingOnBookDisposalDropsEveryProducer() {
        val highlights = ReaderTransientHighlights(7)
        ReaderHighlightKind.entries.forEach {
            highlights.put(ReaderTransientHighlight(7, 0, TextSelectionRange(0, 4), it), 20)
        }
        highlights.clear()
        assertNull(highlights.visible(0, 0))
    }
}
