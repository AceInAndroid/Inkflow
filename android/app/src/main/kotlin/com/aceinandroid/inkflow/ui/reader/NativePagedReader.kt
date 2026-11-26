package com.aceinandroid.inkflow.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aceinandroid.inkflow.Chapter
import com.aceinandroid.inkflow.EpubImageLoader
import com.aceinandroid.inkflow.EpubText
import com.aceinandroid.inkflow.EpubTextAlignment
import com.aceinandroid.inkflow.EpubTextStyle
import com.aceinandroid.inkflow.Fb2ImageLoader
import com.aceinandroid.inkflow.MobiImageLoader
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ReaderAppearance
import com.aceinandroid.inkflow.StoredAnnotation
import com.aceinandroid.inkflow.core.PageItem
import com.aceinandroid.inkflow.core.LineMetrics
import com.aceinandroid.inkflow.core.PaginationSummary
import com.aceinandroid.inkflow.core.SentenceBoundaryResolver
import com.aceinandroid.inkflow.core.TextPaginationCore
import com.aceinandroid.inkflow.core.TextSelectionRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class AnnotationSpan(
    val start: Int,
    val end: Int,
    val type: String,
    val color: Color,
)

/**
 * Builds a single unified AnnotatedString for a chapter so that true pagination
 * and cross-paragraph text selection operate seamlessly on one coordinate space.
 */
fun buildUnifiedChapterAnnotatedString(
    rawText: String,
    epubText: EpubText?,
    annotations: List<StoredAnnotation>,
    appearance: ReaderAppearance,
): AnnotatedString {
    val source = epubText?.text ?: rawText
    val annotationSpans = if (epubText != null) {
        annotations.mapNotNull { note ->
            val range = epubText.offsetsForRange(note.cfi) ?: return@mapNotNull null
            val rgb = note.color.toLongOrNull(16) ?: return@mapNotNull null
            AnnotationSpan(range.first, range.last + 1, note.type, Color(0xFF000000 or rgb))
        }
    } else emptyList()

    return buildAnnotatedString {
        append(source)
        val paragraphs = mutableListOf<AnnotatedString.Range<ParagraphStyle>>()
        if (source.isNotEmpty()) {
            paragraphs += AnnotatedString.Range(ParagraphStyle(
                textAlign = appearance.textAlign ?: TextAlign.Unspecified,
                textIndent = TextIndent(firstLine = appearance.firstLineIndent.sp),
            ), 0, source.length)
        }
        if (appearance.textAlign == null) epubText?.alignments?.asReversed()?.forEach { range ->
            val start = range.start.coerceIn(0, source.length)
            val end = range.end.coerceIn(start, source.length)
            if (start < end) paragraphs += AnnotatedString.Range(ParagraphStyle(textAlign = when (range.alignment) {
                EpubTextAlignment.START -> TextAlign.Start
                EpubTextAlignment.END -> TextAlign.End
                EpubTextAlignment.LEFT -> TextAlign.Left
                EpubTextAlignment.RIGHT -> TextAlign.Right
                EpubTextAlignment.CENTER -> TextAlign.Center
                EpubTextAlignment.JUSTIFY -> TextAlign.Justify
            }), start, end)
        }
        epubText?.styles?.forEach { range ->
            val start = range.start.coerceIn(0, source.length)
            val end = range.end.coerceIn(start, source.length)
            if (start < end) addStyle(epubSpanStyle(range.style, appearance.fontSize), start, end)
        }
        epubText?.visuals?.forEach { range ->
            val start = range.start.coerceIn(0, source.length)
            val end = range.end.coerceIn(start, source.length)
            if (start < end) {
                range.color?.let {
                    addStyle(SpanStyle(color = readableEpubColor(Color(it), appearance)), start, end)
                }
                range.fontScale?.let {
                    addStyle(SpanStyle(fontSize = (appearance.fontSize * it).sp), start, end)
                }
            }
        }
        epubText?.images?.forEach { image ->
            if (image.offset in source.indices) {
                val start = source.lastIndexOf('\n', image.offset).let { if (it < 0) 0 else it + 1 }
                val end = source.indexOf('\n', image.offset).let { if (it < 0) source.length else it + 1 }
                if (start < end) paragraphs += AnnotatedString.Range(ParagraphStyle(textIndent = TextIndent.None), start, end)
            }
        }
        // EPUB block alignment and image indentation can overlap by a trailing newline.
        // Compose rejects partially overlapping paragraph ranges, so merge the active
        // styles into disjoint intervals. Apply outer blocks before inner blocks,
        // as Compose does for valid nested paragraph ranges (HTML closes inner first).
        val ordered = paragraphs.sortedWith(compareBy<AnnotatedString.Range<ParagraphStyle>> { it.start }
            .thenByDescending { it.end })
        val starts = ordered.indices.groupBy { ordered[it].start }
        val ends = ordered.indices.groupBy { ordered[it].end }
        val boundaries = (starts.keys + ends.keys).sorted()
        val active = java.util.TreeMap<Int, ParagraphStyle>()
        val resolved = mutableListOf<AnnotatedString.Range<ParagraphStyle>>()
        boundaries.forEachIndexed { index, start ->
            ends[start]?.forEach { active.remove(it) }
            starts[start]?.forEach { active[it] = ordered[it].item }
            val end = boundaries.getOrNull(index + 1)
            if (end != null && start < end && active.isNotEmpty()) {
                val merged = active.values.fold(ParagraphStyle()) { style, next -> style.merge(next) }
                val previous = resolved.lastOrNull()
                if (previous != null && previous.end == start && previous.item == merged) {
                    resolved[resolved.lastIndex] = previous.copy(end = end)
                } else resolved += AnnotatedString.Range(merged, start, end)
            }
        }
        resolved.forEach { addStyle(it.item, it.start, it.end) }
        annotationSpans.forEach { span ->
            val start = span.start.coerceIn(0, source.length)
            val end = span.end.coerceIn(start, source.length)
            if (start < end) {
                addStyle(
                    if (span.type == "underline") {
                        SpanStyle(textDecoration = TextDecoration.Underline, color = span.color)
                    } else {
                        SpanStyle(background = span.color.copy(alpha = 0.35f))
                    },
                    start,
                    end,
                )
            }
        }
    }
}

