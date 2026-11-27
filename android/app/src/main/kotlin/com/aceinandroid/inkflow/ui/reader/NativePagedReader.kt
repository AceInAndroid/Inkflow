package com.aceinandroid.inkflow.ui.reader

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aceinandroid.inkflow.ReaderAppearanceStore
import com.aceinandroid.inkflow.ReaderScriptFontFamilies
import com.aceinandroid.inkflow.applyReaderScriptFonts
import com.aceinandroid.inkflow.core.ReaderGestureArbiter.Point
import com.aceinandroid.inkflow.core.ReaderInteractionScope
import com.aceinandroid.inkflow.core.SelectionRangeController
import com.aceinandroid.inkflow.core.ReaderHighlightKind
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
import com.aceinandroid.inkflow.ui.theme.LocalEInkConfig
import com.aceinandroid.inkflow.core.ReaderTransientHighlight
import com.aceinandroid.inkflow.core.PageItem
import com.aceinandroid.inkflow.core.LineMetrics
import com.aceinandroid.inkflow.core.PaginationSummary
import com.aceinandroid.inkflow.core.TextPaginationCore
import com.aceinandroid.inkflow.core.TextSelectionRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
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
    val annotation: StoredAnnotation? = null,
)

/**
 * Builds a single unified AnnotatedString for a chapter so that true pagination
 * and cross-paragraph text selection operate seamlessly on one coordinate space.
 * Stored annotations are painted separately: editing a mark must not invalidate text layout.
 */
