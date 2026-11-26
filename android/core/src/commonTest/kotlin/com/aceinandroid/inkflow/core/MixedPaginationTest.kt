package com.aceinandroid.inkflow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixedPaginationTest {
    @Test fun portraitImagesFitWithoutCroppingOrDistortion() {
        val fitted = TextPaginationCore.fitImage(800f, 2400f, 400f, 600f)
        assertEquals(ImageSize(200f, 600f), fitted)
        val fallback = TextPaginationCore.fitImage(Float.NaN, -1f, 400f, 600f)
        assertEquals(ImageSize(400f, 300f), fallback)
    }

    @Test fun mixedPagesKeepImagesAtomicAndPreserveOffsetsForResume() {
        val items = listOf(
            PageItem.TextSlice(0, 10, 0, 0f, 20f),
            PageItem.ImageBlock("portrait.jpg", 10, 11, 1, 20f, 120f),
            PageItem.TextSlice(11, 25, 2, 120f, 140f),
        )
        val mixed = TextPaginationCore.paginateItems(items, 100f, 25)
        assertEquals(3, mixed.summary.pageCount)
        assertEquals(items, mixed.pages.flatMap { it.items })
        assertEquals(1, mixed.summary.pageIndexForOffset(10))
        assertEquals(2, mixed.summary.pageIndexForOffset(11))
        assertEquals(2, mixed.summary.pageIndexForOffset(25))
        assertTrue(mixed.pages.all { it.items.size == 1 })
    }

    @Test fun shortIllustrationSharesPageWithTextAndEmptyChapterStillHasAPage() {
        val items = listOf(PageItem.TextSlice(0, 4, 0, 0f, 20f),
            PageItem.ImageBlock("small", 4, 5, 1, 20f, 50f),
            PageItem.TextSlice(5, 10, 2, 50f, 70f))
        val page = TextPaginationCore.paginateItems(items, 70f, 10)
        assertEquals(1, page.summary.pageCount)
        assertEquals(10, page.summary.pages.single().endOffset)
        assertEquals(1, TextPaginationCore.paginateItems(emptyList(), 100f, 0).summary.pageCount)
    }
}
