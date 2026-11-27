package com.aceinandroid.inkflow.ui.home

import com.aceinandroid.inkflow.ReadingRecord
import com.aceinandroid.inkflow.ReadingSummary
import com.aceinandroid.inkflow.StoredBook
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HomeSelectionTest {
    private val empty = ReadingSummary(0, 0, 0, emptyList(), emptyList())
    private fun book(id: Long, updated: String, progress: Double = 0.0, position: Int = 0, cfi: String = "") =
        StoredBook(id, "Book $id", "", File("book-$id.txt"), "txt", position, cfi,
            updatedAt = updated, progress = progress)

    @Test fun unreadImportsDoNotDisplaceContinueReading() {
        val read = book(1, "2026-11-26", progress = .25)
        val unread = book(2, "2026-11-27")
        assertEquals(listOf(read), recentHomeBooks(listOf(unread, read), empty))
    }

    @Test fun persistedPositionsAndCfiAreReadingEvidence() {
        val older = book(1, "2026-11-25", position = 2)
        val newer = book(2, "2026-11-27", cfi = "epubcfi(/6/2!/4/2:12)")
        assertEquals(listOf(newer, older), recentHomeBooks(listOf(older, newer), empty))
    }

    @Test fun firstPageReadingHistoryCountsAndDeletedBooksStayAbsent() {
        val firstPage = book(3, "")
        val summary = empty.copy(history = listOf(ReadingRecord(3, "2026-11-27", 60), ReadingRecord(99, "2026-11-27", 30)))
        assertEquals(listOf(firstPage), recentHomeBooks(listOf(firstPage), summary))
    }

    @Test fun emptyLibraryHasNoContinueCandidate() {
        assertTrue(recentHomeBooks(emptyList(), empty).isEmpty())
    }
}
