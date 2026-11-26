package com.aceinandroid.inkflow

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ui.reader.buildUnifiedChapterAnnotatedString
import com.aceinandroid.inkflow.ui.reader.readPdfOutline
import com.aceinandroid.inkflow.ui.reader.readerPageItems
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderMigrationTest {
    @Test fun appearanceControlsAndExplicitSwipePreferenceRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReaderAppearanceStore(context)
        val controls = ReaderControlsStore(context)
        val original = store.load()
        val originalSwipe = controls.swipeToTurnPage()
        try {
            val desired = original.copy(pageMargin = 36, paragraphSpacing = 20, firstLineIndent = 36,
                alignment = ReaderAlignment.JUSTIFY, showHeader = false, showFooter = false,
                showTime = true, showBattery = true)
            store.save(desired)
            controls.setSwipeToTurnPage(true)
            assertEquals(desired, ReaderAppearanceStore(context).load())
            assertTrue(ReaderControlsStore(context).swipeToTurnPage())
        } finally {
            store.save(original)
            controls.setSwipeToTurnPage(originalSwipe)
        }
    }

    @Test fun appearanceLayoutPreservesSourceOffsetsAndAddsParagraphSpace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val epub = EpubText.fromHtml("<html><body><p>First paragraph.</p><img src='a.png'/><p>Second paragraph.</p></body></html>")
        val appearance = ReaderAppearance()
        val annotated = buildUnifiedChapterAnnotatedString(epub.text, epub, emptyList(), appearance)
        assertEquals(epub.text, annotated.text)
        assertEquals('\uFFFC', annotated.text[epub.images.single().offset])
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
        val source = "First paragraph.\nSecond paragraph."
        val annotatedSource = buildUnifiedChapterAnnotatedString(source, null, emptyList(), appearance)
        val layout = measurer.measure(annotatedSource, TextStyle(fontSize = 18.sp), constraints = Constraints(maxWidth = 400))
        val tight = readerPageItems(layout, emptyList(), 0f)
        val spaced = readerPageItems(layout, emptyList(), 28f)
        assertEquals("Paragraph gap must affect layout without altering text offsets", 28f,
            spaced.last().bottom - tight.last().bottom, 0.01f)
        assertEquals(tight.map { it.startOffset to it.endOffset }, spaced.map { it.startOffset to it.endOffset })
    }

    @Test fun nestedPublisherAlignmentAndImageParagraphsPreserveOffsets() {
        val epub = nestedAlignmentAndImageFixture()
        assertMergedImageParagraphStyles(epub, ReaderAppearance(firstLineIndent = 28),
            listOf(TextAlign.Center, TextAlign.Right, TextAlign.Justify, TextAlign.Justify))
    }

    @Test fun userAlignmentOverridesNestedPublisherStylesButKeepsImageUnindented() {
        val epub = nestedAlignmentAndImageFixture()
        assertMergedImageParagraphStyles(epub,
            ReaderAppearance(firstLineIndent = 28, alignment = ReaderAlignment.JUSTIFY),
            List(4) { TextAlign.Justify })
    }

    @Test fun identicalPublisherRangesKeepChildAlignmentAndAllowUserOverride() {
        val epub = EpubText.fromHtml("<html><body style='text-align: justify'>" +
            "<p style='text-align: center'>Only.</p></body></html>")
        assertEquals("Only.", epub.text)
        // Trimming the final block newline gives parent and child identical bounds.
        assertEquals(listOf(
            EpubAlignmentRange(0, 5, EpubTextAlignment.CENTER),
            EpubAlignmentRange(0, 5, EpubTextAlignment.JUSTIFY),
        ), epub.alignments)
        listOf(ReaderAlignment.BOOK to TextAlign.Center, ReaderAlignment.JUSTIFY to TextAlign.Justify)
            .forEach { (alignment, expected) ->
                val appearance = ReaderAppearance(firstLineIndent = 28, alignment = alignment)
                val annotated = buildUnifiedChapterAnnotatedString(epub.text, epub, emptyList(), appearance)
                assertEquals("Source offsets must survive identical-range normalization", epub.text, annotated.text)
                val paragraph = annotated.paragraphStyles.single()
                assertEquals(0, paragraph.start)
                assertEquals(epub.text.length, paragraph.end)
                assertEquals("Incorrect alignment for $alignment with identical publisher ranges",
                    expected, paragraph.item.textAlign)
                assertEquals(TextIndent(firstLine = 28.sp), paragraph.item.textIndent)
            }
    }

    private fun nestedAlignmentAndImageFixture(): EpubText {
        // Keep tags adjacent: indentation whitespace would change the source offsets.
        val epub = EpubText.fromHtml("<html><body style='text-align: justify'>" +
            "<div style='text-align: right'><p style='text-align: center'>Before.</p>" +
            "<img src='fixture.png' alt='Diagram'/></div>" +
            "<p>After.</p><p>Tail.</p></body></html>")
        assertEquals("Before.\n\n\uFFFC\n\nAfter.\n\nTail.", epub.text)
        assertEquals(9, epub.images.single().offset)
        val alignment = epub.alignments.single { it.alignment == EpubTextAlignment.RIGHT }
        val imageStart = epub.text.lastIndexOf('\n', epub.images.single().offset) + 1
        val imageEnd = epub.text.indexOf('\n', epub.images.single().offset) + 1
        // The old builder adds [0,10) alignment and [9,11) image indent. The
        // trailing newline crosses the alignment endpoint and crashes Compose.
        assertEquals(0 to 10, alignment.start to alignment.end)
        assertEquals(9 to 11, imageStart to imageEnd)
        assertTrue(alignment.start < imageStart && imageStart < alignment.end &&
            alignment.end < imageEnd)
        return epub
    }

    private fun assertMergedImageParagraphStyles(
        epub: EpubText,
        appearance: ReaderAppearance,
        expectedAlignments: List<TextAlign>,
    ) {
        val annotated = buildUnifiedChapterAnnotatedString(epub.text, epub, emptyList(), appearance)
        assertEquals("Styling must preserve every source offset", epub.text, annotated.text)
        assertEquals('\uFFFC', annotated.text[epub.images.single().offset])
        val ranges = annotated.paragraphStyles.sortedBy { it.start }
        assertTrue("Paragraph styles must cover the chapter", ranges.isNotEmpty())
        assertEquals(0, ranges.first().start)
        assertEquals(epub.text.length, ranges.last().end)
        ranges.forEach { range ->
            assertTrue("Invalid paragraph interval: $range",
                range.start >= 0 && range.start < range.end && range.end <= epub.text.length)
        }
        ranges.zipWithNext().forEach { (previous, next) ->
            assertEquals("Paragraph intervals must be disjoint and have no gaps",
                previous.end, next.start)
        }
        val firstLineIndent = TextIndent(firstLine = appearance.firstLineIndent.sp)
        annotated.text.indices.forEach { offset ->
            val style = ranges.single { offset >= it.start && offset < it.end }.item
            assertEquals("Image indent must not leak into adjacent paragraphs at $offset",
                if (offset in 9 until 11) TextIndent.None else firstLineIndent, style.textIndent)
        }
        listOf(0 until 7, 9 until 10, 12 until 18, 20 until 25)
            .forEachIndexed { index, offsets ->
                offsets.forEach { offset ->
                    val style = ranges.single { offset >= it.start && offset < it.end }.item
                    assertEquals("Incorrect effective alignment at source offset $offset",
                        expectedAlignments[index], style.textAlign)
                }
            }
    }

    @Test fun metadataAndCoverEditsKeepReadingPositionAndRejectBadCover() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString()
        val text = File(context.cacheDir, "$unique.txt").apply { writeText("Metadata fixture $unique") }
        val cover = File(context.cacheDir, "$unique.png")
        val badCover = File(context.cacheDir, "$unique-bad.png").apply { writeText("invalid image") }
        Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            cover.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val store = BookStore(context)
        var imported: StoredBook? = null
        try {
            val book = store.import(Uri.fromFile(text)).also { imported = it }
            store.savePosition(book.id, 2, "inkflow-text:2:5", 0.4)
            store.saveBookMetadata(book.id, " Updated title ", " Author ", Uri.fromFile(cover))
            val saved = store.all().first { it.id == book.id }
            assertEquals("Updated title", saved.title)
            assertEquals("Author", saved.author)
            assertEquals("inkflow-text:2:5", saved.cfi)
            assertEquals(0.4, saved.progress, 0.001)
            assertNotNull(saved.cover)
            assertTrue(saved.cover!!.isFile)
            assertEquals("cover", saved.cover!!.parentFile!!.name)
            assertThrows(Exception::class.java) {
                store.saveBookMetadata(book.id, "Wrong", "Wrong", Uri.fromFile(badCover))
            }
            assertEquals("Updated title", store.all().first { it.id == book.id }.title)
            assertTrue(saved.cover!!.isFile)
            assertThrows(IllegalArgumentException::class.java) { store.saveBookMetadata(book.id, " ", "") }
        } finally {
            imported?.let { store.removeBook(it.id) }
            imported?.let { store.writableDatabase.delete("tb_books", "id = ?", arrayOf(it.id.toString())) }
            store.close()
            text.delete(); cover.delete(); badCover.delete()
        }
    }

    @Test fun pdfOutlineKeepsHierarchyAndOnlyLocalPageDestinations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "outline-${UUID.randomUUID()}.pdf")
        try {
            PDDocument().use { document ->
                val first = PDPage()
                val second = PDPage()
                document.addPage(first); document.addPage(second)
                val root = PDDocumentOutline()
                document.documentCatalog.documentOutline = root
                val chapter = PDOutlineItem().apply { title = "Chapter"; setDestination(first) }
                chapter.addLast(PDOutlineItem().apply { title = "Section"; setDestination(second) })
                root.addLast(chapter)
                root.addLast(PDOutlineItem().apply {
                    title = "External"
                    action = PDActionURI().apply { uri = "https://example.invalid" }
                })
                document.save(file)
            }
            val entries = readPdfOutline(file)
            assertEquals(listOf("Chapter", "Section"), entries.map { it.title })
            assertEquals(listOf(0, 1), entries.map { it.chapterIndex })
            assertEquals(listOf(0, 1), entries.map { it.depth })
        } finally { file.delete() }
    }
}