/**
 * Primary native Canvas/TextMeasurer paginated reader engine.
 * - True horizontal pagination with zero WebView overhead and crisp E-INK page flips.
 * - Single-coordinate cross-paragraph text selection via long-press + KMP SentenceBoundaryResolver.
 * - Tap left/right edges to turn pages; tap center to toggle immersive chrome or clear selection.
 */
@Composable
fun NativePagedReaderCanvas(
    chapterTitle: String,
    chapterIndex: Int,
    totalChapters: Int,
    rawText: String,
    epubText: EpubText?,
    annotations: List<StoredAnnotation>,
    appearance: ReaderAppearance,
    fontFamily: FontFamily,
    currentPage: Int,
    selection: TextSelectionRange?,
    onPaginationReady: (PaginationSummary) -> Unit,
    onPageChange: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onToggleChrome: () -> Unit,
    onSelectionChange: (TextSelectionRange?) -> Unit,
    modifier: Modifier = Modifier,
    file: File? = null,
    chapter: Chapter? = null,
    swipeToTurnPage: Boolean = false,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val annotated = remember(rawText, epubText, annotations, appearance) {
        buildUnifiedChapterAnnotatedString(rawText, epubText, annotations, appearance)
    }
    val plainText = annotated.text
    val selectedNow by rememberUpdatedState(selection)
    val changeSelection by rememberUpdatedState(onSelectionChange)
    var viewingImage by remember(file, chapter) { mutableStateOf<Bitmap?>(null) }
    val baseStyle = TextStyle(
        fontSize = appearance.fontSize.sp,
        lineHeight = (appearance.fontSize * appearance.lineSpacing).sp,
        fontFamily = fontFamily,
        color = appearance.foreground,
    )
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    Column(modifier.fillMaxSize()) {
        if (appearance.showHeader) Row(
            Modifier.fillMaxWidth().padding(horizontal = appearance.pageMargin.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(chapterTitle, color = appearance.foreground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${chapterIndex + 1} / $totalChapters", color = appearance.foreground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()
            .padding(horizontal = appearance.pageMargin.dp, vertical = 4.dp)) {
            val widthPx = with(density) { maxWidth.toPx().roundToInt().coerceAtLeast(1) }
            val heightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
            val images = remember(epubText) { epubText?.images.orEmpty().filter {
                it.offset in plainText.indices && plainText[it.offset] == '\uFFFC'
            } }
            val placeholders = remember(images, widthPx, heightPx, baseStyle, density, appearance.paragraphSpacing) {
                images.map { image ->
                    val imageSize = TextPaginationCore.fitImage(
                        image.width?.toFloat() ?: widthPx.toFloat(),
                        image.height?.toFloat() ?: widthPx * 0.75f,
                        widthPx.toFloat(),
                        (heightPx - with(density) { baseStyle.lineHeight.toPx() * 2 + appearance.paragraphSpacing.dp.toPx() }).coerceAtLeast(1f),
                    )
                    AnnotatedString.Range(Placeholder(
                        with(density) { imageSize.width.toSp() },
                        with(density) { imageSize.height.toSp() },
                        PlaceholderVerticalAlign.AboveBaseline,
                    ), image.offset, image.offset + 1)
                }
            }
            val layout = remember(annotated, baseStyle, widthPx, placeholders) {
                textMeasurer.measure(annotated, baseStyle, placeholders = placeholders,
                    constraints = Constraints(maxWidth = widthPx))
            }
            val mixed = remember(layout, heightPx, images, appearance.paragraphSpacing, density) {
                val spacing = with(density) { appearance.paragraphSpacing.dp.toPx() }
                TextPaginationCore.paginateItems(readerPageItems(layout, images, spacing), heightPx, plainText.length)
            }
            val pagination = mixed.summary
            LaunchedEffect(pagination) { onPaginationReady(pagination) }
            val pageIndex = currentPage.coerceIn(0, pagination.pageCount - 1)
            val page = pagination.pages[pageIndex]
            val pageItems = mixed.pages[pageIndex].items
            val pageTop = pageItems.firstOrNull()?.top ?: 0f
            val drawSegments = remember(pageItems, layout, pageTop) {
                val segments = mutableListOf<ReaderDrawSegment>()
                pageItems.forEach { item ->
                    val sourceTop = layout.getLineTop(item.lineIndex)
                    val delta = item.top - sourceTop - pageTop
                    val bottom = layout.getLineBottom(item.lineIndex) + delta
                    val previous = segments.lastOrNull()
                    if (previous != null && kotlin.math.abs(previous.translationY - delta) < 0.1f)
                        segments[segments.lastIndex] = previous.copy(bottom = bottom)
                    else segments += ReaderDrawSegment(sourceTop + delta, bottom, delta)
                }
                segments
            }
            fun turn(forward: Boolean) {
                if (forward) {
                    if (pageIndex + 1 < pagination.pageCount) onPageChange(pageIndex + 1) else onNextChapter()
                } else {
                    if (pageIndex > 0) onPageChange(pageIndex - 1) else onPreviousChapter()
                }
            }
            val hitTest by rememberUpdatedState<(Offset) -> Int>({ point ->
                val y = point.y + pageTop
                val item = pageItems.firstOrNull { y < it.bottom } ?: pageItems.lastOrNull()
                val sourceY = if (item == null) y else
                    (y - item.top + layout.getLineTop(item.lineIndex)).coerceIn(
                        layout.getLineTop(item.lineIndex), layout.getLineBottom(item.lineIndex) - 0.1f)
                layout.getOffsetForPosition(Offset(point.x, sourceY))
                    .coerceIn(page.startOffset, (page.endOffset - 1).coerceAtLeast(page.startOffset))
            })
            val turnCurrentPage by rememberUpdatedState<(Boolean) -> Unit>({ forward -> turn(forward) })
            Box(Modifier.fillMaxSize().semantics {
                val excerpt = plainText.substring(page.startOffset, page.endOffset)
                text = AnnotatedString(excerpt)
                editableText = AnnotatedString(plainText)
                contentDescription = "$chapterTitle, ${pageIndex + 1}/${pagination.pageCount}"
                textSelectionRange = selection?.let { TextRange(it.start, it.end) } ?: TextRange.Zero
                setSelection { start, end, _ ->
                    changeSelection(if (start != end) TextSelectionRange(start, end).normalized() else null)
                    true
                }
            }.pointerInput(plainText) {
                detectTapGestures(onTap = { point ->
                    if (selectedNow != null) changeSelection(null)
                    else when {
                        point.x < size.width * 0.28f -> turnCurrentPage(false)
                        point.x > size.width * 0.72f -> turnCurrentPage(true)
                        else -> onToggleChrome()
                    }
                }, onLongPress = { point ->
                    if (selectedNow != null) changeSelection(null)
                    else changeSelection(SentenceBoundaryResolver.sentenceRangeAt(plainText, hitTest(point)))
                })
            }.pointerInput(plainText, swipeToTurnPage) {
                var anchor: TextSelectionRange? = null
                var distance = 0f
                var dismissOnly = false
                detectDragGestures(
                    onDragStart = { point ->
                        distance = 0f
                        dismissOnly = selectedNow != null
                        if (dismissOnly) changeSelection(null)
                        else if (!swipeToTurnPage) {
                            anchor = SentenceBoundaryResolver.sentenceRangeAt(plainText, hitTest(point))
                            changeSelection(anchor)
                        }
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        distance += delta.x
                        if (!dismissOnly && !swipeToTurnPage) {
                            val touched = SentenceBoundaryResolver.sentenceRangeAt(plainText, hitTest(change.position))
                            val first = anchor
                            if (first != null && touched != null) changeSelection(TextSelectionRange(
                                min(first.start, touched.start), max(first.end, touched.end)))
                        }
                    },
                    onDragEnd = {
                        if (!dismissOnly && swipeToTurnPage && kotlin.math.abs(distance) > with(density) { 48.dp.toPx() })
                            turnCurrentPage(distance < 0)
                        anchor = null
                    },
                    onDragCancel = { anchor = null },
                )
            }) {
                Canvas(Modifier.fillMaxSize()) {
                    drawSegments.forEach { segment ->
                        clipRect(left = 0f, top = segment.top.coerceAtLeast(0f), right = size.width,
                            bottom = segment.bottom.coerceAtMost(size.height)) {
                            translate(top = segment.translationY) {
                                selection?.normalized()?.let { range ->
                                    val start = range.start.coerceIn(0, plainText.length)
                                    val end = range.end.coerceIn(start, plainText.length)
                                    if (start < end) drawPath(layout.getPathForRange(start, end), selectionColor)
                                }
                                drawText(layout)
                            }
                        }
                    }
                }
                images.forEachIndexed { index, image ->
                    val rect = layout.placeholderRects.getOrNull(index)
                    if (file != null && chapter != null && rect != null &&
                        image.offset in page.startOffset until page.endOffset) {
                        val imageLine = layout.getLineForOffset(image.offset)
                        val lineTop = pageItems.firstOrNull { it.lineIndex == imageLine }?.top ?: pageTop
                        val imageTop = rect.top + lineTop - layout.getLineTop(imageLine) - pageTop
                        val bitmap by produceState<Bitmap?>(null, file, chapter.path, image.href) {
                            value = withContext(Dispatchers.IO) { loadReaderImage(file, chapter, image.href) }
                        }
                        Box(Modifier.offset(with(density) { rect.left.toDp() }, with(density) { imageTop.toDp() })
                            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                            .clickable {
                                if (selectedNow != null) changeSelection(null)
                                else bitmap?.let { viewingImage = it }
                            }, contentAlignment = Alignment.Center) {
                            if (bitmap != null) Image(bitmap!!.asImageBitmap(), image.alt.ifBlank {
                                stringResource(R.string.migration_image)
                            }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else Text(image.alt.ifBlank { stringResource(R.string.migration_image_unavailable) },
                                color = appearance.foreground)
                        }
                    }
                }
            }
        }
        if (appearance.showFooter) Row(
            Modifier.fillMaxWidth().padding(horizontal = appearance.pageMargin.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ReaderRunningStatus(appearance, "${if (totalChapters > 0) (chapterIndex + 1) * 100 / totalChapters else 0}%")
            Text("${currentPage + 1}", color = appearance.foreground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall)
        }
    }
    viewingImage?.let { bitmap -> ImageViewerDialog(bitmap, onDismiss = { viewingImage = null }) }
}

private data class ReaderDrawSegment(val top: Float, val bottom: Float, val translationY: Float)

internal fun readerPageItems(
    layout: TextLayoutResult,
    images: List<com.aceinandroid.inkflow.EpubImage>,
    paragraphSpacing: Float,
): List<PageItem> {
    val source = layout.layoutInput.text.text
    var extraSpace = 0f
    return (0 until layout.lineCount).map { line ->
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line, visibleEnd = false)
        val top = layout.getLineTop(line) + extraSpace
        val paragraphEnd = end > start && end < source.length && source[end - 1] == '\n'
        if (paragraphEnd) extraSpace += paragraphSpacing.coerceAtLeast(0f)
        val bottom = layout.getLineBottom(line) + extraSpace
        val image = images.firstOrNull { it.offset in start until end }
        if (image == null) PageItem.TextSlice(start, end, line, top, bottom)
        else PageItem.ImageBlock(image.href, start, end, line, top, bottom)
    }
}

internal fun loadReaderImage(file: File, chapter: Chapter, href: String): Bitmap? = when (file.extension.lowercase()) {
    "epub" -> EpubImageLoader.load(file, chapter, href)
    "fb2" -> Fb2ImageLoader.load(file, href)
    else -> MobiImageLoader.load(file, href)
}

@OptIn(FlowPreview::class)
@Composable
fun VirtualizedScrollReader(
    chapterTitle: String,
    showChapterHeading: Boolean,
    file: File,
    chapter: Chapter,
    rawText: String,
    epubText: EpubText?,
    annotations: List<StoredAnnotation>,
    appearance: ReaderAppearance,
    fontFamily: FontFamily,
    initialOffset: Int?,
    onOffsetSettled: (Int) -> Unit,
    onSelectionChange: (TextSelectionRange?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val blocks = remember(epubText, rawText) { epubText?.renderBlocks() }

    val annotationSpans = remember(epubText, annotations) {
        if (epubText == null) emptyList() else annotations.mapNotNull { note ->
            val range = epubText.offsetsForRange(note.cfi) ?: return@mapNotNull null
            val rgb = note.color.toLongOrNull(16) ?: return@mapNotNull null
            AnnotationSpan(range.first, range.last + 1, note.type, Color(0xFF000000 or rgb))
        }
    }

    LaunchedEffect(initialOffset, blocks) {
        val offset = initialOffset ?: return@LaunchedEffect
        if (blocks != null && blocks.isNotEmpty()) {
            val idx = blocks.indexOfFirst { offset in it.start until it.end }.coerceAtLeast(0)
            listState.scrollToItem(idx + if (showChapterHeading) 1 else 0)
        }
    }

    LaunchedEffect(listState, blocks) {
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(500).collectLatest { visibleIdx ->
            val adjusted = (visibleIdx - if (showChapterHeading) 1 else 0).coerceAtLeast(0)
            val offset = blocks?.getOrNull(adjusted)?.start ?: 0
            onOffsetSettled(offset)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = appearance.pageMargin.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(appearance.paragraphSpacing.dp),
    ) {
        if (showChapterHeading) {
            item(key = "chapter_heading") {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = appearance.foreground,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        if (blocks != null && epubText != null) {
            itemsIndexed(blocks, key = { index, b -> "${b.start}-${b.end}-$index" }) { _, block ->
                val image = block.image
                if (image != null) {
                    val bitmap by produceState<Bitmap?>(null, file, chapter.path, image.href) {
                        value = withContext(Dispatchers.IO) {
                            when (file.extension.lowercase()) {
                                "epub" -> EpubImageLoader.load(file, chapter, image.href)
                                "fb2" -> Fb2ImageLoader.load(file, image.href)
                                else -> MobiImageLoader.load(file, image.href)
                            }
                        }
                    }
                    val imgWidth = image.width
                    val imgHeight = image.height
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val availableWidth = maxWidth
                        val width = imgWidth?.takeIf { it > 0 }?.dp?.coerceAtMost(availableWidth) ?: availableWidth
                        val ratio = if (imgWidth != null && imgHeight != null && imgWidth > 0) {
                            imgHeight.toFloat() / imgWidth
                        } else {
                            bitmap?.let { it.height.toFloat() / it.width } ?: 0.75f
                        }
                        val height = (width * ratio).coerceAtMost(availableWidth * 1.5f)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = image.alt.ifBlank { null },
                                modifier = Modifier.size(width, height),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Box(
                                Modifier.size(width, height).background(Color(0xFFE9EDE8)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(image.alt, maxLines = 2, color = appearance.foreground)
                            }
                        }
                    }
                } else {
                    val annotated = remember(epubText, block, annotationSpans, appearance.fontSize, appearance.foreground, appearance.background) {
                        buildAnnotatedString {
                            append(epubText.text.substring(block.visibleStart, block.visibleEnd))
                            epubText.styles.forEach { range ->
                                val start = range.start.coerceIn(block.visibleStart, block.visibleEnd) - block.visibleStart
                                val end = range.end.coerceIn(block.visibleStart, block.visibleEnd) - block.visibleStart
                                if (start < end) addStyle(epubSpanStyle(range.style, appearance.fontSize), start, end)
                            }
                            epubText.visuals.forEach { range ->
                                val start = range.start.coerceIn(block.visibleStart, block.visibleEnd) - block.visibleStart
                                val end = range.end.coerceIn(block.visibleStart, block.visibleEnd) - block.visibleStart
                                if (start < end) {
                                    range.color?.let { addStyle(SpanStyle(color = readableEpubColor(Color(it), appearance)), start, end) }
                                    range.fontScale?.let { addStyle(SpanStyle(fontSize = (appearance.fontSize * it).sp), start, end) }
                                }
                            }
                            annotationSpans.forEach { span ->
                                val start = max(span.start, block.visibleStart) - block.visibleStart
                                val end = min(span.end, block.visibleEnd) - block.visibleStart
                                if (start < end) {
                                    addStyle(
                                        if (span.type == "underline") SpanStyle(textDecoration = TextDecoration.Underline, color = span.color)
                                        else SpanStyle(background = span.color.copy(alpha = 0.35f)),
                                        start,
                                        end,
                                    )
                                }
                            }
                        }
                    }
                    var fieldValue by remember(epubText, block, annotated) { mutableStateOf(TextFieldValue(annotated)) }
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { changed ->
                            fieldValue = TextFieldValue(annotated, changed.selection)
                            val start = min(changed.selection.start, changed.selection.end)
                            val end = max(changed.selection.start, changed.selection.end)
                            onSelectionChange(if (start < end) TextSelectionRange(block.visibleStart + start, block.visibleStart + end) else null)
                        },
                        readOnly = true,
                        textStyle = TextStyle(
                            fontSize = appearance.fontSize.sp,
                            lineHeight = (appearance.fontSize * appearance.lineSpacing).sp,
                            fontFamily = fontFamily,
                            color = appearance.foreground,
                            textIndent = TextIndent(firstLine = appearance.firstLineIndent.sp),
                            textAlign = appearance.textAlign ?: when (block.alignment) {
                                EpubTextAlignment.START -> TextAlign.Start
                                EpubTextAlignment.END -> TextAlign.End
                                EpubTextAlignment.LEFT -> TextAlign.Left
                                EpubTextAlignment.RIGHT -> TextAlign.Right
                                EpubTextAlignment.CENTER -> TextAlign.Center
                                EpubTextAlignment.JUSTIFY -> TextAlign.Justify
                                null -> TextAlign.Unspecified
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            item(key = "plain_text_body") {
                var fieldValue by remember(rawText) { mutableStateOf(TextFieldValue(rawText)) }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { changed ->
                        fieldValue = TextFieldValue(rawText, changed.selection)
                        val start = min(changed.selection.start, changed.selection.end)
                        val end = max(changed.selection.start, changed.selection.end)
                        onSelectionChange(if (start < end) TextSelectionRange(start, end) else null)
                    },
                    readOnly = true,
                    textStyle = TextStyle(
                        fontSize = appearance.fontSize.sp,
                        lineHeight = (appearance.fontSize * appearance.lineSpacing).sp,
                        fontFamily = fontFamily,
                        color = appearance.foreground,
                        textAlign = appearance.textAlign ?: TextAlign.Unspecified,
                        textIndent = TextIndent(firstLine = appearance.firstLineIndent.sp),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

fun readableEpubColor(candidate: Color, appearance: ReaderAppearance): Color {
    val light = max(candidate.luminance(), appearance.background.luminance())
    val dark = min(candidate.luminance(), appearance.background.luminance())
    return if (candidate.alpha >= 0.8f && (light + 0.05f) / (dark + 0.05f) >= 3f)
        candidate else appearance.foreground
}

fun epubSpanStyle(style: EpubTextStyle, fontSize: Int): SpanStyle = when (style) {
    EpubTextStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    EpubTextStyle.NORMAL_WEIGHT -> SpanStyle(fontWeight = FontWeight.Normal)
    EpubTextStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    EpubTextStyle.NORMAL_STYLE -> SpanStyle(fontStyle = FontStyle.Normal)
    EpubTextStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    EpubTextStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    EpubTextStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
    EpubTextStyle.HEADING -> SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = (fontSize + 4).sp)
}

/** PDF bookmarks resolve only local page destinations. URI/remote actions are never followed. */
internal fun readPdfOutline(file: File): List<com.aceinandroid.inkflow.TocEntry> =
    com.tom_roush.pdfbox.pdmodel.PDDocument.load(file,
        com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { document ->
        val first = document.documentCatalog.documentOutline?.firstChild ?: return@use emptyList()
        val pending = java.util.ArrayDeque<Pair<com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem, Int>>()
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<com.tom_roush.pdfbox.cos.COSDictionary, Boolean>())
        val entries = mutableListOf<com.aceinandroid.inkflow.TocEntry>()
        pending.add(first to 0)
        while (pending.isNotEmpty() && visited.size < 2048) {
            val (item, depth) = pending.removeLast()
            if (!visited.add(item.cosObject)) continue
            item.nextSibling?.let { pending.add(it to depth) }
            if (depth < 32) item.firstChild?.let { pending.add(it to depth + 1) }
            val page = runCatching { item.findDestinationPage(document) }.getOrNull() ?: continue
            val index = document.pages.indexOf(page)
            val title = item.title.orEmpty().trim().take(500)
            if (index in 0 until document.numberOfPages && title.isNotEmpty())
                entries += com.aceinandroid.inkflow.TocEntry(title, index, null, depth)
        }
        entries
    }
