package com.aceinandroid.inkflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfSearchTest {
    @Test fun recognizesImageOnlyPageForTextModeAndSearch() {
        org.junit.Assume.assumeTrue("Scanned-page OCR requires fullOcr", BuildConfig.ON_DEVICE_OCR)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "ocr-${UUID.randomUUID()}.pdf")
        val bitmap = Bitmap.createBitmap(1000, 320, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("SCANNED TARGET", 40f, 130f, paint)
            canvas.drawText("扫描文字", 40f, 265f, paint)
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(1000, 320, 1).create())
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdf.finishPage(page)
                file.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            PDDocument.load(file).use { pdf ->
                assertTrue("Fixture must contain no extractable text",
                    PDFTextStripper().getText(pdf).isBlank())
            }
            val hit = BookSearcher.search(file, BookParser.inspect(file), "TARGET").single()
            assertEquals(0, hit.chapterIndex)
            assertTrue(hit.isPdfPage)
            assertTrue(hit.excerpt.contains("TARGET", ignoreCase = true))
            val recognized = BookSearcher.readPdfPageText(file, 0)
            assertTrue("OCR text: $recognized", recognized.contains("TARGET", ignoreCase = true))
            assertTrue("Chinese OCR text: $recognized", recognized.contains("扫描"))
            assertEquals(0, BookSearcher.search(file, BookParser.inspect(file), "扫描")
                .single().chapterIndex)

            val store = BookStore(context)
            val book = store.import(Uri.fromFile(file))
            var activity: android.app.Activity? = null
            try {
                activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                click(book.title)
                click(NativeLocaleContext(context).getString(R.string.pdf_text_mode))
                val textNode = waitFor("TARGET") ?: error("OCR text is not visible in PDF text mode")
                val offset = textNode.text.toString().indexOf("TARGET")
                assertTrue(offset >= 0)
                val selection = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, offset)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, offset + 6)
                }
                assertTrue("OCR text cannot be selected", textNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION, selection))
            } finally {
                activity?.finish()
                store.removeBook(book.id)
                store.close()
            }
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test fun findsTextOnTheCorrectPdfPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "pdf-search-test.pdf")
        try {
            PDDocument().use { pdf ->
                for (line in listOf("Opening page", "Find the Target on page two")) {
                    val page = PDPage()
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(72f, 700f)
                        stream.showText(line)
                        stream.endText()
                    }
                }
                pdf.save(file)
            }
            val hit = BookSearcher.search(file, BookParser.inspect(file), "target").single()
            assertEquals(1, hit.chapterIndex)
            assertTrue(hit.isPdfPage)
            assertTrue(hit.excerpt.contains("Target on page two"))
            assertEquals("", hit.cfi)
            assertTrue(BookSearcher.search(file, BookParser.inspect(file), "missing").isEmpty())
            assertTrue(BookSearcher.readPdfPageText(file, 0).contains("Opening page"))
            assertTrue(BookSearcher.readPdfPageText(file, 1).contains("Target on page two"))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(2, renderer.pageCount)
                    renderer.openPage(hit.chapterIndex).use { page -> assertTrue(page.width > 0) }
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun click(label: String) {
        repeat(30) {
            var node = waitFor(label) ?: error("Missing $label")
            while (!node.isClickable) node = node.parent ?: error("Cannot click $label")
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(100)
        }
        error("Cannot click $label")
    }

    private fun waitFor(value: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.contains(value, ignoreCase = true) == true ||
                node.contentDescription?.toString() == value) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        repeat(150) {
            find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
                ?.let { return it }
            SystemClock.sleep(100)
        }
        return null
    }
}
