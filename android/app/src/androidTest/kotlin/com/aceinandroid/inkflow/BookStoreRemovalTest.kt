package com.aceinandroid.inkflow

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BookStoreRemovalTest {
    @Test fun epubCoverIsImportedDisplayedAndRemovedWithBook() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "${UUID.randomUUID()}.epub")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val image = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
        bitmap.recycle()
        ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("META-INF/container.xml", """
                <container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>
            """.trimIndent().toByteArray())
            entry("OPS/book.opf", """
                <package><metadata><title>Cover fixture</title></metadata><manifest>
                <item id="page" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover" href="images/front.png" media-type="image/png" properties="cover-image"/>
                </manifest><spine><itemref idref="page"/></spine></package>
            """.trimIndent().toByteArray())
            entry("OPS/chapter.xhtml", "<html><body><h1>Chapter</h1></body></html>".toByteArray())
            entry("OPS/images/front.png", image)
        }
        val store = BookStore(context)
        var imported: StoredBook? = null
        try {
            imported = store.import(Uri.fromFile(source))
            val book = store.all().first { it.id == imported.id }
            val cover = requireNotNull(book.cover)
            assertTrue(cover.isFile)
            assertTrue(BitmapFactory.decodeFile(cover.path).getPixel(0, 0) == Color.RED)
            store.removeBook(book.id)
            assertFalse(cover.exists())
            assertFalse(book.file.exists())
        } finally {
            imported?.let { store.removeBook(it.id) }
            imported?.let { store.writableDatabase.delete("tb_books", "id = ?", arrayOf(it.id.toString())) }
            store.close()
            source.delete()
        }
    }

    @Test fun removalHidesBookAndDeletesOnlyItsImportedFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BookStore(context)
        val directory = File(context.filesDir, "file").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.txt").apply { writeText("Temporary book") }
        val bookId = store.writableDatabase.insertOrThrow("tb_books", null, ContentValues().apply {
            put("title", "Temporary removal test")
            put("file_path", "file/${file.name}")
            put("is_deleted", 0)
        })
        try {
            assertTrue(store.all().any { it.id == bookId })
            store.removeBook(bookId)
            assertFalse(store.all().any { it.id == bookId })
            assertFalse(file.exists())
            store.readableDatabase.query("tb_books", arrayOf("is_deleted"), "id = ?",
                arrayOf(bookId.toString()), null, null, null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getInt(0) == 1)
            }
        } finally {
            store.writableDatabase.delete("tb_books", "id = ?", arrayOf(bookId.toString()))
            file.delete()
            store.close()
        }
    }
}
