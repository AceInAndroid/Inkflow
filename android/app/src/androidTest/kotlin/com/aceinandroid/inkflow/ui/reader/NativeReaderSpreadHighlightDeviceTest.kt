package com.aceinandroid.inkflow.ui.reader

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
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
import com.aceinandroid.inkflow.ReaderAppearance
import com.aceinandroid.inkflow.StoredAnnotation
import com.aceinandroid.inkflow.core.MixedPagination
import com.aceinandroid.inkflow.core.ReaderHighlightKind
import com.aceinandroid.inkflow.core.ReaderTransientHighlight
import com.aceinandroid.inkflow.core.TextPaginationCore
import com.aceinandroid.inkflow.core.TextSelectionRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeReaderSpreadHighlightDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val style = TextStyle(fontSize = 24.sp, lineHeight = 36.sp)
    private val source = (1..32).joinToString("\n") { "Line $it: 中文 🌊 reader text has actual wrapped words." }

    @Test fun halfWidthReflowsRealTextAndTwoLeavesCoverOneNavigationViewport() = main {
        val full = measure(source, 600)
        val half = measure(source, 288)
        assertTrue(half.lineCount > full.lineCount)
        val leaves = paginate(half)
        val summary = readerViewportPagination(leaves.summary, 2)
        val panes = readerPagePanes(half, leaves, 0, 2, 288f, 24f)
        assertEquals(2, panes.size)
        assertEquals(0f, panes[0].left, .001f)
        assertEquals(312f, panes[1].left, .001f)
        assertEquals(leaves.pages[0].slice.startOffset, summary.pages[0].startOffset)
        assertEquals(leaves.pages[1].slice.endOffset, summary.pages[0].endOffset)
        assertEquals(source.substring(0, summary.pages[0].endOffset), panes.joinToString("") {
            source.substring(it.page.slice.startOffset, it.page.slice.endOffset)
        })
    }

    @Test fun rightPaneTapResolvesItsAnnotationAndGutterDoesNotHitText() = main {
        val layout = measure(source, 288)
        val leaves = paginate(layout)
        val panes = readerPagePanes(layout, leaves, 0, 2, 288f, 24f)
        val right = panes[1]
        val item = right.page.items.first()
        val offset = item.startOffset + 2
        val glyph = layout.getBoundingBox(offset)
        val point = Offset(right.left + glyph.center.x,
            glyph.center.y + item.top - layout.getLineTop(item.lineIndex) - right.pageTop)
        val localPoint = requireNotNull(right.sourcePoint(layout, point))
        val note = StoredAnnotation(43, "saved wave", "range", "chapter", "wavy", "F6C453", "")
        val span = AnnotationSpan(item.startOffset, item.endOffset, "wavy", Color.Blue, note)
        assertSame(note, readerAnnotationAtPosition(layout, listOf(span), localPoint))
        assertNull(panes[0].sourcePoint(layout, point))
        for (pane in panes) assertNull(pane.sourcePoint(layout, Offset(300f, point.y)))
    }

    @Test fun oddLeafCountHasNoRightPaneToDrawOrHitOnLastSpread() = main {
        val layout = measure("First\nSecond\nThird", 288)
        val leaves = paginate(layout, 36f)
        assertEquals(3, leaves.pages.size)
        val panes = readerPagePanes(layout, leaves, 1, 2, 288f, 24f)
        assertEquals(1, panes.size)
        assertEquals(0f, panes.single().left, .001f)
        assertNull(panes.single().sourcePoint(layout, Offset(320f, 12f)))
        val summary = readerViewportPagination(leaves.summary, 2)
        assertEquals(layout.layoutInput.text.length, summary.pages.last().endOffset)
        assertEquals(2, summary.pageCount)
    }

    @Test fun transientRangeIsFaintAndClearsCompletelyWhenRemoved() = main {
        val layout = measure("prefix target suffix", 400)
        val mark = highlight(7, 13)
        val pixels = render(layout, mark)
        assertTrue(pixels.any { android.graphics.Color.alpha(it) in 1..64 })
        assertTrue(pixels.filter { it != 0 }.all { android.graphics.Color.alpha(it) < 80 })
        val target = layout.getBoundingBox(9).center
        assertTrue(pixels[target.y.toInt() * layout.size.width + target.x.toInt()] != 0)
        val prefix = layout.getBoundingBox(2).center
        assertEquals(0, pixels[prefix.y.toInt() * layout.size.width + prefix.x.toInt()])
        assertTrue(render(layout, null).all { it == 0 })
    }

    @Test fun einkTransientUsesStaticMonochromeOutlineAndLeavesGlyphInteriorClear() = main {
        val layout = measure("EINK target", 400)
        for (background in listOf(Color.White, Color.Black)) {
            val pixels = render(layout, highlight(0, 4), highContrast = true, background = background)
            assertTrue(pixels.any { android.graphics.Color.alpha(it) == 255 })
            val rgb = if (background == Color.White) 0 else 0xFFFFFF
            assertTrue(pixels.filter { it != 0 }.all { it and 0xFFFFFF == rgb })
            val center = layout.getBoundingBox(2).center
            assertEquals(0, pixels[center.y.toInt() * layout.size.width + center.x.toInt()])
        }
    }

    @Test fun streamedBlockUsesGlobalRangeAndKeepsBidiSurrogateGeometry() = main {
        val text = "中文 🌊 abc אבג target"
        val layout = measure(text, 160)
        assertArrayEquals(render(layout, highlight(3, text.length)),
            render(layout, highlight(83, 80 + text.length), sourceOffset = 80))
        val globalRange = highlight(0, 80)
        assertTrue(render(layout, globalRange, sourceOffset = 80).all { it == 0 })
    }

    @Test fun transientRangeCrossingTwoPanesPaintsOnlyVisibleRealPages() = main {
        val layout = measure(source, 288)
        val leaves = paginate(layout)
        val panes = readerPagePanes(layout, leaves, 0, 2, 288f, 24f)
        val mark = highlight(panes[0].page.slice.endOffset - 4, panes[1].page.slice.startOffset + 4)
        val width = 600
        val height = 144
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(600f, 144f)) {
                panes.forEach { pane ->
                    translate(left = pane.left) {
                        pane.segments.forEach { segment ->
                            clipRect(left = 0f, top = segment.top, right = pane.width, bottom = segment.bottom) {
                                translate(top = segment.translationY) {
                                    drawReaderTransientHighlight(layout, mark, false, Color.White,
                                        visibleStart = pane.page.slice.startOffset, visibleEnd = pane.page.slice.endOffset,
                                        firstLine = segment.firstLine, lastLine = segment.lastLine)
                                }
                            }
                        }
                    }
                }
            }
            val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
            assertTrue(pixels.indices.any { it % width < 288 && pixels[it] != 0 })
            assertTrue(pixels.indices.any { it % width >= 312 && pixels[it] != 0 })
            assertTrue(pixels.indices.filter { it % width in 288 until 312 }.all { pixels[it] == 0 })
        } finally { bitmap.recycle() }
        val hidden = highlight(leaves.pages[2].slice.startOffset, leaves.pages[2].slice.endOffset)
        assertTrue(render(layout, hidden, visibleStart = panes[0].page.slice.startOffset,
            visibleEnd = panes[1].page.slice.endOffset).all { it == 0 })
    }

    @Test fun spreadFingerprintSeparatesGeometryAndTransientChangesRetainRealCachedLayout() = runBlocking {
        val directory = File(context.cacheDir, "spread-highlight-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val appearance = ReaderAppearance(fontSize = 24)
            val resolver = withContext(Dispatchers.Main) { createFontFamilyResolver(context) }
            val full = readerLayoutFingerprint(AnnotatedString(source), style, emptyList(), emptyList(), appearance,
                600, 144f, 1f, 1f, "Ltr", "en", "test", "chapter", directory, null, "single-page", 1)
            val spread = full.copy(viewportWidthPx = 288, layoutMode = READER_SPREAD_LAYOUT_MODE, columnCount = 2)
            assertNotEquals(full.canonicalForm(), spread.canonicalForm())
            assertNotEquals(spread.canonicalForm(), spread.copy(layoutMode = "single-page", columnCount = 1).canonicalForm())
            val cache = NativeReaderPaginationCache(ReaderPaginationDiskCache(directory), scope)
            var measurements = 0
            var pageBreaks = 0
            suspend fun obtain() = withContext(Dispatchers.Main) {
                cache.obtain(spread, resolver, measure = {
                    measurements++
                    TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr, cacheSize = 0)
                        .measure(AnnotatedString(source), style, constraints = Constraints(maxWidth = 288))
                }, paginate = { pageBreaks++; paginate(it) })
            }
            val first = obtain()
            for (mark in listOf(highlight(0, 5), highlight(50, 70), null)) {
                withContext(Dispatchers.Main) { render(first.layout, mark) }
                val next = obtain()
                assertSame(first.layout, next.layout)
                assertSame(first.pagination, next.pagination)
                assertEquals(READER_SPREAD_LAYOUT_MODE, next.fingerprint.layoutMode)
            }
            assertEquals(1, measurements)
            assertEquals(1, pageBreaks)
        } finally { scope.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]?.join(); directory.deleteRecursively() }
    }

    private fun highlight(start: Int, end: Int) = ReaderTransientHighlight(1, 0,
        TextSelectionRange(start, end), ReaderHighlightKind.SEARCH)

    private fun measure(text: String, width: Int) = TextMeasurer(createFontFamilyResolver(context),
        Density(1f), LayoutDirection.Ltr, cacheSize = 0).measure(AnnotatedString(text), style,
        constraints = Constraints(maxWidth = width))

    private fun paginate(layout: TextLayoutResult, height: Float = 144f): MixedPagination =
        TextPaginationCore.paginateItems(readerPageItems(layout, emptyList(), 0f), height, layout.layoutInput.text.length)

    private fun render(
        layout: TextLayoutResult, mark: ReaderTransientHighlight?, sourceOffset: Int = 0,
        highContrast: Boolean = false, background: Color = Color.White,
        visibleStart: Int = sourceOffset, visibleEnd: Int = sourceOffset + layout.layoutInput.text.length,
    ): IntArray {
        val bitmap = Bitmap.createBitmap(layout.size.width, layout.size.height, Bitmap.Config.ARGB_8888)
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()),
                Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
                drawReaderTransientHighlight(layout, mark, highContrast, background, sourceOffset, visibleStart, visibleEnd)
            }
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        } finally { bitmap.recycle() }
    }

    private fun main(block: () -> Unit) = runBlocking { withContext(Dispatchers.Main) { block() } }
}