fun buildUnifiedChapterAnnotatedString(
    rawText: String,
    epubText: EpubText?,
    @Suppress("UNUSED_PARAMETER") annotations: List<StoredAnnotation>,
    appearance: ReaderAppearance,
): AnnotatedString {
    val source = epubText?.text ?: rawText
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
    // Optional caller identities; spread supplies its own actual pane width and mode.
    layoutMode: String = "single-page",
    columnCount: Int = 1,
    // Optional identity for externally managed font resources; imported reader fonts hash automatically.
    fontCacheIdentity: String? = null,
    anchorOffset: Int? = null,
    onAnnotationClick: (StoredAnnotation) -> Unit = {},
    spreadEnabled: Boolean = false,
    transientHighlight: ReaderTransientHighlight? = null,
    // Called after onPaginationReady. Parent updates the page and persists this exact offset,
    // rather than treating a reflow as a user turn to the viewport's starting character.
    onLayoutReflow: ((pageIndex: Int, anchorOffset: Int) -> Unit)? = null,
    onManualNavigation: () -> Unit = {},
    // Share the parent's chapter controller to opt into revision-checked edge expansion.
    selectionController: SelectionRangeController? = null,
    edgeSelectionEnabled: Boolean = false,
    interactionBookId: Long = 0L,
    gesturesEnabled: Boolean = true,
    onNarrationTap: ((TextSelectionRange) -> Unit)? = null,
) {
    val scriptFonts = rememberReaderScriptFonts(appearance, fontCacheIdentity)
    if (scriptFonts == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val annotated = remember(rawText, epubText, appearance, scriptFonts) {
        applyReaderScriptFonts(buildUnifiedChapterAnnotatedString(rawText, epubText, emptyList(), appearance), scriptFonts.families)
    }
    val annotationSpans = remember(epubText, annotations) { readerAnnotationSpans(epubText, annotations) }
    val liveAnnotations = rememberUpdatedState(annotationSpans)
    val liveTransientHighlight = rememberUpdatedState(transientHighlight)
    val annotationClick = rememberUpdatedState(onAnnotationClick)
    val highContrast = LocalEInkConfig.current.enabled
    val plainText = annotated.text
    val selectedState = rememberUpdatedState(selection)
    val selectedNow by selectedState
    val changeSelection by rememberUpdatedState(onSelectionChange)
    var viewingImage by remember(file, chapter) { mutableStateOf<Bitmap?>(null) }
    val baseStyle = TextStyle(
        fontSize = appearance.fontSize.sp,
        lineHeight = (appearance.fontSize * appearance.lineSpacing).sp,
        fontFamily = fontFamily,
        color = appearance.foreground,
    )
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val sourceIdentity = remember(file, chapter?.path, chapterIndex, plainText) {
        "${file?.absolutePath.orEmpty()}|$chapterIndex|${chapter?.path.orEmpty()}"
    }
    val visibleAnchor = remember(sourceIdentity, plainText) { ReaderPageAnchorHolder() }
    var footerProgress by remember(sourceIdentity, plainText) { mutableStateOf("") }
    val footerLocale = LocalConfiguration.current.locales[0]
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
            val bodyWidthPx = with(density) { maxWidth.toPx().roundToInt().coerceAtLeast(1) }
            val gutterPx = if (spreadEnabled) with(density) { READER_SPREAD_GUTTER_DP.dp.toPx() } else 0f
            val widthPx = if (spreadEnabled) ((bodyWidthPx - gutterPx) / 2).toInt().coerceAtLeast(1)
                else bodyWidthPx
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
            val measured = rememberNativeReaderLayout(
                textMeasurer, annotated, baseStyle, placeholders, images, appearance, widthPx, heightPx,
                sourceIdentity, "${fontCacheIdentity.orEmpty()}|${scriptFonts.identity}",
                if (spreadEnabled) READER_SPREAD_LAYOUT_MODE else layoutMode,
                if (spreadEnabled) 2 else columnCount,
            )
            if (measured == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = appearance.foreground)
                }
                return@BoxWithConstraints
            }
            val layout = measured.layout
            val mixed = measured.pagination
            // Retained layouts keep their delivered pane geometry while a new request runs.
            // This also preserves the R1 no-blank guarantee across single/spread transitions.
            val columns = if (measured.fingerprint.layoutMode == READER_SPREAD_LAYOUT_MODE) 2 else 1
            val pagination = remember(mixed.summary, columns) { readerViewportPagination(mixed.summary, columns) }
            val previousAnchor = visibleAnchor.value
            val reflowed = previousAnchor != null && previousAnchor.layoutKey != measured.key
            val explicitOffset = if (reflowed || previousAnchor == null) anchorOffset else null
            val pageIndex = anchoredReaderPage(previousAnchor, measured.key, pagination, currentPage, explicitOffset)
            val deliveredAnchor = readerVisibleAnchor(previousAnchor, measured.key, pagination, pageIndex, explicitOffset)
            LaunchedEffect(measured.key) {
                onPaginationReady(pagination)
                if ((reflowed || anchorOffset != null) && onLayoutReflow != null)
                    onLayoutReflow(pageIndex, deliveredAnchor.offset)
                else if ((reflowed || anchorOffset != null) && pageIndex != currentPage) onPageChange(pageIndex)
            }
            val deliveredProgress = remember(chapterIndex, totalChapters, deliveredAnchor.offset, plainText.length, footerLocale) {
                readerFooterProgress(chapterIndex, totalChapters, deliveredAnchor.offset, plainText.length, footerLocale)
            }
            SideEffect {
                visibleAnchor.value = deliveredAnchor
                footerProgress = deliveredProgress
            }
            val page = pagination.pages[pageIndex]
            val panes = remember(measured, pageIndex, columns) {
                readerPagePanes(layout, mixed, pageIndex, columns,
                    measured.fingerprint.viewportWidthPx.toFloat(),
                    if (columns == 2) READER_SPREAD_GUTTER_DP * measured.fingerprint.density else 0f)
            }
            fun turn(forward: Boolean) {
                // Touch turns alone pause automatic following; layout delivery never does.
                onManualNavigation()
                if (forward) {
                    if (pageIndex + 1 < pagination.pageCount) onPageChange(pageIndex + 1) else onNextChapter()
                } else {
                    if (pageIndex > 0) onPageChange(pageIndex - 1) else onPreviousChapter()
                }
            }
            val hitTest by rememberUpdatedState<(Offset) -> Int?>({ point ->
                panes.firstNotNullOfOrNull { pane ->
                    pane.sourcePoint(layout, point)?.let { source ->
                        layout.getOffsetForPosition(source).coerceIn(pane.page.slice.startOffset,
                            (pane.page.slice.endOffset - 1).coerceAtLeast(pane.page.slice.startOffset))
                    }
                }
            })
            val annotationAtPoint by rememberUpdatedState<(Offset) -> StoredAnnotation?>({ point ->
                panes.firstNotNullOfOrNull { pane ->
                    pane.sourcePoint(layout, point)?.let { source ->
                        readerAnnotationAtPosition(layout, liveAnnotations.value, source)
                    }
                }
            })
            val turnCurrentPage by rememberUpdatedState<(Boolean) -> Unit>({ forward -> turn(forward) })
            val scope = remember(sourceIdentity, plainText, measured.key, bodyWidthPx, heightPx, interactionBookId) {
                ReaderInteractionScope(interactionBookId, chapterIndex, System.nanoTime())
            }
            val rangeAuthority = selectionController ?: remember(sourceIdentity, plainText) { SelectionRangeController(plainText) }
            val handleRadius = with(density) { 8.dp.toPx() }
            val handleTouchRadius = with(density) { 24.dp.toPx() }
            val edgeBand = with(density) { 28.dp.toPx() }
            val handles = readerSelectionHandles(layout, panes, selectedNow, handleRadius)
            val liveHandles by rememberUpdatedState(handles)
            val handleOffsetAt by rememberUpdatedState<(Offset) -> Int?>({ point -> readerHandleOffsetAt(layout, panes, point) })
            var dragFocus by remember(scope) { mutableStateOf<Point?>(null) }
            val liveChrome by rememberUpdatedState(onToggleChrome)
            val edgeNavigation by rememberUpdatedState<(Int) -> Unit>({ target -> onManualNavigation(); onPageChange(target) })
            val narratorTap by rememberUpdatedState(onNarrationTap)
            val imageBitmaps = remember(sourceIdentity) { mutableMapOf<Int, Bitmap>() }
            val touchSlop = LocalViewConfiguration.current.touchSlop
            val session = remember(scope, rangeAuthority, pagination, swipeToTurnPage, density, highContrast, touchSlop) {
                ReaderCanvasGestureSession(plainText, scope, rangeAuthority, pagination.pages,
                    touchSlop.toDouble(), with(density) { 48.dp.toPx().toDouble() },
                    swipeToTurnPage, { changeSelection(it) }, { turnCurrentPage(it) }, { liveChrome() },
                    { edgeNavigation(it) }, { dragFocus = if (highContrast) null else it })
            }
            SideEffect {
                session.synchronize(selectedNow, pageIndex)
                session.policy(edgeSelectionEnabled && selectionController != null, highContrast)
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(session, lifecycleOwner, gesturesEnabled, viewingImage != null) {
                if (!gesturesEnabled || viewingImage != null) session.cancel()
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY)
                        session.cancel()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer); session.cancel() }
            }
            val downHit by rememberUpdatedState<(Offset) -> ReaderCanvasGestureSession.Hit>({ point ->
                val handle = readerHandleAt(liveHandles, point.readerPoint(), handleTouchRadius.toDouble())
                val grab = handle?.let { point - it.anchor.readerOffset() }
                val offset = hitTest(point)
                val narration = liveTransientHighlight.value?.takeIf {
                    it.kind == ReaderHighlightKind.NARRATION && it.chapterIndex == chapterIndex &&
                        it.bookId == interactionBookId && offset != null && offset in it.range.start until it.range.end
                }
                val annotation = annotationAtPoint(point)
                val image = panes.firstNotNullOfOrNull { pane ->
                    images.mapIndexedNotNull { index, image ->
                        val rect = layout.placeholderRects.getOrNull(index) ?: return@mapIndexedNotNull null
                        if (image.offset !in pane.page.slice.startOffset until pane.page.slice.endOffset) return@mapIndexedNotNull null
                        val line = layout.getLineForOffset(image.offset)
                        val item = pane.page.items.firstOrNull { it.lineIndex == line } ?: return@mapIndexedNotNull null
                        val top = rect.top + item.top - layout.getLineTop(line) - pane.pageTop
                        imageBitmaps[image.offset]?.takeIf { point.x >= pane.left + rect.left && point.x < pane.left + rect.right &&
                            point.y >= top && point.y < top + rect.height }
                    }.firstOrNull()
                }
                ReaderCanvasGestureSession.Hit(offset, handle?.endpoint,
                    handleOffset = grab?.let { delta -> { moved -> handleOffsetAt(moved.readerOffset() - delta - Offset(0f, .5f)) } },
                    narration = narration?.takeIf { narratorTap != null }?.let { highlight -> { narratorTap?.invoke(highlight.range) } },
                    image = image?.let { bitmap -> { viewingImage = bitmap } },
                    annotation = annotation?.let { mark -> { annotationClick.value(mark) } },
                    pageForward = when { point.x < bodyWidthPx * .28f -> false; point.x > bodyWidthPx * .72f -> true; else -> null })
            })
            val edgeAt by rememberUpdatedState<(Offset, SelectionRangeController.Endpoint?) -> com.aceinandroid.inkflow.core.ReaderEdgeSelectionController.Direction?>({ point, endpoint ->
                readerHandleEdge(endpoint, point.readerPoint(), bodyWidthPx.toDouble(), heightPx.toDouble(), edgeBand.toDouble())
            })
            val bodyLayer = if (!highContrast) rememberGraphicsLayer() else null
            Box(Modifier.fillMaxSize().semantics {
                val excerpt = plainText.substring(page.startOffset, page.endOffset)
                text = AnnotatedString(excerpt)
                editableText = AnnotatedString(plainText)
                contentDescription = "$chapterTitle, ${pageIndex + 1}/${pagination.pageCount}"
                textSelectionRange = selection?.let { TextRange(it.start, it.end) } ?: TextRange.Zero
                setSelection { start, end, _ ->
                    session.cancel()
                    changeSelection(if (start != end) TextSelectionRange(start, end).normalized() else null)
                    true
                }
            }.readerCanvasGestureInput(session, gesturesEnabled && viewingImage == null,
                { downHit(it) }, { hitTest(it) }, { point, endpoint -> edgeAt(point, endpoint) }), contentAlignment = AbsoluteAlignment.TopLeft) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    if (bodyLayer != null && dragFocus != null) {
                        bodyLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(bodyLayer)
                    } else drawContent()
                }) {
                    panes.forEach { pane ->
                        val slice = pane.page.slice
                        Box(Modifier.absoluteOffset(x = with(density) { pane.left.toDp() })
                            .width(with(density) { pane.width.toDp() }).fillMaxHeight().clipToBounds(),
                            contentAlignment = AbsoluteAlignment.TopLeft) {
                            ReaderAnnotationLayer(layout, pane.segments, liveAnnotations, highContrast, appearance.background,
                                slice.startOffset, slice.endOffset, underlay = true)
                            ReaderSelectionUnderlay(layout, pane.segments, selectedState, selectionColor)
                            ReaderTextContent(layout, pane.segments)
                            ReaderAnnotationLayer(layout, pane.segments, liveAnnotations, highContrast, appearance.background,
                                slice.startOffset, slice.endOffset, underlay = false)
                            images.forEachIndexed { index, image ->
                                val rect = layout.placeholderRects.getOrNull(index)
                                if (file != null && chapter != null && rect != null &&
                                    image.offset in slice.startOffset until slice.endOffset) {
                                    val imageLine = layout.getLineForOffset(image.offset)
                                    val lineTop = pane.page.items.firstOrNull { it.lineIndex == imageLine }?.top ?: pane.pageTop
                                    val imageTop = rect.top + lineTop - layout.getLineTop(imageLine) - pane.pageTop
                                    val bitmap by produceState<Bitmap?>(null, file, chapter.path, image.href) {
                                        value = withContext(Dispatchers.IO) { loadReaderImage(file, chapter, image.href) }
                                    }
                                    DisposableEffect(image.offset, bitmap) {
                                        bitmap?.let { imageBitmaps[image.offset] = it }
                                        onDispose { imageBitmaps.remove(image.offset) }
                                    }
                                    Box(Modifier.absoluteOffset(with(density) { rect.left.toDp() }, with(density) { imageTop.toDp() })
                                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                                        .semantics {
                                            onClick {
                                                session.cancel()
                                                if (selectedNow != null) { changeSelection(null); true }
                                                else bitmap?.let { viewingImage = it; true } ?: false
                                            }
                                        }, contentAlignment = Alignment.Center) {
                                        if (bitmap != null) Image(bitmap!!.asImageBitmap(), image.alt.ifBlank {
                                            stringResource(R.string.migration_image)
                                        }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                        else Text(image.alt.ifBlank { stringResource(R.string.migration_image_unavailable) },
                                            color = appearance.foreground)
                                    }
                                }
                            }
                            ReaderTransientHighlightLayer(layout, pane.segments, liveTransientHighlight, highContrast,
                                appearance.background, slice.startOffset, slice.endOffset)
                        }
                    }
                }
                ReaderSelectionHandles(handles, handleRadius, appearance.foreground,
                    dragFocus, bodyLayer, highContrast, with(density) { 44.dp.toPx() },
                    with(density) { 24.dp.toPx() }, appearance.background)
            }
        }
        if (appearance.showFooter) Row(
            Modifier.fillMaxWidth().padding(horizontal = appearance.pageMargin.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ReaderRunningStatus(appearance, footerProgress)
            Text("${currentPage + 1}", color = appearance.foreground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall)
        }
    }
    viewingImage?.let { bitmap -> ImageViewerDialog(bitmap, onDismiss = { viewingImage = null }) }
}

