package com.aceinandroid.inkflow

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

@RunWith(AndroidJUnit4::class)
class Fb2BookDeviceTest {
    @Test fun embeddedPngRendersFromBinaryResource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "native-fb2-image-device.fb2")
        try {
            val bytes = ByteArrayOutputStream().use { stream ->
                val source = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
                try {
                    source.eraseColor(Color.GREEN)
                    source.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.toByteArray()
                } finally {
                    source.recycle()
                }
            }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            file.writeText("""
                <FictionBook xmlns:l="http://www.w3.org/1999/xlink">
                  <body><section><p>Before.</p><image l:href="#picture" alt="Picture"/><p>After.</p></section></body>
                  <binary id="picture" content-type="image/png">$encoded</binary>
                </FictionBook>
            """.trimIndent())
            val book = BookParser.inspect(file)
            val content = Fb2Book.readChapterText(file, book.chapters.single())
            val image = content.images.single()
            assertEquals("#picture", image.href)
            val decoded = Fb2ImageLoader.load(file, image.href)
            assertEquals(2, decoded?.width)
            assertEquals(3, decoded?.height)
            decoded?.recycle()
        } finally {
            file.delete()
        }
    }

    @Test fun declaredEncodingAndNestedDirectoryWorkOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "native-fb2-device.fb2")
        try {
            file.writeBytes("""
                <?xml version="1.0" encoding="windows-1251"?>
                <FictionBook><description><title-info><book-title>Книга</book-title></title-info></description>
                  <body><section><title><p>Первая</p></title><p>Начало.</p>
                    <section><title><p>Вторая</p></title><p>Цель.</p></section>
                  </section></body></FictionBook>
            """.trimIndent().toByteArray(Charset.forName("windows-1251")))
            val book = BookParser.inspect(file)
            assertEquals("Книга", book.title)
            assertEquals(listOf(0, 1), book.toc.map { it.depth })
            assertEquals("Цель.", BookParser.readChapter(file, book.chapters[1]))
        } finally {
            file.delete()
        }
    }
}
