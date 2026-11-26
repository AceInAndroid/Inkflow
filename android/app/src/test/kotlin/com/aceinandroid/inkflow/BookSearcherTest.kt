package com.aceinandroid.inkflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSearcherTest {
    @Test fun searchesPlainTextAndMarkdownWithReadableExcerpts() {
        for (extension in listOf("txt", "md", "markdown")) {
            val file = File.createTempFile("native-search", ".$extension")
            try {
                file.writeText("Opening line.\n\nThe Target phrase appears here. Another target follows.")
                val hits = BookSearcher.search(file, BookParser.inspect(file), "TARGET")
                assertEquals(2, hits.size)
                assertEquals(file.readText().indexOf("Target"), hits.first().offset)
                assertTrue(hits.first().excerpt.contains("Target phrase"))
                assertFalse(hits.first().excerpt.contains('\n'))
                assertTrue(hits.all { it.cfi.isEmpty() && !it.isPdfPage && it.chapterIndex == 0 })
                assertEquals(1, BookSearcher.search(file, BookParser.inspect(file), "target", limit = 1).size)
                assertTrue(BookSearcher.search(file, BookParser.inspect(file), "missing").isEmpty())
            } finally {
                file.delete()
            }
        }
    }

    @Test fun searchesExtractedFb2Text() {
        val file = File.createTempFile("native-search", ".fb2")
        try {
            file.writeText("<FictionBook><body><section><p>First line.</p><p>Find the Target here.</p></section></body></FictionBook>")
            val document = BookParser.inspect(file)
            val hit = BookSearcher.search(file, document, "target").single()
            assertEquals(BookParser.readChapter(file, document.chapters.single()).indexOf("Target"), hit.offset)
            assertEquals(document.title, hit.chapterTitle)
        } finally {
            file.delete()
        }
    }
}
