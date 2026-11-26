package com.aceinandroid.inkflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotesExportTest {
    @Test fun exportsOnlySuppliedNotesWithBookChapterAndLocation() {
        val book = StoredBook(1, "A #title", "Author", File("book.epub"), "epub", 0, "")
        val selected = LibraryNote(1, book, "First line\n*Second*", "epubcfi(/6/2!/4/2/1:0)",
            "Chapter 1", "highlight", "My note")
        val ignored = LibraryNote(2, book, "Other", "epubcfi(/6/4)",
            "Chapter 2", "bookmark", "")
        val output = notesAsMarkdown(listOf(selected))
        assertTrue(output.contains("## A \\#title"))
        assertTrue(output.contains("### Highlight - Chapter 1"))
        assertTrue(output.contains("> First line\n> \\*Second\\*"))
        assertTrue(output.contains("**Note**\n> My note"))
        assertTrue(output.contains("Location: `epubcfi(/6/2!/4/2/1:0)`"))
        assertFalse(output.contains(ignored.content))
    }
}
