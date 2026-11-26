package com.aceinandroid.inkflow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.TimeZone

class BookshelfSortTest {
    private val books = listOf(
        StoredBook(1, "Beta", "Zed", File("one.epub"), "epub", 0, "", null,
            "2024-01-01T00:00:00.000", "2025-01-01T00:00:00.000", 0.5),
        StoredBook(2, "Alpha", "Amy", File("two.epub"), "epub", 0, "", null,
            "2024-02-01T00:00:00.000", "2024-02-01T00:00:00.000", 0.9),
        StoredBook(3, "Gamma", "Bob", File("three.epub"), "epub", 0, "", null,
            "2024-03-01T00:00:00.000", "2024-03-01T00:00:00.000", 0.1),
    )

    @Test fun fieldsAndDirectionsSortTheBookshelf() {
        assertEquals(listOf(2L, 1L, 3L), ids(BookshelfSort(BookSortField.TITLE, false)))
        assertEquals(listOf(1L, 3L, 2L), ids(BookshelfSort(BookSortField.AUTHOR, true)))
        assertEquals(listOf(1L, 3L, 2L), ids(BookshelfSort()))
        assertEquals(listOf(3L, 1L, 2L), ids(BookshelfSort(BookSortField.PROGRESS, false)))
        assertEquals(listOf(3L, 2L, 1L), ids(BookshelfSort(BookSortField.IMPORTED, true)))
    }

    @Test fun mixedFlutterLocalAndNativeUtcTimesSortByInstant() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val local = books[0].copy(createdAt = "2025-01-01T08:00:00.123456",
                updatedAt = "2025-01-01T08:00:00.123456")
            val utc = books[1].copy(createdAt = "2025-01-01T01:00:00.123Z",
                updatedAt = "2025-01-01T01:00:00.123Z")
            val offset = books[2].copy(createdAt = "2025-01-01T02:30:00.123+01:00",
                updatedAt = "2025-01-01T02:30:00.123+01:00")
            assertEquals(listOf(3L, 2L, 1L),
                BookshelfSort().apply(listOf(local, utc, offset)).map { it.id })
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun ids(sort: BookshelfSort): List<Long> = sort.apply(books).map { it.id }
}
