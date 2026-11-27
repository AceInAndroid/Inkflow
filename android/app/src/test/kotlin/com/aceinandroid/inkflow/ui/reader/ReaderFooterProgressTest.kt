package com.aceinandroid.inkflow.ui.reader

import com.aceinandroid.inkflow.core.PageSlice
import com.aceinandroid.inkflow.core.PaginationSummary
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ReaderFooterProgressTest {
    @Test fun firstPageInOneLongChapterDoesNotClaimCompletion() {
        assertEquals("0%", readerFooterProgress(0, 1, 424, 100_000, Locale.US))
        assertEquals("42%", readerFooterProgress(0, 1, 42_400, 100_000, Locale.US))
    }

    @Test fun chapterFractionIncludesItsActualVisibleOffset() {
        assertEquals("12%", readerFooterProgress(1, 10, 25_000, 100_000, Locale.US))
        assertEquals("10%", readerFooterProgress(1, 10, 0, 100_000, Locale.US))
    }

    @Test fun trailingBlankPaneDoesNotAdvanceAnOddSpreadToTheBookEnd() {
        val leaves = PaginationSummary((0..2).map { index ->
            PageSlice(index, index * 100, (index + 1) * 100, index, index)
        }, 300)
        val spreads = readerViewportPagination(leaves, 2)
        val anchor = readerVisibleAnchor(null, "spread", spreads, 1)
        assertEquals(200, anchor.offset)
        assertEquals("66%", readerFooterProgress(0, 1, anchor.offset, leaves.totalLength, Locale.US))
    }

    @Test fun restoredRightPaneAnchorIsKeptInsteadOfUsingEitherPageEnd() {
        val leaves = PaginationSummary((0..3).map { index ->
            PageSlice(index, index * 100, (index + 1) * 100, index, index)
        }, 400)
        val spreads = readerViewportPagination(leaves, 2)
        val anchor = readerVisibleAnchor(null, "spread", spreads, 1, explicitOffset = 357)
        assertEquals("89%", readerFooterProgress(0, 1, anchor.offset, leaves.totalLength, Locale.US))
    }

    @Test fun onlyAnActualEndAnchorCanShowOneHundredPercent() {
        assertEquals("99%", readerFooterProgress(0, 1, 999, 1000, Locale.US))
        assertEquals("100%", readerFooterProgress(0, 1, 1000, 1000, Locale.US))
    }

    @Test fun invalidAndEmptySourcesDoNotInventAnEndOffset() {
        assertEquals("", readerFooterProgress(0, 0, 10, 10, Locale.US))
        assertEquals("0%", readerFooterProgress(-1, 2, -4, 100, Locale.US))
        assertEquals("50%", readerFooterProgress(1, 2, 10, 0, Locale.US))
        assertEquals("100%", readerFooterProgress(3, 2, 200, 100, Locale.US))
    }

    @Test fun integerPercentUsesTheRequestedLocale() {
        assertEquals("42\u00a0%", readerFooterProgress(0, 1, 424, 1000, Locale.GERMANY))
        val arabic = readerFooterProgress(0, 1, 424, 1000, Locale.forLanguageTag("ar-EG"))
        assertTrue(arabic.contains("٤٢"))
        assertTrue(arabic.contains('٪'))
        assertFalse(arabic.contains("42"))
    }
}
