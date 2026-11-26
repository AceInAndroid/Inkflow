package com.aceinandroid.inkflow

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset

@RunWith(AndroidJUnit4::class)
class TxtBookDeviceTest {
    @Test fun utf32BomIsDecodedOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "native-txt-utf32-device.txt")
        try {
            val source = "Chapter 1\nFirst page."
            file.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0) +
                source.toByteArray(Charset.forName("UTF-32LE")))
            val book = BookParser.inspect(file)
            assertEquals("Chapter 1", book.chapters.single().title)
            assertEquals("First page.", BookParser.readChapter(file, book.chapters.single()))
        } finally {
            file.delete()
        }
    }

    @Test fun textPositionPersistsOnlyForItsChapter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bookId = Long.MIN_VALUE
        try {
            BookStore(context).use { store ->
                store.saveTextPosition(bookId, 2, 87)
                assertEquals(87, store.textOffset(bookId, 2))
                assertEquals(0, store.textOffset(bookId, 1))
            }
            BookStore(context).use { store ->
                assertEquals(87, store.textOffset(bookId, 2))
                store.saveTextPosition(bookId, 3, 0)
                assertEquals(0, store.textOffset(bookId, 2))
            }
        } finally {
            context.getSharedPreferences("native_reader_positions", Context.MODE_PRIVATE)
                .edit().remove(bookId.toString()).commit()
            context.getSharedPreferences("native_reader_text_positions", Context.MODE_PRIVATE)
                .edit().remove("$bookId:chapter").remove("$bookId:offset").commit()
        }
    }

    @Test fun gb18030ChaptersAndSearchUseTheSameText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "native-txt-device.txt")
        try {
            file.writeBytes("第一章 开始\r\n第一段。\r\n第二章 继续\r\n找到目标。"
                .toByteArray(Charset.forName("GB18030")))
            val book = BookParser.inspect(file)
            assertEquals(listOf("第一章 开始", "第二章 继续"), book.toc.map { it.title })
            val text = BookParser.readChapter(file, book.chapters[1])
            val hit = BookSearcher.search(file, book, "目标").single()
            assertEquals(1, hit.chapterIndex)
            assertEquals(text.indexOf("目标"), hit.offset)
        } finally {
            file.delete()
        }
    }
}