private data class ReaderRenderScriptFonts(val families: ReaderScriptFontFamilies, val identity: String)

@Composable
private fun rememberReaderScriptFonts(appearance: ReaderAppearance, fileIdentity: String? = null): ReaderRenderScriptFonts? {
    val context = LocalContext.current
    val store = remember(context) { ReaderAppearanceStore(context) }
    val resolved by produceState<ReaderRenderScriptFonts?>(null, store, appearance.cjkFont, appearance.latinFont, fileIdentity) {
        value = withContext(Dispatchers.IO) {
            ReaderRenderScriptFonts(store.scriptFontFamilies(appearance), store.scriptFontCacheIdentity(appearance))
        }
    }
    return resolved
}

private class ReaderPageAnchorHolder(var value: ReaderVisiblePageAnchor? = null)

private data class ReaderLayoutDelivery(val sourceIdentity: String, val value: NativeReaderLayout)

@Composable
private fun rememberNativeReaderLayout(
    measurer: TextMeasurer,
    annotated: AnnotatedString,
    style: TextStyle,
    placeholders: List<AnnotatedString.Range<Placeholder>>,
    images: List<com.aceinandroid.inkflow.EpubImage>,
    appearance: ReaderAppearance,
    widthPx: Int,
    heightPx: Float,
    sourceIdentity: String,
    fontCacheIdentity: String?,
    layoutMode: String,
    columnCount: Int,
): NativeReaderLayout? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    val configuration = LocalConfiguration.current
    val locales = configuration.locales.toLanguageTags()
    val platform = "${Build.FINGERPRINT}:${Build.VERSION.SDK_INT}:" +
        if (Build.VERSION.SDK_INT >= 31) configuration.fontWeightAdjustment else 0
    val cache = remember(context.cacheDir) {
        NativeReaderPaginationCaches.forDirectory(File(context.cacheDir, "native-pagination-v1"))
    }
    // Neither the current page nor the selected range is a layout key. Hashing, reading disk
    // and pagination therefore do not run on the page-turn path.
    val request = remember(annotated, style, placeholders, images, appearance, widthPx, heightPx,
        density, direction, resolver, locales, platform, sourceIdentity, fontCacheIdentity, layoutMode, columnCount) { Any() }
    var fontRevision by remember(request) { mutableIntStateOf(0) }
    val delivery by produceState<ReaderLayoutDelivery?>(null, request, fontRevision) {
        val fingerprint = readerLayoutFingerprint(annotated, style, placeholders, images, appearance,
            widthPx, heightPx, density.density, density.fontScale, direction.name, locales, platform,
            sourceIdentity, File(context.filesDir, "fonts"), fontCacheIdentity, layoutMode, columnCount)
        var freshPagination: Lazy<com.aceinandroid.inkflow.core.MixedPagination>? = null
        publishReaderLayoutWhileCaching<TextLayoutResult, NativeReaderLayout>(
            obtain = { measured ->
                cache.obtain(fingerprint, resolver,
                    measure = {
                        measurer.measure(annotated, style, placeholders = placeholders,
                            constraints = Constraints(maxWidth = widthPx), density = density,
                            layoutDirection = direction, fontFamilyResolver = resolver).also { layout ->
                            // Both consumers evaluate this on Default; even a cache miss paginates only once.
                            freshPagination = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                                val spacing = appearance.paragraphSpacing * density.density
                                TextPaginationCore.paginateItems(readerPageItems(layout, images, spacing), heightPx, annotated.length)
                            }
                            measured(layout)
                        }
                    },
                    paginate = { requireNotNull(freshPagination).value })
            },
            fresh = { layout ->
                val mixed = withContext(Dispatchers.Default) { requireNotNull(freshPagination).value }
                val key = paginationDigest { writeCacheString(fingerprint.canonicalForm()) }
                NativeReaderLayout(key, layout, mixed, ReaderLayoutCacheOrigin.MEASURED, fingerprint)
            },
            publish = { result -> value = ReaderLayoutDelivery(sourceIdentity, result) },
        )
    }
    // produceState retains its last delivery while its keyed producer restarts. Locale,
    // viewport and font changes must keep that live layout (and reader semantics) visible
    // until the replacement is ready. Never display another chapter or changed source text.
    val current = delivery?.takeIf {
        it.sourceIdentity == sourceIdentity && it.value.layout.layoutInput.text.text == annotated.text
    }?.value
    // Resolve stale fonts without removing the currently readable fallback. Cache.obtain
    // rejects stale memory entries and the next delivery is swapped in atomically; the
    // caller maps its latest visible character offset into the replacement pagination.
    val staleFonts = current?.layout?.multiParagraph?.intrinsics?.hasStaleResolvedFonts == true
    LaunchedEffect(request, staleFonts) { if (staleFonts) fontRevision++ }
    return current
}

