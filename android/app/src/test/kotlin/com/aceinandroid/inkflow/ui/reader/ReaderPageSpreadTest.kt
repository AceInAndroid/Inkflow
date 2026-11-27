package com.aceinandroid.inkflow.ui.reader

import com.aceinandroid.inkflow.core.PageSlice
import com.aceinandroid.inkflow.core.PaginationSummary
import org.junit.Assert.*
import org.junit.Test

class ReaderPageSpreadTest {
    @Test fun defaultSinglePageKeepsOriginalSummaryAndOffsets() {
        val leaves = leaves(5)
        assertSame(leaves, readerViewportPagination(leaves, 1))
    }

    @Test fun spreadGroupsTwoRealPagesPerNavigationStep() {
        val spreads = readerViewportPagination(leaves(6), 2)
        assertEquals(3, spreads.pageCount)
        assertEquals(listOf(0, 200, 400), spreads.pages.map { it.startOffset })
        assertEquals(listOf(200, 400, 600), spreads.pages.map { it.endOffset })
        assertEquals(listOf(0, 1, 2), spreads.pages.map { it.pageIndex })
        assertEquals(listOf(0, 4, 8), spreads.pages.map { it.startLine })
        assertEquals(listOf(3, 7, 11), spreads.pages.map { it.endLine })
    }

    @Test fun oddFinalPageAddsNeitherBlankOffsetsNorProgress() {
        val spreads = readerViewportPagination(leaves(5), 2)
        assertEquals(3, spreads.pageCount)
        assertEquals(PageSlice(2, 400, 500, 8, 9), spreads.pages.last())
        assertEquals(500, spreads.totalLength)
        assertEquals(2, spreads.pageIndexForOffset(500))
    }

    @Test fun eachLeafCharacterResolvesToItsDisplayedSpread() {
        val spreads = readerViewportPagination(leaves(5), 2)
        for (offset in 0 until 500) assertEquals(offset / 200, spreads.pageIndexForOffset(offset))
    }

    @Test fun singleToSpreadToSingleRetainsCharacterInRightPane() {
        val leaves = leaves(6)
        val spreads = readerViewportPagination(leaves, 2)
        val single = ReaderVisiblePageAnchor("single", 3, 317)
        val spreadIndex = anchoredReaderPage(single, "spread", spreads, 3)
        assertEquals(1, spreadIndex)
        val spread = readerVisibleAnchor(single, "spread", spreads, spreadIndex)
        assertEquals(317, spread.offset)
        val stable = readerVisibleAnchor(spread, "spread", spreads, spreadIndex)
        assertEquals(317, stable.offset)
        assertEquals(3, anchoredReaderPage(stable, "single", leaves, spreadIndex))
    }

    @Test fun turningWhileReplacementIsPendingUsesLatestVisibleCharacters() {
        val leaves = leaves(8)
        val original = ReaderVisiblePageAnchor("single", 1, 120)
        val turned = readerVisibleAnchor(original, "single", leaves, 2)
        assertEquals(200, turned.offset)
        val spread = readerViewportPagination(leaves, 2)
        assertEquals(1, anchoredReaderPage(turned, "spread", spread, 2))
        val inSpread = readerVisibleAnchor(turned, "spread", spread, 1)
        val turnedSpread = readerVisibleAnchor(inSpread, "spread", spread, 2)
        assertEquals(400, turnedSpread.offset)
        assertEquals(4, anchoredReaderPage(turnedSpread, "single", leaves, 2))
    }

    @Test fun explicitResumeOffsetIsPreservedInsideDisplayedSpread() {
        val spreads = readerViewportPagination(leaves(6), 2)
        val index = anchoredReaderPage(null, "spread", spreads, 0, 357)
        val anchor = readerVisibleAnchor(null, "spread", spreads, index, 357)
        assertEquals(1, index)
        assertEquals(357, anchor.offset)
        assertEquals(3, anchoredReaderPage(anchor, "single", leaves(6), index))
    }

    @Test fun emptyChapterStillHasOneRealViewport() {
        val empty = PaginationSummary(listOf(PageSlice(0, 0, 0, 0, 0)), 0)
        val spreads = readerViewportPagination(empty, 2)
        assertEquals(empty, spreads)
        assertEquals(ReaderVisiblePageAnchor("empty", 0, 0), readerVisibleAnchor(null, "empty", spreads, 0))
    }

    private fun leaves(count: Int) = PaginationSummary((0 until count).map {
        PageSlice(it, it * 100, (it + 1) * 100, it * 2, it * 2 + 1)
    }, count * 100)
}
