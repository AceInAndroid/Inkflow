package com.aceinandroid.inkflow.ui.bookshelf

import com.aceinandroid.inkflow.StoredBook
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class BookshelfPresentationTest {
    private fun book(id: Long) = StoredBook(id, "Book $id", "Author", File("$id.epub"), "epub", 0, "")

    @Test fun phoneUsesThreeColumnsButNarrowOrLargeTextUsesTwo() {
        // A 360dp phone leaves 328dp after the screen's 16dp side padding.
        assertEquals(3, bookshelfColumnCount(328f, 1f))
        assertEquals(3, bookshelfColumnCount(400f, 1.2f))
        assertEquals(2, bookshelfColumnCount(288f, 1f))
        assertEquals(2, bookshelfColumnCount(328f, 1.3f))
        assertEquals(2, bookshelfColumnCount(400f, 2f))
    }

    @Test fun wideLayoutsExpandWithoutSqueezingLargeText() {
        assertEquals(4, bookshelfColumnCount(768f, 1f))
        assertEquals(6, bookshelfColumnCount(1000f, 1f))
        assertEquals(2, bookshelfColumnCount(768f, 2f))
        assertEquals(1.44f, 1f / BOOKSHELF_COVER_RATIO, 0.0001f)
    }

    @Test fun selectionTogglesIndependentlyOfSortOrder() {
        val first = toggleBookshelfSelection(emptySet(), 3)
        val both = toggleBookshelfSelection(first, 1)
        assertEquals(setOf(1L, 3L), both)
        assertEquals(setOf(1L), toggleBookshelfSelection(both, 3))
        assertEquals(setOf(3L), first)
    }

    @Test fun batchContinuesAfterFailureAndKeepsOnlyFailedBooksForRetry() {
        val books = listOf(book(3), book(1), book(2))
        val attempted = mutableListOf<Long>()
        val result = applyBookshelfBatch(books) {
            attempted += it.id
            if (it.id == 1L) throw IOException("Cannot remove local file")
        }
        assertEquals(listOf(3L, 1L, 2L), attempted)
        assertEquals(setOf(3L, 2L), result.succeeded)
        assertEquals(listOf(1L), result.failed.map { it.book.id })
        assertEquals("Cannot remove local file", result.failed.single().message)
        assertEquals(setOf(1L), books.map { it.id }.toSet() - result.succeeded)
    }

    @Test fun batchDoesNotRepeatStoreMutationForDuplicateIds() {
        val attempted = mutableListOf<Long>()
        val result = applyBookshelfBatch(listOf(book(1), book(1), book(2))) { attempted += it.id }
        assertEquals(listOf(1L, 2L), attempted)
        assertEquals(setOf(1L, 2L), result.succeeded)
        assertTrue(result.failed.isEmpty())
    }

    @Test fun cancellationStopsTheBatchInsteadOfBecomingAnItemFailure() {
        val attempted = mutableListOf<Long>()
        assertThrows(CancellationException::class.java) {
            applyBookshelfBatch(listOf(book(1), book(2))) {
                attempted += it.id
                throw CancellationException("Screen closed")
            }
        }
        assertEquals(listOf(1L), attempted)
    }
}