@Composable
private fun ReaderTextContent(layout: TextLayoutResult, segments: List<ReaderDrawSegment>) {
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        segments.forEach { segment ->
            clipRect(left = 0f, top = segment.top.coerceAtLeast(0f), right = size.width,
                bottom = segment.bottom.coerceAtMost(size.height)) {
                translate(top = segment.translationY) { drawText(layout) }
            }
        }
    }
}

@Composable
private fun ReaderAnnotationLayer(
    layout: TextLayoutResult, segments: List<ReaderDrawSegment>, spans: State<List<AnnotationSpan>>,
    highContrast: Boolean, background: Color, start: Int, end: Int, underlay: Boolean,
) {
    Canvas(Modifier.fillMaxSize()) {
        segments.forEach { segment ->
            clipRect(left = 0f, top = segment.top.coerceAtLeast(0f), right = size.width,
                bottom = segment.bottom.coerceAtMost(size.height)) {
                translate(top = segment.translationY) {
                    drawReaderAnnotations(layout, spans.value, highContrast, background, underlay,
                        visibleStart = start, visibleEnd = end,
                        firstLine = segment.firstLine, lastLine = segment.lastLine)
                }
            }
        }
    }
}

@Composable
private fun ReaderTransientHighlightLayer(
    layout: TextLayoutResult, segments: List<ReaderDrawSegment>, highlight: State<ReaderTransientHighlight?>,
    highContrast: Boolean, background: Color, start: Int, end: Int,
) {
    Canvas(Modifier.fillMaxSize()) {
        segments.forEach { segment ->
            clipRect(left = 0f, top = segment.top.coerceAtLeast(0f), right = size.width,
                bottom = segment.bottom.coerceAtMost(size.height)) {
                translate(top = segment.translationY) {
                    drawReaderTransientHighlight(layout, highlight.value, highContrast, background,
                        visibleStart = start, visibleEnd = end,
                        firstLine = segment.firstLine, lastLine = segment.lastLine)
                }
            }
        }
    }
}

