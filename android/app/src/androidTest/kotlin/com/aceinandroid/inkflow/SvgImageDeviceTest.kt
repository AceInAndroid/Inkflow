package com.aceinandroid.inkflow

import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SvgImageDeviceTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10" viewBox="0 0 20 10"><rect width="20" height="10" fill="#ff0000"/></svg>"""

    @Test fun svgResourceRendersThroughSharedImageDecoder() {
        val bitmap = decodeBookBitmap { ByteArrayInputStream(svg.toByteArray()) }
        assertNotNull(bitmap)
        assertEquals(20, bitmap!!.width)
        assertEquals(10, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(5, 5))
        bitmap.recycle()
    }

    @Test fun epubAndFb2SvgResourcesRender() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val epub = File(cache, "native-svg-device.epub")
        val fb2 = File(cache, "native-svg-device.fb2")
        try {
            ZipOutputStream(epub.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("OEBPS/images/picture.svg"))
                zip.write(svg.toByteArray())
                zip.closeEntry()
            }
            fb2.writeText("""<FictionBook><binary id="picture" content-type="image/svg+xml">${Base64.encodeToString(svg.toByteArray(), Base64.NO_WRAP)}</binary></FictionBook>""")
            val epubBitmap = EpubImageLoader.load(epub, Chapter("OEBPS/text/chapter.xhtml", "Chapter"), "../images/picture.svg")
            val fb2Bitmap = Fb2ImageLoader.load(fb2, "#picture")
            assertEquals(Color.RED, epubBitmap?.getPixel(5, 5))
            assertEquals(Color.RED, fb2Bitmap?.getPixel(5, 5))
            epubBitmap?.recycle()
            fb2Bitmap?.recycle()
        } finally {
            epub.delete()
            fb2.delete()
        }
    }

    @Test fun inlineEpubSvgRendersAndKeepsFollowingText() {
        val chapter = EpubText.fromHtml("""<html><body><p>Before<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10" viewBox="0 0 20 10"><rect width="20" height="10" fill="#ff0000"/></svg>After</p></body></html>""")
        assertEquals("Before\uFFFCAfter", chapter.text)
        val bitmap = EpubImageLoader.load(File("unused.epub"), Chapter("chapter.xhtml", "Chapter"), chapter.images.single().href)
        assertEquals(Color.RED, bitmap?.getPixel(5, 5))
        bitmap?.recycle()
    }

    @Test fun epubDataUriImageRenders() {
        val href = "data:image/svg+xml;base64,${Base64.encodeToString(svg.toByteArray(), Base64.NO_WRAP)}"
        val bitmap = EpubImageLoader.load(File("unused.epub"), Chapter("chapter.xhtml", "Chapter"), href)
        assertEquals(Color.RED, bitmap?.getPixel(5, 5))
        bitmap?.recycle()
    }
}
