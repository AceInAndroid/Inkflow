package com.aceinandroid.inkflow

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
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