@Composable
private fun ReaderSelectionUnderlay(
    layout: TextLayoutResult, segments: List<ReaderDrawSegment>,
    selection: State<TextSelectionRange?>, color: Color,
) {
    Canvas(Modifier.fillMaxSize()) {
        // Read only in draw: moving a handle invalidates this layer rather than text layout.
        val range = selection.value?.normalized() ?: return@Canvas
        val start = range.start.coerceIn(0, layout.layoutInput.text.length)
        val end = range.end.coerceIn(start, layout.layoutInput.text.length)
        if (start == end) return@Canvas
        val path = layout.getPathForRange(start, end)
        segments.forEach { segment ->
            clipRect(left = 0f, top = segment.top.coerceAtLeast(0f), right = size.width,
                bottom = segment.bottom.coerceAtMost(size.height)) {
                translate(top = segment.translationY) { drawPath(path, color) }
            }
        }
    }
}

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
    onAnnotationClick: (StoredAnnotation) -> Unit = {},
    transientHighlight: ReaderTransientHighlight? = null,
    onManualNavigation: () -> Unit = {},
    fontCacheIdentity: String? = null,
) {
    val scriptFonts = rememberReaderScriptFonts(appearance, fontCacheIdentity)
    if (scriptFonts == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val listState = rememberLazyListState()
    val manualNavigation = rememberUpdatedState(onManualNavigation)
    LaunchedEffect(listState) {
        // LazyList emits DragInteraction only for touch dragging, not scrollToItem,
        // animateScrollToItem, initial restoration or programmatic playback following.
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) manualNavigation.value()
        }
    }
    val blocks = remember(epubText, rawText) { epubText?.renderBlocks() }

    val annotationSpans = remember(epubText, annotations) { readerAnnotationSpans(epubText, annotations) }
    val liveAnnotations = rememberUpdatedState(annotationSpans)
    val liveTransientHighlight = rememberUpdatedState(transientHighlight)
    val annotationClick = rememberUpdatedState(onAnnotationClick)
    val highContrast = LocalEInkConfig.current.enabled

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
                        val imageModifier = Modifier.size(width, height).drawWithContent {
                            drawContent()
                            val range = liveTransientHighlight.value?.range
                            if (range != null && image.offset >= range.start && image.offset < range.end)
                                drawReaderTransientRect(highContrast, appearance.background)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = image.alt.ifBlank { null },
                                modifier = imageModifier,
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Box(
                                imageModifier.background(Color(0xFFE9EDE8)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(image.alt, maxLines = 2, color = appearance.foreground)
                            }
                        }
                    }
                } else {
                    val annotated = remember(epubText, block, appearance.fontSize, appearance.foreground, appearance.background, scriptFonts) {
                        val original = buildAnnotatedString {
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

                        }
                        applyReaderScriptFonts(original, scriptFonts.families)
                    }
                    var blockSelection by remember(epubText, block) { mutableStateOf(TextRange.Zero) }
                    val fieldValue = TextFieldValue(annotated, blockSelection)
                    var textLayout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
                    val selectionActive = rememberUpdatedState(!fieldValue.selection.collapsed)
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { changed ->
                            blockSelection = changed.selection
                            val start = min(changed.selection.start, changed.selection.end)
                            val end = max(changed.selection.start, changed.selection.end)
                            onSelectionChange(if (start < end) TextSelectionRange(block.visibleStart + start, block.visibleStart + end) else null)
                        },
                        readOnly = true,
                        onTextLayout = { textLayout = it },
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
                        modifier = Modifier.fillMaxWidth()
                            .readerAnnotationTap({ textLayout }, liveAnnotations, block.visibleStart,
                                selectionActive, annotationClick)
                            .drawBehind {
                                textLayout?.let { drawReaderAnnotations(it, liveAnnotations.value,
                                    highContrast, appearance.background, underlay = true,
                                    sourceOffset = block.visibleStart) }
                            }
                            .drawWithContent {
                                drawContent()
                                textLayout?.let { drawReaderAnnotations(it, liveAnnotations.value,
                                    highContrast, appearance.background, underlay = false,
                                    sourceOffset = block.visibleStart) }
                                textLayout?.let { drawReaderTransientHighlight(it, liveTransientHighlight.value,
                                    highContrast, appearance.background, sourceOffset = block.visibleStart) }
                            },
                    )
                }
            }
        } else {
            item(key = "plain_text_body") {
                val annotated = remember(rawText, scriptFonts) { applyReaderScriptFonts(AnnotatedString(rawText), scriptFonts.families) }
                var plainSelection by remember(rawText) { mutableStateOf(TextRange.Zero) }
                val fieldValue = TextFieldValue(annotated, plainSelection)
                var textLayout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { changed ->
                        plainSelection = changed.selection
                        val start = min(changed.selection.start, changed.selection.end)
                        val end = max(changed.selection.start, changed.selection.end)
                        onSelectionChange(if (start < end) TextSelectionRange(start, end) else null)
                    },
                    readOnly = true,
                    onTextLayout = { textLayout = it },
                    textStyle = TextStyle(
                        fontSize = appearance.fontSize.sp,
                        lineHeight = (appearance.fontSize * appearance.lineSpacing).sp,
                        fontFamily = fontFamily,
                        color = appearance.foreground,
                        textAlign = appearance.textAlign ?: TextAlign.Unspecified,
                        textIndent = TextIndent(firstLine = appearance.firstLineIndent.sp),
                    ),
                    modifier = Modifier.fillMaxWidth().drawWithContent {
                        drawContent()
                        textLayout?.let { drawReaderTransientHighlight(it, liveTransientHighlight.value,
                            highContrast, appearance.background) }
                    },
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
