package com.aceinandroid.inkflow.ui.reader

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.EpubText
import com.aceinandroid.inkflow.ReaderAppearance
import com.aceinandroid.inkflow.StoredAnnotation
import com.aceinandroid.inkflow.core.TextPaginationCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeReaderAnnotationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val style = TextStyle(fontSize = 24.sp, lineHeight = 38.sp)
    private val appearance = ReaderAppearance(fontSize = 24)

    @Test fun changingAnnotationTypeColorOrNoteReusesActualLayoutAndPagination() = runBlocking {
        val epub = EpubText.fromHtml("<html><body><p>Cache this actual text 🌊, not its saved marks.</p></body></html>")
        val cfi = requireNotNull(epub.rangeCfiAt("epubcfi(/6/2)", 0, 12))
        val original = StoredAnnotation(12, epub.text.take(12), cfi, "chapter", "highlight", "F6C453", "")
        val directory = File(context.cacheDir, "annotation-layout-test-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { withContext(Dispatchers.Main) {
            val resolver = createFontFamilyResolver(context)
            val measurer = TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr, cacheSize = 0)
            val cache = NativeReaderPaginationCache(ReaderPaginationDiskCache(directory), scope)
            var measures = 0
            var paginations = 0
            var previous: NativeReaderLayout? = null
            for (note in listOf(original, original.copy(type = "underline", color = "FF0000"),
                original.copy(type = "wavy", color = "0000FF", readerNote = "edited"))) {
                val annotated = buildUnifiedChapterAnnotatedString(epub.text, epub, listOf(note), appearance)
                assertEquals(buildUnifiedChapterAnnotatedString(epub.text, epub, emptyList(), appearance), annotated)
                assertEquals(note.type, readerAnnotationSpans(epub, listOf(note)).single().type)
                val fingerprint = readerLayoutFingerprint(annotated, style, emptyList(), emptyList(), appearance,
                    300, 180f, 1f, 1f, "Ltr", "en-US", "test-fonts", "chapter", directory,
                    null, "single-page", 1)
                val result = cache.obtain(fingerprint, resolver,
                    measure = { measures++; measurer.measure(annotated, style, constraints = Constraints(maxWidth = 300)) },
                    paginate = { paginations++; TextPaginationCore.paginateItems(readerPageItems(it, emptyList(), 8f), 180f, annotated.length) })
                previous?.let { assertSame(it.layout, result.layout); assertSame(it.pagination, result.pagination) }
                previous = result
            }
            assertEquals(1, measures)
            assertEquals(1, paginations)
        } } finally {
            requireNotNull(scope.coroutineContext[Job]).children.toList().joinAll()
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test fun wavyPaintsCurvedPixelsAndPreservesUtf16Layout() = main {
        val layout = measure("Wave 中文 🌊 across this line")
        val originalGeometry = readerLayoutGeometryHash(layout)
        val span = AnnotationSpan(0, layout.layoutInput.text.length, "wavy", Color.Blue)
        val pixels = render(layout, listOf(span), underlay = false)
        val rows = pixels.indices.filter { android.graphics.Color.alpha(pixels[it]) > 100 }
            .map { it / layout.size.width }.toSet()
        assertTrue("A real wave must occupy multiple vertical positions", rows.size >= 3)
        assertEquals(originalGeometry, readerLayoutGeometryHash(layout))
        assertTrue(render(layout, listOf(span), underlay = true).all { it == 0 })
        val underline = render(layout, listOf(span.copy(type = "underline")), underlay = false)
        assertFalse("Wavy must not draw a straight underline", pixels.contentEquals(underline))
    }

    @Test fun wrapBidiSurrogateAndImageRangesAreClippedToVisibleLines() = main {
        val text = "中文 🌊 abc אבג xyz\n\uFFFC\nNext paragraph remains separate."
        val layout = measure(text, 140)
        assertTrue(layout.lineCount > 2)
        val imageLine = layout.getLineForOffset(text.indexOf('\uFFFC'))
        val visibleLine = 0
        val runs = readerAnnotationRuns(layout, 0, text.length, visibleLine, visibleLine)
        assertTrue(runs.isNotEmpty())
        assertTrue(runs.all { it.line == visibleLine && it.right > it.left })
        assertTrue(readerAnnotationRuns(layout, 0, text.length).none { it.line == imageLine })
        val wave = AnnotationSpan(0, text.length, "wavy", Color.Blue)
        val pixels = render(layout, listOf(wave), underlay = false, firstLine = visibleLine, lastLine = visibleLine)
        for (y in layout.getLineBottom(visibleLine).toInt() + 1 until layout.size.height)
            assertTrue(pixels.sliceArray(y * layout.size.width until (y + 1) * layout.size.width).all { it == 0 })
    }

    @Test fun fallbackBlockCoordinatesProduceTheSameWaveAsPagedCoordinates() = main {
        val layout = measure("Second block 🌊")
        val local = AnnotationSpan(0, layout.layoutInput.text.length, "wavy", Color.Red)
        val global = local.copy(start = 37, end = 37 + layout.layoutInput.text.length)
        assertArrayEquals(render(layout, listOf(local), underlay = false),
            render(layout, listOf(global), underlay = false, sourceOffset = 37))
    }

    @Test fun einkUsesOpaqueMonochromeForAllThreeStylesAndKeepsTextAreaClear() = main {
        val layout = measure("EINK marks")
        for (background in listOf(Color.White, Color.Black)) {
            val expected = if (background == Color.White) 0x000000 else 0xFFFFFF
            for (type in listOf("highlight", "underline", "wavy")) {
                val span = AnnotationSpan(0, 5, type, Color.Red)
                assertTrue(render(layout, listOf(span), underlay = true, highContrast = true,
                    background = background).all { it == 0 })
                val pixels = render(layout, listOf(span), underlay = false, highContrast = true, background = background)
                assertTrue(pixels.any { android.graphics.Color.alpha(it) == 255 })
                assertTrue(pixels.filter { android.graphics.Color.alpha(it) > 0 }.all { (it and 0xFFFFFF) == expected })
                if (type == "highlight") {
                    val box = layout.getBoundingBox(2)
                    assertEquals("EINK highlight outline must not cover glyph interiors", 0,
                        pixels[box.center.y.toInt() * layout.size.width + box.center.x.toInt()])
                }
            }
        }
    }

    @Test fun annotationHitTestingResolvesStoredIdAndRejectsPaddingAndPreview() = main {
        val layout = measure("Click a saved wave")
        val note = StoredAnnotation(9, "saved", "range", "chapter", "wavy", "FF0000", "")
        val span = AnnotationSpan(0, 5, "wavy", Color.Red, note)
        assertSame(note, readerAnnotationAtPosition(layout, listOf(span), layout.getBoundingBox(2).center))
        assertNull(readerAnnotationAtPosition(layout, listOf(span), Offset(299f, 10f)))
        assertNull(readerAnnotationAtOffset(listOf(span), 5))
        assertNull(readerAnnotationAtOffset(listOf(span.copy(annotation = note.copy(id = -1))), 2))
        val global = span.copy(start = 100, end = 105)
        assertSame(note, readerAnnotationAtPosition(layout, listOf(global), layout.getBoundingBox(2).center, 100))
    }

    private fun measure(text: String, width: Int = 300): TextLayoutResult = TextMeasurer(
        createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr, cacheSize = 0,
    ).measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = width))

    private fun render(
        layout: TextLayoutResult, spans: List<AnnotationSpan>, underlay: Boolean,
        sourceOffset: Int = 0, highContrast: Boolean = false, background: Color = Color.White,
        firstLine: Int = 0, lastLine: Int = layout.lineCount - 1,
    ): IntArray {
        val bitmap = Bitmap.createBitmap(layout.size.width, layout.size.height, Bitmap.Config.ARGB_8888)
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()),
                Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
                drawReaderAnnotations(layout, spans, highContrast, background, underlay, sourceOffset,
                    firstLine = firstLine, lastLine = lastLine)
            }
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        } finally { bitmap.recycle() }
    }

    private fun main(block: () -> Unit) = runBlocking { withContext(Dispatchers.Main) { block() } }
}
