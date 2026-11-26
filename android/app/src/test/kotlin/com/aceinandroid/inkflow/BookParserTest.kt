package com.aceinandroid.inkflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookParserTest {
    private fun epub(entries: Map<String, String>): File = File.createTempFile("toc-test", ".epub").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test fun epubMetadataAndChapter() {
        val epub = File.createTempFile("reader-test", ".epub")
        try {
            ZipOutputStream(epub.outputStream()).use { zip ->
                fun entry(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                entry("META-INF/container.xml", """
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" /></rootfiles>
                    </container>
                """.trimIndent())
                entry("OEBPS/content.opf", """
                    <package xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Sample Book</dc:title><dc:creator>Author</dc:creator>
                      </metadata>
                      <manifest><item id="chapter" href="chapters/chapter%201.xhtml" media-type="application/xhtml+xml" /></manifest>
                      <spine><itemref idref="chapter" /></spine>
                    </package>
                """.trimIndent())
                entry("OEBPS/chapters/chapter 1.xhtml", "<html><body><h1>First</h1><p>Hello <b>reader</b>.</p></body></html>")
            }
            val document = BookParser.inspect(epub)
            assertEquals("Sample Book", document.title)
            assertEquals("Author", document.author)
            assertEquals("First", document.chapters.single().title)
            assertEquals("epubcfi(/6/2)", document.chapters.single().baseCfi)
            assertTrue(BookParser.readChapter(epub, document.chapters.single()).contains("Hello reader."))
        } finally {
            epub.delete()
        }
    }

    @Test fun fb2MetadataAndText() {
        val fb2 = File.createTempFile("reader-test", ".fb2")
        try {
            fb2.writeText("""
                <FictionBook><description><title-info><book-title>Story</book-title>
                <author><first-name>Ada</first-name><last-name>Lovelace</last-name></author>
                </title-info></description><body><section><p>Opening line.</p></section></body></FictionBook>
            """.trimIndent())
            val document = BookParser.inspect(fb2)
            assertEquals("Story", document.title)
            assertEquals("Ada Lovelace", document.author)
            assertTrue(BookParser.readChapter(fb2, document.chapters.single()).contains("Opening line."))
        } finally {
            fb2.delete()
        }
    }

    @Test fun markdownHeadingsStylesAndSearchShareRenderedOffsets() {
        val file = File.createTempFile("native-markdown", ".md")
        try {
            file.writeText("""
                # First chapter

                Before **bold**, *italic*, ~~old~~, and `code`.

                - list item

                | Left | Right |
                | --- | --- |
                | A | B |

                ![cover](missing.jpg)

                ```md
                # Not a chapter
                ```

                ## Second chapter

                The Target phrase is here.
            """.trimIndent())
            val document = BookParser.inspect(file)
            assertEquals("First chapter", document.title)
            assertEquals(listOf("First chapter", "Second chapter"), document.chapters.map { it.title })
            assertTrue(document.chapters.all { it.hasHeading })
            assertEquals(listOf(0, 1), document.toc.map { it.depth })
            val first = MarkdownBook.readChapterText(file, document.chapters.first())
            assertTrue(first.text.contains("- list item"))
            assertTrue(first.text.contains("Left | Right"))
            assertTrue(first.text.contains("[cover]"))
            assertTrue(first.text.contains("# Not a chapter"))
            assertTrue(first.images.isEmpty())
            assertTrue(first.styles.map { it.style }.containsAll(listOf(
                EpubTextStyle.BOLD, EpubTextStyle.ITALIC, EpubTextStyle.STRIKE,
                EpubTextStyle.CODE, EpubTextStyle.HEADING)))
            val hit = BookSearcher.search(file, document, "TARGET").single()
            assertEquals(1, hit.chapterIndex)
            assertEquals(MarkdownBook.readChapterText(file, document.chapters[1]).text.indexOf("Target"), hit.offset)
        } finally {
            file.delete()
        }
    }

    @Test fun markdownIntroductionBecomesItsOwnChapter() {
        val file = File.createTempFile("native-markdown-intro", ".markdown")
        try {
            file.writeText("Preface text.\n\n# Main title\n\nMain body.")
            val document = BookParser.inspect(file, "Fallback")
            assertEquals("Main title", document.title)
            assertEquals(listOf("Fallback", "Main title"), document.chapters.map { it.title })
            assertEquals(listOf(false, true), document.chapters.map { it.hasHeading })
            assertEquals(listOf(0, 1), document.toc.map { it.chapterIndex })
            assertEquals("Preface text.", BookParser.readChapter(file, document.chapters.first()))
            assertTrue(BookParser.readChapter(file, document.chapters.last()).contains("Main body."))
        } finally {
            file.delete()
        }
    }

    @Test fun richEpubFixture() {
        val epub = File.createTempFile("native-rich", ".epub")
        try {
            javaClass.getResourceAsStream("/native-rich.epub")!!.use { input ->
                epub.outputStream().use(input::copyTo)
            }
            val document = BookParser.inspect(epub)
            assertEquals("Native EPUB Fixture", document.title)
            val content = BookParser.readEpubText(epub, document.chapters.single())
            assertTrue(content.text.contains("Text after image"))
            assertEquals(1, content.images.size)
            assertTrue(content.styles.any { it.style == EpubTextStyle.BOLD })
            val blocks = content.renderBlocks()
            assertEquals(0, blocks.first().start)
            assertEquals(content.text.length, blocks.last().end)
            assertTrue(blocks.zipWithNext().all { (left, right) -> left.end == right.start })
            assertEquals(content.images.single().offset, blocks.single { it.image != null }.start)
            assertTrue(blocks.filter { it.image == null }.all { block ->
                block.visibleStart >= block.start && block.visibleEnd <= block.end &&
                    (block.visibleStart == block.visibleEnd ||
                        (!content.text[block.visibleStart].isWhitespace() &&
                            !content.text[block.visibleEnd - 1].isWhitespace()))
            })
        } finally {
            epub.delete()
        }
    }

    @Test fun epub3NavigationAndAnchors() {
        val file = epub(mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>",
            "OEBPS/content.opf" to """
                <package><metadata><title>Navigation</title></metadata><manifest>
                <item id="one" href="chapters/one.xhtml" media-type="application/xhtml+xml"/>
                <item id="two" href="chapters/two.xhtml" media-type="application/xhtml+xml"/>
                <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>
            """.trimIndent(),
            "OEBPS/nav.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
                <li><a href="chapters/one.xhtml#start">First</a><ol>
                <li><a href="chapters/two.xhtml#middle">Second section</a></li>
                </ol></li></ol></nav></body></html>
            """.trimIndent(),
            "OEBPS/chapters/one.xhtml" to "<html><body><p id=\"start\">First chapter</p></body></html>",
            "OEBPS/chapters/two.xhtml" to "<html><body><p>Before</p><p id=\"middle\">Target text</p></body></html>",
        ))
        try {
            val document = BookParser.inspect(file)
            assertEquals(listOf("First", "Second section"), document.toc.map { it.title })
            assertEquals(listOf(0, 1), document.toc.map { it.chapterIndex })
            assertEquals(listOf(0, 1), document.toc.map { it.depth })
            assertEquals("middle", document.toc[1].fragment)
            val chapter = BookParser.readEpubText(file, document.chapters[1])
            assertEquals(chapter.text.indexOf("Target text"), chapter.anchorOffset("middle"))
            assertEquals(chapter.anchorOffset("middle"),
                chapter.offsetForCfi(chapter.cfiAt(document.chapters[1].baseCfi!!, chapter.anchorOffset("middle")!!)!!))
        } finally {
            file.delete()
        }
    }

    @Test fun epubLoadsRelativeLinkedStylesheetForAlignment() {
        val file = epub(mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>",
            "OEBPS/content.opf" to """
                <package><metadata><title>Styled</title></metadata><manifest>
                <item id="one" href="chapters/one.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="one"/></spine></package>
            """.trimIndent(),
            "OEBPS/chapters/one.xhtml" to """
                <html><head><link rel="stylesheet" href="../styles/book.css"/></head>
                <body><p class="centered">Target paragraph.</p></body></html>
            """.trimIndent(),
            "OEBPS/styles/book.css" to ".centered { text-align: center; }",
        ))
        try {
            val chapter = BookParser.readEpubText(file, BookParser.inspect(file).chapters.single())
            assertEquals(EpubTextAlignment.CENTER, chapter.renderBlocks().single().alignment)
            assertEquals("Target paragraph.", chapter.text)
        } finally {
            file.delete()
        }
    }

    @Test fun epub2NcxNavigation() {
        val file = epub(mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>",
            "OEBPS/content.opf" to """
                <package><metadata><title>NCX</title></metadata><manifest>
                <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                </manifest><spine toc="ncx"><itemref idref="one"/></spine></package>
            """.trimIndent(),
            "OEBPS/toc.ncx" to """
                <ncx><navMap><navPoint><navLabel><text>Opening</text></navLabel>
                <content src="one.xhtml#opening"/></navPoint></navMap></ncx>
            """.trimIndent(),
            "OEBPS/one.xhtml" to "<html><body><h1 id=\"opening\">Opening</h1></body></html>",
        ))
        try {
            val document = BookParser.inspect(file)
            assertEquals(listOf(TocEntry("Opening", 0, "opening", 0)), document.toc)
        } finally {
            file.delete()
        }
    }

    @Test fun epubSearchFindsLaterChapterAndResolvesCfi() {
        val file = epub(mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>",
            "OEBPS/content.opf" to """
                <package><metadata><title>Search</title></metadata><manifest>
                <item id="one" href="one.xhtml"/><item id="two" href="two.xhtml"/>
                </manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>
            """.trimIndent(),
            "OEBPS/one.xhtml" to "<html><body><p>Opening chapter.</p></body></html>",
            "OEBPS/two.xhtml" to "<html><body><p>Earlier text.</p><p>Find the Target phrase here.</p></body></html>",
        ))
        try {
            val document = BookParser.inspect(file)
            val hit = BookSearcher.searchEpub(file, document, "TARGET").single()
            assertEquals(hit, BookSearcher.search(file, document, "TARGET").single())
            assertEquals(1, hit.chapterIndex)
            assertTrue(hit.excerpt.contains("Target phrase"))
            val body = BookParser.readEpubText(file, document.chapters[1])
            assertEquals(hit.offset, body.offsetForCfi(hit.cfi))
            assertEquals(1, EpubCfi.chapterIndex(hit.cfi, document.chapters))
            assertEquals(1, BookSearcher.searchEpub(file, document, "target", limit = 1).size)
            assertTrue(BookSearcher.searchEpub(file, document, "missing").isEmpty())
        } finally {
            file.delete()
        }
    }
}
