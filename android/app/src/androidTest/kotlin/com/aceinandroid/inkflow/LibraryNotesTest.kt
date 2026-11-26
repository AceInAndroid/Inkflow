package com.aceinandroid.inkflow

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryNotesTest {
    @Test fun listsReadingNotesForActiveBooksOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BookStore(context)
        val bookId = store.writableDatabase.insertOrThrow("tb_books", null, ContentValues().apply {
            put("title", "Notes fixture")
            put("file_path", "file/${UUID.randomUUID()}.epub")
            put("is_deleted", 0)
        })
        val noteIds = mutableListOf<Long>()
        try {
            for (type in listOf("highlight", "bookmark", "review")) {
                noteIds += store.writableDatabase.insertOrThrow("tb_notes", null, ContentValues().apply {
                    put("book_id", bookId)
                    put("type", type)
                    put("content", "Unique ${type} ${UUID.randomUUID()}")
                    put("cfi", "epubcfi(/6/2!/4/2,/1:0,/1:4)")
                    put("update_time", "2026-11-17T00:00:00")
                })
            }
            val notes = store.libraryNotes().filter { it.book.id == bookId }
            assertEquals(setOf("highlight", "bookmark"), notes.map { it.type }.toSet())
            assertTrue(notes.all { it.book.title == "Notes fixture" })
            assertTrue(notes.all { it.sourceTypes == setOf("annotation") })
            store.removeBook(bookId)
            assertTrue(store.libraryNotes().none { it.book.id == bookId })
        } finally {
            noteIds.forEach { id ->
                store.writableDatabase.delete("tb_notes", "id = ?", arrayOf(id.toString()))
            }
            store.writableDatabase.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            store.close()
        }
    }
}
