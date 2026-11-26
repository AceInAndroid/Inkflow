package com.aceinandroid.inkflow

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MobiParserDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun fixture(asset: String, extension: String, test: (File) -> Unit) {
        val file = File.createTempFile("mobi-device-", extension, context.cacheDir)
        try {
            testAssets.open(asset).use { input -> file.outputStream().use(input::copyTo) }
            test(file)
        } finally {
            MobiBook.deleteCache(file)
            file.delete()
        }
    }

    @Test fun kf8ChaptersCanBeReadAndSearched() = fixture("sample-ncx.mobi", ".azw3") { file ->
        val document = BookParser.inspect(file)
        assertEquals("libmobi ncx test", document.title)
        assertEquals(3, document.chapters.size)
        assertEquals(listOf("Test chapter 1", "Test chapter 2", "Test subchapter 2-1"),
            document.toc.map { it.title })
        assertEquals(listOf(0, 1, 2), document.toc.map { it.chapterIndex })
        assertEquals(listOf(0, 0, 0), document.toc.map { it.depth })
        assertTrue(BookParser.readChapter(file, document.chapters.first()).contains("Test page 1"))
        val hit = BookSearcher.search(file, document, "Test page 2").single()
        assertEquals(1, hit.chapterIndex)
        assertTrue(hit.excerpt.contains("Test page 2"))
        assertEquals(document.chapters.size, BookParser.inspect(file).chapters.size)
    }

    @Test fun nestedNcxKeepsAnchorsAndDepth() {
        val chapters = listOf(Chapter("part00000.html", "One"), Chapter("part00001.html", "Two"))
        val toc = MobiBook.parseNcxToc("""
            <ncx><navMap><navPoint><navLabel><text>First</text></navLabel>
            <content src="part00000.html"/>
            <navPoint><navLabel><text>Section</text></navLabel>
            <content src="part00001.html#target"/></navPoint>
            </navPoint></navMap></ncx>
        """.trimIndent(), chapters)
        assertEquals(listOf("First", "Section"), toc.map { it.title })
        assertEquals(listOf(0, 1), toc.map { it.chapterIndex })
        assertEquals(listOf(0, 1), toc.map { it.depth })
        assertEquals("target", toc.last().fragment)
    }

    @Test fun legacyMobiMetadataAndText() = fixture("sample-cp1252.mobi", ".mobi") { file ->
        val document = BookParser.inspect(file)
        assertEquals("Libmobi test sample", document.title)
        assertEquals("Bartek Fabiszewski", document.author)
        assertFalse(BookParser.readChapter(file, document.chapters.first()).isBlank())
    }

    @Test fun multimediaResourcesRenderWithStyledText() = fixture("sample-multimedia.mobi", ".mobi") { file ->
        val document = BookParser.inspect(file)
        val chapters = document.chapters.map { MobiBook.readChapterText(file, it) }
        assertTrue(chapters.any { it.styles.isNotEmpty() })
        val images = chapters.flatMap { it.images }
        assertTrue(images.isNotEmpty())
        val decoded = images.mapNotNull { MobiImageLoader.load(file, it.href) }
        assertTrue(decoded.isNotEmpty())
        assertTrue(decoded.all { it.width > 0 && it.height > 0 })
        assertTrue(chapters.any { it.renderBlocks().any { block -> block.image != null } })
    }

    @Test fun removingBookRemovesExtractedChapters() {
        val store = BookStore(context)
        val directory = File(context.filesDir, "file").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.mobi")
        var bookId: Long? = null
        try {
            testAssets.open("sample-ncx.mobi").use { input -> file.outputStream().use(input::copyTo) }
            BookParser.inspect(file)
            val cache = File(directory, "${file.name}.native")
            assertTrue(cache.isDirectory)
            val insertedId = store.writableDatabase.insertOrThrow("tb_books", null, ContentValues().apply {
                put("title", "MOBI cleanup test")
                put("file_path", "file/${file.name}")
                put("is_deleted", 0)
            })
            bookId = insertedId
            store.removeBook(insertedId)
            assertFalse(file.exists())
            assertFalse(cache.exists())
        } finally {
            bookId?.let { store.writableDatabase.delete("tb_books", "id = ?", arrayOf(it.toString())) }
            MobiBook.deleteCache(file)
            file.delete()
            store.close()
        }
    }
}
