package com.aceinandroid.inkflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class Fb2BookTest {
    @Test fun inlineStylesAndImagesKeepSearchOffsets() {
        val file = File.createTempFile("native-fb2-rich", ".fb2")
        try {
            file.writeText("""
                <FictionBook xmlns:l="http://www.w3.org/1999/xlink">
                  <description><title-info><book-title>Styled</book-title></title-info></description>
                  <body><section><title><p>Opening</p></title>
                    <p>Before <strong>bold</strong> and <emphasis>italic</emphasis>.</p>
                    <p><strikethrough>Old</strikethrough> words.</p>
                    <image l:href="#cover" alt="Cover"/>
                    <p>Target after picture.</p>
                  </section></body>
                  <binary id="cover" content-type="image/png">AAAA</binary>
                </FictionBook>
            """.trimIndent())
            val book = BookParser.inspect(file)
            val content = Fb2Book.readChapterText(file, book.chapters.single())
            assertTrue(content.styles.any { it.style == EpubTextStyle.BOLD })
            assertTrue(content.styles.any { it.style == EpubTextStyle.ITALIC })
            assertTrue(content.styles.any { it.style == EpubTextStyle.STRIKE })
            assertEquals("#cover", content.images.single().href)
            assertEquals(content.images.single().offset, content.text.indexOf('\uFFFC'))
            assertTrue(content.text.indexOf("Target") > content.images.single().offset)
            assertEquals(content.text.indexOf("Target"),
                BookSearcher.search(file, book, "Target").single().offset)
            assertEquals(1, content.renderBlocks().count { it.image != null })
        } finally {
            file.delete()
        }
    }

    @Test fun nestedSectionsProduceDistinctChaptersAndSearchHits() {
        val file = File.createTempFile("native-fb2", ".fb2")
        try {
            file.writeText("""
                <FictionBook><description><title-info><book-title>Story</book-title></title-info></description>
                  <body><section><title><p>Part One</p></title><p>Opening Target.</p>
                    <section><title><p>Inside</p></title><p>Nested target.</p></section>
                  </section><section><title><p>End</p></title><p>Final page.</p></section></body>
                  <body name="notes"><section><p>Footnote target.</p></section></body>
                </FictionBook>
            """.trimIndent())
            val book = BookParser.inspect(file)
            assertEquals(listOf("Part One", "Inside", "End"), book.chapters.map { it.title })
            assertEquals(listOf(0, 1, 0), book.toc.map { it.depth })
            assertEquals("Opening Target.", BookParser.readChapter(file, book.chapters[0]))
            assertEquals("Nested target.", BookParser.readChapter(file, book.chapters[1]))
            assertFalse(BookParser.readChapter(file, book.chapters[0]).contains("Nested"))
            assertEquals(listOf(0, 1), BookSearcher.search(file, book, "target").map { it.chapterIndex })
        } finally {
            file.delete()
        }
    }

    @Test fun readsDeclaredLegacyEncoding() {
        val file = File.createTempFile("native-fb2-legacy", ".fb2")
        try {
            file.writeBytes("""
                <?xml version="1.0" encoding="windows-1251"?>
                <FictionBook><description><title-info><book-title>Книга</book-title></title-info></description>
                <body><section><title><p>Глава</p></title><p>Текст книги.</p></section></body></FictionBook>
            """.trimIndent().toByteArray(Charset.forName("windows-1251")))
            val book = BookParser.inspect(file)
            assertEquals("Книга", book.title)
            assertEquals("Глава", book.chapters.single().title)
            assertEquals("Текст книги.", BookParser.readChapter(file, book.chapters.single()))
        } finally {
            file.delete()
        }
    }
}
