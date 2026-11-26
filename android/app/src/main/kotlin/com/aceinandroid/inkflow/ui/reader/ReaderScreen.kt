package com.aceinandroid.inkflow.ui.reader

import androidx.activity.compose.LocalActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aceinandroid.inkflow.AnnotationUndoResult
import com.aceinandroid.inkflow.BookDocument
import com.aceinandroid.inkflow.BookParser
import com.aceinandroid.inkflow.BookSearchHit
import com.aceinandroid.inkflow.BookSearcher
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.DeviceReadingPosition
import com.aceinandroid.inkflow.EpubCfi
import com.aceinandroid.inkflow.EpubText
import com.aceinandroid.inkflow.Fb2Book
import com.aceinandroid.inkflow.MainActivity
import com.aceinandroid.inkflow.MarkdownBook
import com.aceinandroid.inkflow.MobiBook
import com.aceinandroid.inkflow.NarrationPlayback
import com.aceinandroid.inkflow.NarrationPlaybackService
import com.aceinandroid.inkflow.NarrationState
import com.aceinandroid.inkflow.NativeVocabularyStore
import com.aceinandroid.inkflow.ProgressSyncWorker
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ReaderAppearance
import com.aceinandroid.inkflow.ReaderAppearanceDialog
import com.aceinandroid.inkflow.ReaderAppearanceStore
import com.aceinandroid.inkflow.ReaderControlsStore
import com.aceinandroid.inkflow.ReaderNarrationDialog
import com.aceinandroid.inkflow.ReaderSearchDialog
import com.aceinandroid.inkflow.ReaderTocDialog
import com.aceinandroid.inkflow.ReadingNoteWorkspaceStore
import com.aceinandroid.inkflow.ReadingSessionTracker
import com.aceinandroid.inkflow.StoredAnnotation
import com.aceinandroid.inkflow.StoredBook
import com.aceinandroid.inkflow.StoredBookmark
import com.aceinandroid.inkflow.TxtChapterRuleStore
import com.aceinandroid.inkflow.VocabularyEnrichmentWorker
import com.aceinandroid.inkflow.pdfPageCount
import com.aceinandroid.inkflow.renderPdfPage
import com.aceinandroid.inkflow.ui.ai.AiReadingSheet
import com.aceinandroid.inkflow.ui.ai.ReadingWorkspaceHost
import com.aceinandroid.inkflow.ai.NativeReadingActions
import com.aceinandroid.inkflow.NativeSyncIntegration
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import com.aceinandroid.inkflow.ui.ai.LookupSheet
import com.aceinandroid.inkflow.ui.theme.InkflowPalette
import com.aceinandroid.inkflow.ui.theme.LocalEInkConfig
import com.aceinandroid.inkflow.core.AiQuickAction
import com.aceinandroid.inkflow.core.PaginationSummary
import com.aceinandroid.inkflow.core.ReaderEngineMode
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.SentenceBoundaryResolver
import com.aceinandroid.inkflow.core.SelectionRangeController
import com.aceinandroid.inkflow.core.TextSelectionRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val AnnotationColors = listOf("66CCFF", "FF0000", "00FF00", "EB3BFF", "FFD700")
private val forwardPageKeys = setOf(
    KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE,
)
private val backwardPageKeys = setOf(
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
)

private data class VocabularyDraft(
    val word: String,
    val sentence: String,
    val cfi: String,
    val chapterTitle: String,
    val initialDefinition: String = "",
)

@Composable
fun ReaderScreen(
    book: StoredBook,
    store: BookStore,
    snackbar: SnackbarHostState,
    initialOffset: Int?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appearanceStore = remember(context) { ReaderAppearanceStore(context) }
    var appearance by remember { mutableStateOf(appearanceStore.load()) }
    ReaderSystemBars(appearance)
    MaterialTheme(colorScheme = appearance.colors) {
        ReaderContent(
            book = book,
            store = store,
            snackbar = snackbar,
            onBack = onBack,
            appearance = appearance,
            initialOffset = initialOffset,
            onAppearanceChange = { updated ->
                appearance = updated
                appearanceStore.save(updated)
            },
        )
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ReaderSystemBars(appearance: ReaderAppearance) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val previousStatus = window.statusBarColor
        val previousNavigation = window.navigationBarColor
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousLightStatus = controller.isAppearanceLightStatusBars
        val previousLightNavigation = controller.isAppearanceLightNavigationBars
        onDispose {
            window.statusBarColor = previousStatus
            window.navigationBarColor = previousNavigation
            controller.isAppearanceLightStatusBars = previousLightStatus
            controller.isAppearanceLightNavigationBars = previousLightNavigation
        }
    }
    SideEffect {
        window.statusBarColor = appearance.background.toArgb()
        window.navigationBarColor = appearance.background.toArgb()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !appearance.isDark
        controller.isAppearanceLightNavigationBars = !appearance.isDark
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    book: StoredBook,
    store: BookStore,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    appearance: ReaderAppearance,
    initialOffset: Int?,
    onAppearanceChange: (ReaderAppearance) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val eInk = LocalEInkConfig.current
    val controlsStore = remember(context) { ReaderControlsStore(context) }
    var swipeToTurnPage by remember { mutableStateOf(controlsStore.swipeToTurnPage()) }
    var fullScreenImage by remember(book.id) { mutableStateOf<Bitmap?>(null) }
    val readerFontFamily = remember(context, appearance.font, appearance.customFontId) {
        ReaderAppearanceStore(context).fontFamily(appearance)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var readingGeneration by remember(book.id) { mutableStateOf<Long?>(null) }
    var pendingPositionWrite by remember(book.id) { mutableStateOf<Job?>(null) }
    val readingSession = remember(book.id) { ReadingSessionTracker(book.id, store) }
    val narrator = remember(book.id) { NarrationPlayback.obtain(context, book.id) }
    val noReadableText = stringResource(R.string.no_readable_text)

    DisposableEffect(narrator) { onDispose { NarrationPlayback.releaseIfIdle(book.id) } }
    DisposableEffect(lifecycleOwner, readingSession) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { readingGeneration = NativeSyncIntegration.readingStarted(); readingSession.resume() }
                Lifecycle.Event.ON_PAUSE -> readingSession.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { readingGeneration = NativeSyncIntegration.readingStarted(); readingSession.resume() }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            readingSession.close()
            val pending = pendingPositionWrite
            val generation = readingGeneration
            if (pending == null) NativeSyncIntegration.readingSaved(generation = generation)
            else pending.invokeOnCompletion { NativeSyncIntegration.readingSaved(generation = generation); ProgressSyncWorker.enqueue(context) }
        }
    }

    var engineMode by remember(book.id) {
        mutableStateOf(
            if (book.format == "epub" && isFixedLayoutEpub(book.file)) ReaderEngineMode.WEBVIEW_FALLBACK
            else controlsStore.readerEngineMode(),
        )
    }
    var chromeVisible by remember(book.id) { mutableStateOf(true) }
    var document by remember(book.id) { mutableStateOf<BookDocument?>(null) }
    var text by remember(book.id) { mutableStateOf("") }
    var readable by remember(book.id) { mutableStateOf<EpubText?>(null) }
    var pdfPage by remember(book.id) { mutableStateOf<Bitmap?>(null) }
    var pdfTextMode by remember(book.id) { mutableStateOf(book.format == "pdf" && initialOffset != null) }
    var pdfTextLoading by remember(book.id) { mutableStateOf(false) }
    var position by remember(book.id) { mutableIntStateOf(book.position) }
    var pageCount by remember(book.id) { mutableIntStateOf(0) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var initialChapterLoaded by remember(book.id) { mutableStateOf(false) }
    var loadedChapter by remember(book.id) { mutableIntStateOf(-1) }

    // Pagination & offset state
    var pagination by remember(book.id) { mutableStateOf<PaginationSummary?>(null) }
    var currentPage by remember(book.id) { mutableIntStateOf(0) }
    var openPreviousAtEnd by remember(book.id) { mutableStateOf(false) }
    var resumeOffset by remember(book.id) { mutableStateOf<Int?>(null) }
    var pendingCfi by remember(book.id) { mutableStateOf<String?>(null) }
    var pendingOffset by remember(book.id) { mutableStateOf(initialOffset) }
    var pendingFragment by remember(book.id) { mutableStateOf<String?>(null) }
    var currentCfi by remember(book.id) { mutableStateOf(book.cfi) }
    var currentOffset by remember(book.id) { mutableIntStateOf(0) }
    var directlySelectedEnd by remember(book.id, position) { mutableIntStateOf(0) }

    // Selection, AI, Lookup, Annotations, Bookmarks, Dialogs
    var selection by remember(book.id) { mutableStateOf<TextSelectionRange?>(null) }
    var webViewSelectedText by remember(book.id) { mutableStateOf<String?>(null) }
    var selectionMenu by remember(book.id) { mutableStateOf(false) }
    var aiSheetOpen by remember(book.id) { mutableStateOf(false) }
    var aiQuickAction by remember(book.id) { mutableStateOf<AiQuickAction?>(null) }
    var aiTranslation by remember(book.id) { mutableStateOf<com.aceinandroid.inkflow.core.translate.TranslationRequest?>(null) }
    var referenceReturn by remember(book.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var lookupQuery by remember(book.id) { mutableStateOf<String?>(null) }

    var bookmarks by remember(book.id) { mutableStateOf<List<StoredBookmark>>(emptyList()) }
    var annotations by remember(book.id) { mutableStateOf<List<StoredAnnotation>>(emptyList()) }
    var vocabularyDraft by remember(book.id) { mutableStateOf<VocabularyDraft?>(null) }
    var vocabularySaving by remember(book.id) { mutableStateOf(false) }
    var annotationDialog by remember(book.id) { mutableStateOf(false) }
    var annotationsDialog by remember(book.id) { mutableStateOf(false) }
    var editingAnnotation by remember(book.id) { mutableStateOf<StoredAnnotation?>(null) }
    var draftType by remember(book.id) { mutableStateOf("highlight") }
    var draftColor by remember(book.id) { mutableStateOf("66CCFF") }
    var draftNote by remember(book.id) { mutableStateOf("") }
    var draftCfi by remember(book.id) { mutableStateOf("") }
    var draftContent by remember(book.id) { mutableStateOf("") }
    var draftChapter by remember(book.id) { mutableStateOf("") }
    var annotationSaving by remember(book.id) { mutableStateOf(false) }
    var bookmarkDialog by remember(book.id) { mutableStateOf(false) }
    var bookmarkSaving by remember(book.id) { mutableStateOf(false) }
    var tocDialog by remember(book.id) { mutableStateOf(false) }
    var searchDialog by remember(book.id) { mutableStateOf(false) }
    var searchQuery by remember(book.id) { mutableStateOf("") }
    var searchResults by remember(book.id) { mutableStateOf<List<BookSearchHit>>(emptyList()) }
    var searching by remember(book.id) { mutableStateOf(false) }
    var appearanceDialog by remember(book.id) { mutableStateOf(false) }
    var narrationDialog by remember(book.id) { mutableStateOf(false) }
    var preparingSpeech by remember(book.id) { mutableStateOf(false) }
    var speechRequest by remember(book.id) { mutableIntStateOf(0) }
    var autoNarrateTarget by remember(book.id) { mutableStateOf<Int?>(null) }
    var remoteOffer by remember(book.id) { mutableStateOf<DeviceReadingPosition?>(null) }
    var remoteOfferChecked by remember(book.id) { mutableStateOf(false) }
    var moreMenu by remember(book.id) { mutableStateOf(false) }

    val total = if (book.format == "pdf") pageCount else document?.chapters?.size ?: 0

    val activeSourceText: String = readable?.text ?: text
    val selectionController = remember(book.id, position, activeSourceText) { SelectionRangeController(activeSourceText) }
    LaunchedEffect(selection, selectionController) {
        if (selection == null) selectionController.clear()
        else if (selectionController.snapshot?.range != selection) selectionController.begin(selection!!, currentPage)
    }
    val activeSelectedString: String? = remember(selection, webViewSelectedText, activeSourceText) {
        if (!webViewSelectedText.isNullOrBlank()) webViewSelectedText
        else selection?.let { sel ->
            val norm = sel.normalized()
            val s = norm.start.coerceIn(0, activeSourceText.length)
            val e = norm.end.coerceIn(s, activeSourceText.length)
            if (e > s) activeSourceText.substring(s, e) else null
        }
    }

    fun persistCurrentOffset(offset: Int): Job? {
        currentOffset = offset
        val chapters = document?.chapters ?: return null
        if (chapters.isEmpty() || (book.format != "pdf" && loadedChapter != position)) return null
        val chapterIndex = position
        val body = readable
        // Read the state cells at invocation, including callbacks retained by Compose effects.
        val textLength = (body?.text ?: text).length.coerceAtLeast(1)
        val cfi = if (book.format == "epub") {
            val base = chapters.getOrNull(chapterIndex)?.baseCfi ?: return null
            body?.cfiAt(base, offset) ?: return null
        } else null
        if (cfi != null) currentCfi = cfi
        val previousWrite = pendingPositionWrite
        return lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            previousWrite?.join()
            runCatching {
                if (cfi != null) {
                    val progress = (chapterIndex + offset.toDouble() / body!!.text.length.coerceAtLeast(1)) / chapters.size
                    store.savePosition(book.id, chapterIndex, cfi, progress)
                } else if (book.format != "pdf") {
                    val progress = (chapterIndex + offset.toDouble() / textLength) / chapters.size
                    store.saveTextPosition(book.id, chapterIndex, offset, progress)
                }
            }
        }.also { pendingPositionWrite = it }
    }

    val onChapterFinished: () -> Unit = {
        val next = position + 1
        if (narrator.continuous && book.format != "pdf" && next < (document?.chapters?.size ?: 0)) {
            autoNarrateTarget = next
            position = next
        }
    }

    // Stop narration on chapter change
    LaunchedEffect(book.id, position) {
        if (autoNarrateTarget != null && autoNarrateTarget != position) autoNarrateTarget = null
        loadedChapter = -1
        speechRequest++
        preparingSpeech = false
        narrator.stop()
    }

    // Inspect book metadata & chapters
    LaunchedEffect(book.id) {
        try {
            val inspected = withContext(Dispatchers.IO) {
                BookParser.inspect(
                    book.file,
                    book.title,
                    if (book.format == "txt") TxtChapterRuleStore(context).ruleForBook(book.id) else null,
                )
            }
            if (book.format == "epub") {
                position = EpubCfi.chapterIndex(book.cfi, inspected.chapters) ?: position
            }
            document = inspected
            bookmarks = withContext(Dispatchers.IO) { store.bookmarks(book.id) }
            annotations = withContext(Dispatchers.IO) { store.annotations(book.id) }
            if (book.format == "pdf") {
                pageCount = withContext(Dispatchers.IO) { pdfPageCount(book.file) }
                document = inspected.copy(toc = withContext(Dispatchers.IO) {
                    runCatching { readPdfOutline(book.file) }.getOrDefault(emptyList())
                })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Cannot open book"
        }
    }

    // Load chapter content
    LaunchedEffect(book.id, position, document, pageCount, pdfTextMode) {
        val chapters = document?.chapters ?: return@LaunchedEffect
        if (book.format != "pdf" && chapters.isEmpty()) return@LaunchedEffect
        try {
            selection = null
            webViewSelectedText = null
            currentPage = 0
            pagination = null
            if (book.format == "pdf") {
                if (pageCount > 0) withContext(Dispatchers.IO) {
                    store.savePosition(book.id, position, progress = (position + 1).toDouble() / pageCount)
                }
                if (pageCount > 0 && pdfTextMode) {
                    pdfPage = null
                    pdfTextLoading = true
                    text = withContext(Dispatchers.IO) { BookSearcher.readPdfPageText(book.file, position.coerceIn(0, pageCount - 1)) }
                    pdfTextLoading = false
                    resumeOffset = pendingOffset
                    pendingOffset = null
                } else if (pageCount > 0) {
                    pdfPage = null
                    pdfPage = withContext(Dispatchers.IO) { renderPdfPage(book.file, position.coerceIn(0, pageCount - 1)) }
                }
            } else {
                val chapter = chapters[position.coerceIn(chapters.indices)]
                val chapterBaseCfi = chapter.baseCfi
                if (book.format == "epub") {
                    val loaded = withContext(Dispatchers.IO) { BookParser.readEpubText(book.file, chapter) }
                    readable = loaded
                    text = loaded.text
                    resumeOffset = resolveEpubResumeOffset(
                        loaded, pendingCfi, pendingFragment, pendingOffset,
                        book.cfi.takeIf { !initialChapterLoaded },
                    )
                    pendingCfi = null
                    pendingFragment = null
                    pendingOffset = null
                    currentOffset = resumeOffset ?: 0
                    currentCfi = chapterBaseCfi?.let { loaded.cfiAt(it, currentOffset) }.orEmpty()
                    if (chapterBaseCfi != null) {
                        val cfi = loaded.cfiAt(chapterBaseCfi, currentOffset)
                        if (cfi != null && (initialChapterLoaded || book.cfi.isBlank())) withContext(Dispatchers.IO) {
                            val progress = (position + currentOffset.toDouble() / loaded.text.length.coerceAtLeast(1)) / chapters.size
                            store.savePosition(book.id, position, cfi, progress)
                        }
                    }
                } else {
                    if (book.format in setOf("mobi", "azw3", "md", "markdown", "fb2")) {
                        val loaded = withContext(Dispatchers.IO) {
                            when (book.format) {
                                "md", "markdown" -> MarkdownBook.readChapterText(book.file, chapter)
                                "fb2" -> Fb2Book.readChapterText(book.file, chapter)
                                else -> MobiBook.readChapterText(book.file, chapter)
                            }
                        }
                        readable = loaded
                        text = loaded.text
                        resumeOffset = pendingFragment?.let(loaded::anchorOffset) ?: pendingOffset
                            ?: if (!initialChapterLoaded) withContext(Dispatchers.IO) { store.textOffset(book.id, position) } else null
                        currentOffset = resumeOffset ?: 0
                    } else {
                        readable = null
                        text = withContext(Dispatchers.IO) { BookParser.readChapter(book.file, chapter) }
                        resumeOffset = pendingOffset ?: if (book.format == "txt" && !initialChapterLoaded) {
                            withContext(Dispatchers.IO) { store.textOffset(book.id, position) }
                        } else null
                        currentOffset = resumeOffset ?: 0
                    }
                    pendingOffset = null
                    pendingFragment = null
                    withContext(Dispatchers.IO) {
                        store.saveTextPosition(
                            book.id,
                            position,
                            currentOffset,
                            (position + currentOffset.toDouble() / text.length.coerceAtLeast(1)) / chapters.size,
                        )
                    }
                }
                initialChapterLoaded = true
                loadedChapter = position
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            pdfTextLoading = false
            error = failure.message ?: "Cannot read chapter"
        }
    }

    // Apply resume offset or open-at-end once pagination is measured
    LaunchedEffect(pagination, resumeOffset, openPreviousAtEnd, loadedChapter, position) {
        if (book.format != "pdf" && loadedChapter != position) return@LaunchedEffect
        val summary = pagination ?: return@LaunchedEffect
        if (openPreviousAtEnd) {
            val lastIdx = (summary.pageCount - 1).coerceAtLeast(0)
            currentPage = lastIdx
            // The EPUB load also supplies an opening offset. Consume it here so a
            // second effect cannot override the explicit previous-chapter end.
            resumeOffset = null
            openPreviousAtEnd = false
            val sliceOffset = summary.pages.getOrNull(lastIdx)?.startOffset ?: 0
            persistCurrentOffset(sliceOffset)
        } else if (resumeOffset != null) {
            val targetPage = summary.pageIndexForOffset(resumeOffset!!)
            currentPage = targetPage
            resumeOffset = null
        }
    }

    // Remote progress sync offer
    LaunchedEffect(book.id, loadedChapter, document) {
        val chapters = document?.chapters ?: return@LaunchedEffect
        if (book.format != "epub" || loadedChapter < 0 || remoteOfferChecked) return@LaunchedEffect
        val candidate = withContext(Dispatchers.IO) { store.farthestOtherPosition(book.id) }
        remoteOfferChecked = true
        if (candidate != null && EpubCfi.chapterIndex(candidate.cfi, chapters) != null) {
            remoteOffer = candidate
        }
    }

    // Continuous narration
    LaunchedEffect(autoNarrateTarget, loadedChapter) {
        val target = autoNarrateTarget ?: return@LaunchedEffect
        if (target != position || loadedChapter != target) return@LaunchedEffect
        autoNarrateTarget = null
        if (activeSourceText.replace('\uFFFC', ' ').isBlank()) onChapterFinished()
        else {
            narrator.play(activeSourceText, onFinished = onChapterFinished)
            if (narrator.state in setOf(NarrationState.PLAYING, NarrationState.PREPARING)) {
                NarrationPlaybackService.start(context, book.title)
            }
        }
    }

    // Book search
    LaunchedEffect(book.id, document, searchDialog, searchQuery) {
        val inspected = document
        if (!searchDialog || searchQuery.isBlank() || inspected == null) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searchResults = emptyList()
        searching = true
        try {
            delay(250)
            searchResults = withContext(Dispatchers.IO) {
                val searchContext = currentCoroutineContext()
                BookSearcher.search(book.file, inspected, searchQuery) { searchContext.ensureActive() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            snackbar.showSnackbar(context.getString(R.string.language_error_search))
        } finally {
            searching = false
        }
    }

    // Hardware & volume key turning
    val activity = LocalActivity.current as? MainActivity
    val turnPage: (Int) -> Boolean = { keyCode ->
        if (total == 0 || (book.format != "pdf" && loadedChapter != position) ||
            error != null || remoteOffer != null || annotationDialog || annotationsDialog ||
            bookmarkDialog || tocDialog || searchDialog || appearanceDialog ||
            narrationDialog || moreMenu || aiSheetOpen || lookupQuery != null
        ) false
        else if (selection != null || webViewSelectedText != null) {
            selection = null
            webViewSelectedText = null
            true
        } else {
            val forward = keyCode in forwardPageKeys
            val summary = pagination
            if (book.format != "pdf" && engineMode == ReaderEngineMode.NATIVE_PAGED && summary != null) {
                when {
                    forward && currentPage + 1 < summary.pageCount -> {
                        val nextPage = currentPage + 1
                        currentPage = nextPage
                        summary.pages.getOrNull(nextPage)?.startOffset?.let(::persistCurrentOffset)
                    }
                    !forward && currentPage > 0 -> {
                        val prevPage = currentPage - 1
                        currentPage = prevPage
                        summary.pages.getOrNull(prevPage)?.startOffset?.let(::persistCurrentOffset)
                    }
                    forward && position + 1 < total -> position++
                    !forward && position > 0 -> {
                        openPreviousAtEnd = true
                        position--
                    }
                }
            } else {
                when {
                    forward && position + 1 < total -> position++
                    !forward && position > 0 -> {
                        openPreviousAtEnd = book.format != "pdf"
                        position--
                    }
                }
            }
            true
        }
    }
    val latestTurnPage by rememberUpdatedState(turnPage)
    DisposableEffect(activity, book.id) {
        activity?.readerKeyHandler = { keyCode -> latestTurnPage(keyCode) }
        onDispose { activity?.readerKeyHandler = null }
    }

    // Lifecycle observers outlive recompositions; always capture the loaded chapter's current body.
    val latestPersistCurrentOffset by rememberUpdatedState<(Int) -> Job?>(::persistCurrentOffset)
    // Flush progress when going background
    DisposableEffect(lifecycleOwner, book.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                readingSession.pause()
                val save = latestPersistCurrentOffset(currentOffset)
                val generation = readingGeneration
                val afterSave = { NativeSyncIntegration.readingSaved(background = true, generation = generation); ProgressSyncWorker.enqueue(context) }
                if (save == null) afterSave() else save.invokeOnCompletion { afterSave() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val speakCurrent: () -> Unit = {
        val requestedPosition = position
        val request = ++speechRequest
        scope.launch {
            preparingSpeech = true
            try {
                val body = if (book.format == "pdf") withContext(Dispatchers.IO) {
                    BookSearcher.readPdfPageText(book.file, requestedPosition)
                } else activeSourceText
                if (request != speechRequest || requestedPosition != position) return@launch
                if (body.replace('\uFFFC', ' ').isBlank()) {
                    snackbar.showSnackbar(noReadableText)
                    return@launch
                }
                narrator.play(body, if (book.format == "pdf") 0 else currentOffset, onChapterFinished)
                if (narrator.state in setOf(NarrationState.PLAYING, NarrationState.PREPARING)) {
                    NarrationPlaybackService.start(context, book.title)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: noReadableText)
            } finally {
                if (request == speechRequest) preparingSpeech = false
            }
        }
    }

    val exitReader: () -> Unit = {
        readingSession.pause()
        val save = persistCurrentOffset(currentOffset)
        val generation = readingGeneration
        val afterSave = { NativeSyncIntegration.readingSaved(generation = generation); ProgressSyncWorker.enqueue(context) }
        if (save == null) afterSave() else save.invokeOnCompletion { afterSave() }
        narrator.stop()
        NarrationPlaybackService.stop(context)
        onBack()
    }

    BackHandler {
        when {
            selection != null || webViewSelectedText != null -> {
                selection = null
                webViewSelectedText = null
            }
            else -> exitReader()
        }
    }

    fun buildContextSnapshot(): ReadingContextSnapshot {
        val chapterTitle = document?.chapters?.getOrNull(position)?.title.orEmpty()
        val visibleEnd = if (book.format != "pdf" && engineMode == ReaderEngineMode.NATIVE_PAGED)
            pagination?.pages?.getOrNull(currentPage)?.endOffset ?: currentOffset else max(currentOffset, directlySelectedEnd)
        val readOffset = visibleEnd.coerceIn(0, activeSourceText.length)
        val readingProgress = if (total > 0)
            ((position + readOffset.toDouble() / activeSourceText.length.coerceAtLeast(1)) / total).coerceIn(0.0, 1.0)
            else 0.0
        val progressPct = (readingProgress * 100).toInt()
        val precedingEnd = (selection?.normalized()?.start ?: readOffset).coerceIn(0, readOffset)
        val preceding = activeSourceText.substring((precedingEnd - 1500).coerceAtLeast(0), precedingEnd)
        val selectedEnd = selection?.normalized()?.end ?: webViewSelectedText?.let { selected ->
            activeSourceText.substring(0, readOffset).indexOf(selected).takeIf { it >= 0 }?.plus(selected.length)
        }
        val selectionSafe = activeSelectedString.isNullOrBlank() || selectedEnd != null && selectedEnd <= readOffset
        return ReadingContextSnapshot(
            bookTitle = book.title,
            bookAuthor = book.author,
            chapterTitle = chapterTitle,
            selectedText = activeSelectedString.takeIf { selectionSafe },
            selectionWithinBoundary = selectionSafe,
            precedingContext = preceding,
            progressPercent = progressPct,
            readingProgress = readingProgress,
            chapterIndex = position,
            readOffset = readOffset,
        )
    }

    fun openVocabularyDraft(initialDef: String = "") {
        val selectedStr = activeSelectedString ?: return
        val chapterTitle = if (book.format == "pdf") context.getString(R.string.page_number, position + 1)
        else document?.chapters?.getOrNull(position)?.title ?: book.title
        val sel = selection?.normalized()
        val location = if (book.format == "epub" && sel != null) {
            val body = readable
            val base = document?.chapters?.getOrNull(position)?.baseCfi
            if (body != null && base != null) body.rangeCfiAt(base, sel.start, sel.end).orEmpty() else currentCfi
        } else if (book.format == "pdf" && sel != null) {
            "native:page:$position:${sel.start}"
        } else if (sel != null) {
            "native:chapter:$position:${sel.start}"
        } else currentCfi
        val sentence = if (sel != null) vocabularySentence(activeSourceText, sel.start, sel.end) else selectedStr
        vocabularyDraft = VocabularyDraft(selectedStr, sentence, location, chapterTitle, initialDef)
    }

    fun openAnnotationDraft() {
        val sel = selection?.normalized() ?: return
        val body = readable ?: return
        val chapter = document?.chapters?.getOrNull(position) ?: return
        val base = chapter.baseCfi ?: return
        draftCfi = body.rangeCfiAt(base, sel.start, sel.end) ?: return
        draftContent = body.text.substring(sel.start.coerceIn(0, body.text.length), sel.end.coerceIn(0, body.text.length))
        draftChapter = chapter.title
        editingAnnotation = null
        draftType = "highlight"
        draftColor = "66CCFF"
        draftNote = ""
        annotationDialog = true
    }

    ReadingWorkspaceHost(aiSheetOpen) { workspaceDocked, readerModifier ->
    Scaffold(
        modifier = readerModifier,
        containerColor = appearance.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (chromeVisible) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = appearance.background.copy(alpha = 0.96f),
                        titleContentColor = appearance.foreground,
                        navigationIconContentColor = appearance.foreground,
                        actionIconContentColor = appearance.foreground,
                    ),
                    title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = exitReader) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            aiQuickAction = null
                            aiSheetOpen = true
                        }) {
                            Icon(Icons.Default.AutoAwesome, stringResource(R.string.ai_assistant))
                        }
                        if (document != null) {
                            IconButton(onClick = { searchDialog = true }) {
                                Icon(Icons.Default.Search, stringResource(R.string.search_in_book))
                            }
                        }
                        if (book.format == "pdf") {
                            IconToggleButton(
                                checked = pdfTextMode,
                                onCheckedChange = {
                                    if (it) pdfTextLoading = true
                                    pdfTextMode = it
                                },
                                enabled = pageCount > 0,
                            ) {
                                Icon(
                                    if (pdfTextMode) Icons.Default.Image else Icons.Default.TextFields,
                                    stringResource(if (pdfTextMode) R.string.pdf_page_mode else R.string.pdf_text_mode),
                                )
                            }
                        }
                        IconButton(onClick = { appearanceDialog = true }) {
                            Icon(Icons.Default.Tune, stringResource(R.string.reader_appearance))
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.more_reader_options))
                            }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                if (document?.toc?.isNotEmpty() == true) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.contents)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                        onClick = { moreMenu = false; tocDialog = true },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.read_aloud)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
                                    onClick = { moreMenu = false; narrationDialog = true },
                                )
                                if (book.format == "epub") {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.bookmarks)) },
                                        leadingIcon = { Icon(Icons.Default.Bookmarks, null) },
                                        onClick = { moreMenu = false; bookmarkDialog = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.annotations)) },
                                        leadingIcon = { Icon(Icons.Default.FormatUnderlined, null) },
                                        onClick = { moreMenu = false; annotationsDialog = true },
                                    )
                                }
                                if (book.format != "pdf") {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.engine_native_paged) +
                                                    if (engineMode == ReaderEngineMode.NATIVE_PAGED) " ✓" else "",
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                                        onClick = {
                                            moreMenu = false
                                            engineMode = ReaderEngineMode.NATIVE_PAGED
                                            controlsStore.setReaderEngineMode(engineMode)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.engine_native_scroll) +
                                                    if (engineMode == ReaderEngineMode.NATIVE_SCROLL) " ✓" else "",
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Default.TextFields, null) },
                                        onClick = {
                                            moreMenu = false
                                            engineMode = ReaderEngineMode.NATIVE_SCROLL
                                            controlsStore.setReaderEngineMode(engineMode)
                                        },
                                    )
                                    if (book.format == "epub") {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(R.string.engine_webview_fallback) +
                                                        if (engineMode == ReaderEngineMode.WEBVIEW_FALLBACK) " ✓" else "",
                                                )
                                            },
                                            leadingIcon = { Icon(Icons.Default.Tune, null) },
                                            onClick = {
                                                moreMenu = false
                                                engineMode = ReaderEngineMode.WEBVIEW_FALLBACK
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(appearance.background.copy(alpha = 0.96f))) {
                referenceReturn?.let { point ->
                    TextButton(onClick = {
                        if (point.first == position) resumeOffset = point.second
                        else { pendingOffset = point.second; position = point.first }
                        referenceReturn = null
                    }) { Text(stringResource(R.string.ai_source_return)) }
                }
                // Edge selection action bar (low-distraction, E-INK friendly)
                if (!activeSelectedString.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (book.format == "epub" && selection != null) {
                                    AssistChip(
                                        onClick = { openAnnotationDraft() },
                                        label = { Text(stringResource(R.string.highlight)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) },
                                    )
                                }
                                AssistChip(
                                    onClick = { lookupQuery = activeSelectedString },
                                    label = { Text(stringResource(R.string.lookup_word)) },
                                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) },
                                )
                                AssistChip(
                                    onClick = {
                                        aiQuickAction = AiQuickAction.EXPLAIN_SELECTION
                                        aiSheetOpen = true
                                    },
                                    label = { Text(stringResource(R.string.ai_explain)) },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) },
                                )
                                AssistChip(
                                    onClick = { openVocabularyDraft() },
                                    label = { Text(stringResource(R.string.vocabulary_add)) },
                                    leadingIcon = { Icon(Icons.Default.School, null, Modifier.size(16.dp)) },
                                )
                                AssistChip(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Inkflow", activeSelectedString))
                                        scope.launch { snackbar.showSnackbar(context.getString(R.string.copied_to_clipboard)) }
                                    },
                                    label = { Text(stringResource(R.string.copy_text)) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) },
                                )
                                if (selection != null) {
                                    AssistChip(
                                        onClick = {
                                            SentenceBoundaryResolver.paragraphRangeAt(activeSourceText, selection!!.start)
                                                ?.let { selection = it }
                                        },
                                        label = { Text(stringResource(R.string.select_paragraph)) },
                                    )
                                }
                                IconButton(onClick = { selection = null; webViewSelectedText = null }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.close))
                                }
                            }
                            selection?.let { selectedRange ->
                                SelectionRangeControls(selectionController, selectedRange, currentPage,
                                    pagination.takeIf { engineMode == ReaderEngineMode.NATIVE_PAGED && book.format != "pdf" },
                                    onChange = { selection = it },
                                    onShowPage = { target ->
                                        currentPage = target
                                        pagination?.pages?.getOrNull(target)?.startOffset?.let(::persistCurrentOffset)
                                    })
                            }
                        }
                    }
                }

                if (chromeVisible) {
                    HorizontalDivider(color = appearance.foreground.copy(alpha = 0.1f))
                    if (total > 1) {
                        Slider(
                            value = position.toFloat(),
                            onValueChange = { position = it.roundToInt().coerceIn(0, total - 1) },
                            valueRange = 0f..(total - 1).toFloat(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                if (book.format == "pdf" && pdfTextMode) pdfTextLoading = true
                                position--
                            },
                            enabled = position > 0,
                        ) {
                            Icon(Icons.Default.ChevronLeft, stringResource(R.string.previous), tint = appearance.foreground)
                        }
                        Text(
                            text = if (total > 0) "${position + 1} / $total" else "",
                            modifier = Modifier.weight(1f),
                            color = appearance.foreground,
                        )
                        if (narrator.state == NarrationState.PLAYING || narrator.state == NarrationState.PAUSED) {
                            IconButton(onClick = {
                                if (narrator.state == NarrationState.PLAYING) narrator.pause() else narrator.resume()
                            }) {
                                Icon(
                                    if (narrator.state == NarrationState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    stringResource(if (narrator.state == NarrationState.PLAYING) R.string.pause else R.string.play),
                                    tint = appearance.foreground,
                                )
                            }
                            IconButton(onClick = {
                                autoNarrateTarget = null; speechRequest++; preparingSpeech = false
                                narrator.stop(); NarrationPlaybackService.stop(context)
                            }) {
                                Icon(Icons.Default.Stop, stringResource(R.string.stop), tint = appearance.foreground)
                            }
                        }
                        if (selection != null) {
                            Box {
                                IconButton(onClick = { selectionMenu = true }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.selection_actions), tint = appearance.foreground)
                                }
                                DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                                    if (book.format == "epub") {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.add_annotation)) },
                                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            onClick = { selectionMenu = false; openAnnotationDraft() },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.vocabulary_add)) },
                                        leadingIcon = { Icon(Icons.Default.School, null) },
                                        onClick = { selectionMenu = false; openVocabularyDraft() },
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                if (book.format == "pdf" && pdfTextMode) pdfTextLoading = true
                                position++
                            },
                            enabled = position + 1 < total,
                        ) {
                            Icon(Icons.Default.ChevronRight, stringResource(R.string.next), tint = appearance.foreground)
                        }
                    }
                }
            }
        },
    ) { insets ->
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { Text(error!!) }
            document == null || total == 0 || (book.format != "pdf" && loadedChapter != position) -> Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            book.format == "pdf" && pdfTextMode -> {
                if (pdfTextLoading) {
                    Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (text.isBlank()) {
                    Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_selectable_text), color = appearance.foreground)
                    }
                } else {
                    ReaderTextBackground(appearance, Modifier.fillMaxSize().padding(insets)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                            var fieldValue by remember(text) { mutableStateOf(TextFieldValue(text)) }
                            BasicTextField(
                                value = fieldValue,
                                onValueChange = { changed ->
                                    fieldValue = TextFieldValue(text, changed.selection)
                                    val s = min(changed.selection.start, changed.selection.end)
                                    val e = max(changed.selection.start, changed.selection.end)
                                    selection = if (s < e) TextSelectionRange(s, e) else null
                                    directlySelectedEnd = e
                                },
                                readOnly = true,
                                textStyle = TextStyle(
                                    fontSize = appearance.fontSize.sp,
                                    lineHeight = (appearance.fontSize * appearance.lineSpacing).sp,
                                    fontFamily = readerFontFamily,
                                    color = appearance.foreground,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            book.format == "pdf" -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(insets)
                        .background(Color(0xFFE7E7E7))
                        .clickable { chromeVisible = !chromeVisible },
                    contentAlignment = Alignment.Center,
                ) {
                    if (pdfPage == null) CircularProgressIndicator()
                    else NativePdfPage(pdfPage!!,
                        onPrevious = { if (position > 0) position-- },
                        onNext = { if (position + 1 < total) position++ },
                        onToggleChrome = { chromeVisible = !chromeVisible },
                        onExpand = { fullScreenImage = pdfPage },
                        modifier = Modifier.fillMaxSize())
                }
            }
            book.format == "epub" && engineMode == ReaderEngineMode.WEBVIEW_FALLBACK -> {
                val chapter = document!!.chapters[position.coerceIn(document!!.chapters.indices)]
                DirectZipWebViewReader(
                    epubFile = book.file,
                    chapter = chapter,
                    appearance = appearance,
                    eInkMode = eInk.enabled,
                    onTextSelected = { selectedStr ->
                        webViewSelectedText = selectedStr.ifBlank { null }
                        val start = activeSourceText.indexOf(selectedStr)
                        // DOM selection is explicitly made in the displayed chapter; require a unique local match.
                        directlySelectedEnd = if (selectedStr.isNotBlank() && start >= 0 &&
                            activeSourceText.indexOf(selectedStr, start + 1) < 0) start + selectedStr.length else 0
                    },
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    modifier = Modifier.fillMaxSize().padding(insets),
                )
            }
            engineMode == ReaderEngineMode.NATIVE_PAGED -> {
                val chapter = document!!.chapters[position.coerceIn(document!!.chapters.indices)]
                val chapterAnnotations = if (book.format == "epub") {
                    annotations.filter { EpubCfi.chapterIndex(it.cfi, document!!.chapters) == position }
                } else emptyList()
                ReaderTextBackground(appearance, Modifier.fillMaxSize().padding(insets)) {
                    NativePagedReaderCanvas(
                        file = book.file,
                        chapter = chapter,
                        swipeToTurnPage = swipeToTurnPage,
                        chapterTitle = chapter.title,
                        chapterIndex = position,
                        totalChapters = total,
                        rawText = text,
                        epubText = readable,
                        annotations = chapterAnnotations,
                        appearance = appearance,
                        fontFamily = readerFontFamily,
                        currentPage = currentPage,
                        selection = selection,
                        onPaginationReady = { pagination = it },
                        onPageChange = { newPage ->
                            currentPage = newPage
                            pagination?.pages?.getOrNull(newPage)?.startOffset?.let(::persistCurrentOffset)
                        },
                        onNextChapter = { if (position + 1 < total) position++ },
                        onPreviousChapter = {
                            if (position > 0) {
                                openPreviousAtEnd = true
                                position--
                            }
                        },
                        onToggleChrome = { chromeVisible = !chromeVisible },
                        onSelectionChange = { selection = it },
                    )
                }
            }
            else -> {
                val chapter = document!!.chapters[position.coerceIn(document!!.chapters.indices)]
                val chapterAnnotations = if (book.format == "epub") {
                    annotations.filter { EpubCfi.chapterIndex(it.cfi, document!!.chapters) == position }
                } else emptyList()
                ReaderTextBackground(appearance, Modifier.fillMaxSize().padding(insets)) {
                    VirtualizedScrollReader(
                        chapterTitle = chapter.title,
                        showChapterHeading = book.format !in setOf("md", "markdown") || !chapter.hasHeading,
                        file = book.file,
                        chapter = chapter,
                        rawText = text,
                        epubText = readable,
                        annotations = chapterAnnotations,
                        appearance = appearance,
                        fontFamily = readerFontFamily,
                        initialOffset = resumeOffset,
                        onOffsetSettled = { offset -> persistCurrentOffset(offset) },
                        onSelectionChange = { selection = it; directlySelectedEnd = it?.normalized()?.end ?: 0 },
                    )
                }
            }
        }
    }

    // --- Sheets & Dialogs ---

    if (aiSheetOpen) {
        AiReadingSheet(
            bookId = book.id,
            book = book,
            store = store,
            docked = workspaceDocked,
            snapshot = buildContextSnapshot(),
            initialQuickAction = aiQuickAction,
            initialTranslation = aiTranslation,
            onOpenSource = { source ->
                val chapters = document?.chapters.orEmpty()
                val target = source.chapterIndex.takeIf { it in chapters.indices }
                    ?: chapters.indexOfFirst { it.path == source.chapterHref }.takeIf { it >= 0 }
                if (target != null) {
                    if (referenceReturn == null) referenceReturn = position to currentOffset
                    val offset = if (target == position) activeSourceText.indexOf(source.text).takeIf { it >= 0 }
                        ?: (source.endOffset - source.text.length).coerceAtLeast(0)
                        else (source.endOffset - source.text.length).coerceAtLeast(0)
                    if (target == position) resumeOffset = offset else { pendingOffset = offset; position = target }
                    selection = null
                    scope.launch {
                        val result = snackbar.showSnackbar(context.getString(R.string.ai_source_opened), context.getString(R.string.ai_source_return), duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) referenceReturn?.let { point ->
                            if (point.first == position) resumeOffset = point.second else { pendingOffset = point.second; position = point.first }
                            referenceReturn = null
                        }
                    }
                }
            },
            onSaveToNotes = { title, content, quote, source ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            NativeReadingActions(store).saveAiNote(book.id, title, content, quote, source, userAuthorized = true)
                        }
                        snackbar.showSnackbar(context.getString(R.string.ai_saved_to_notes))
                    } catch (failure: Exception) {
                        snackbar.showSnackbar(failure.message ?: context.getString(R.string.note_operation_failed))
                    }
                }
            },
            onDismiss = {
                aiSheetOpen = false
                aiQuickAction = null
                aiTranslation = null
            },
        )
    }

    } // ReadingWorkspaceHost

    lookupQuery?.let { queryText ->
        LookupSheet(
            query = queryText,
            store = store,
            onTranslationRequest = { request ->
                lookupQuery = null
                aiTranslation = request
                aiQuickAction = AiQuickAction.TRANSLATE_SELECTION
                aiSheetOpen = true
            },
            onAskAi = {
                lookupQuery = null
                aiTranslation = null
                aiQuickAction = AiQuickAction.EXPLAIN_SELECTION
                aiSheetOpen = true
            },
            onAddVocabulary = { def ->
                lookupQuery = null
                openVocabularyDraft(def)
            },
            onDismiss = { lookupQuery = null },
        )
    }

    remoteOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = { remoteOffer = null },
            title = { Text(stringResource(R.string.remote_progress_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.remote_progress_message, (offer.progress * 100).roundToInt()))
                    offer.chapterTitle?.takeIf(String::isNotBlank)?.let {
                        Text(stringResource(R.string.remote_progress_chapter, it))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chapters = document?.chapters.orEmpty()
                    val target = EpubCfi.chapterIndex(offer.cfi, chapters)
                    remoteOffer = null
                    if (target != null) {
                        if (target == position) {
                            val off = readable?.offsetForCfi(offer.cfi) ?: 0
                            resumeOffset = off
                            persistCurrentOffset(off)
                        } else {
                            pendingCfi = offer.cfi
                            position = target
                        }
                    }
                }) { Text(stringResource(R.string.jump_to_remote)) }
            },
            dismissButton = {
                TextButton(onClick = { remoteOffer = null }) { Text(stringResource(R.string.continue_here)) }
            },
        )
    }

    if (narrationDialog) {
        ReaderNarrationDialog(
            state = narrator.state,
            preparingText = preparingSpeech,
            rate = narrator.rate,
            pitch = narrator.pitch,
            voices = narrator.voices,
            selectedVoiceName = narrator.selectedVoiceName,
            continuous = narrator.continuous,
            onPlayPause = {
                when (narrator.state) {
                    NarrationState.PLAYING -> narrator.pause()
                    NarrationState.PAUSED -> narrator.resume()
                    else -> speakCurrent()
                }
            },
            onStop = {
                autoNarrateTarget = null; speechRequest++; preparingSpeech = false
                narrator.stop(); NarrationPlaybackService.stop(context)
            },
            onRateChange = narrator::updateRate,
            onPitchChange = narrator::updatePitch,
            onVoiceChange = { narrator.updateVoice(it) },
            onContinuousChange = narrator::updateContinuous,
            onDismiss = { narrationDialog = false },
        )
    }

    fullScreenImage?.let { image -> ImageViewerDialog(image, onDismiss = { fullScreenImage = null }) }

    if (appearanceDialog) {
        ReaderAppearanceDialog(
            appearance = appearance,
            swipeToTurnPage = swipeToTurnPage,
            onSwipeToTurnPageChange = { swipeToTurnPage = it; controlsStore.setSwipeToTurnPage(it) },
            onChange = { updated ->
                if (updated.fontSize != appearance.fontSize || updated.lineSpacing != appearance.lineSpacing ||
                    updated.font != appearance.font || updated.customFontId != appearance.customFontId ||
                    updated.pageMargin != appearance.pageMargin || updated.paragraphSpacing != appearance.paragraphSpacing ||
                    updated.firstLineIndent != appearance.firstLineIndent || updated.alignment != appearance.alignment ||
                    updated.showHeader != appearance.showHeader || updated.showFooter != appearance.showFooter
                ) {
                    resumeOffset = currentOffset
                }
                onAppearanceChange(updated)
            },
            onDismiss = { appearanceDialog = false },
        )
    }

    if (tocDialog) document?.let { inspected ->
        ReaderTocDialog(inspected, position, onDismiss = { tocDialog = false }) { entry ->
            tocDialog = false
            if (book.format == "pdf") {
                position = entry.chapterIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            } else if (entry.chapterIndex == position) {
                val offset = entry.fragment?.let { readable?.anchorOffset(it) } ?: 0
                resumeOffset = offset
                persistCurrentOffset(offset)
            } else {
                pendingFragment = entry.fragment
                position = entry.chapterIndex
            }
        }
    }

    if (searchDialog) {
        ReaderSearchDialog(searchQuery, { searchQuery = it }, searching, searchResults, onDismiss = { searchDialog = false }) { hit ->
            searchDialog = false
            if (book.format == "pdf") {
                resumeOffset = hit.offset
                if (!pdfTextMode || hit.chapterIndex != position) pdfTextLoading = true
                pdfTextMode = true
                position = hit.chapterIndex
            } else if (book.format != "epub") {
                if (hit.chapterIndex == position) resumeOffset = hit.offset
                else {
                    pendingOffset = hit.offset
                    position = hit.chapterIndex
                }
            } else {
                if (hit.chapterIndex == position) resumeOffset = hit.offset
                else {
                    pendingCfi = hit.cfi
                    position = hit.chapterIndex
                }
            }
        }
    }

    if (annotationsDialog) {
        AlertDialog(
            onDismissRequest = { annotationsDialog = false },
            title = { Text(stringResource(R.string.annotations)) },
            text = {
                if (annotations.isEmpty()) Text(stringResource(R.string.no_annotations))
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(annotations, key = { it.id }) { annotation ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val chapters = document?.chapters.orEmpty()
                                    val target = EpubCfi.chapterIndex(annotation.cfi, chapters) ?: return@TextButton
                                    annotationsDialog = false
                                    if (target == position) {
                                        resumeOffset = readable?.offsetForCfi(annotation.cfi) ?: 0
                                    } else {
                                        pendingCfi = annotation.cfi
                                        position = target
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(annotation.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        annotation.readerNote.ifBlank { annotation.chapter },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = InkflowPalette.Accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(onClick = {
                                editingAnnotation = annotation
                                draftCfi = annotation.cfi
                                draftContent = annotation.content
                                draftChapter = annotation.chapter
                                draftType = annotation.type
                                draftColor = annotation.color
                                draftNote = annotation.readerNote
                                annotationsDialog = false
                                annotationDialog = true
                            }) { Icon(Icons.Default.Edit, stringResource(R.string.edit_annotation)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { annotationsDialog = false }) { Text(stringResource(R.string.close)) } },
        )
    }

    vocabularyDraft?.let { draft ->
        var word by remember(draft) { mutableStateOf(draft.word.trim()) }
        var definition by remember(draft) { mutableStateOf(draft.initialDefinition) }
        AlertDialog(
            onDismissRequest = { if (!vocabularySaving) vocabularyDraft = null },
            title = { Text(stringResource(R.string.vocabulary_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(word, { word = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.vocabulary_word)) })
                    OutlinedTextField(definition, { definition = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.vocabulary_definition)) })
                    Text(draft.sentence, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !vocabularySaving && word.isNotBlank() && word.length <= 80,
                    onClick = {
                        vocabularySaving = true
                        scope.launch {
                            try {
                                val added = withContext(Dispatchers.IO) {
                                    NativeVocabularyStore(store).add(book, draft.chapterTitle, word, draft.sentence, draft.cfi, definition)
                                }
                                if (added) runCatching { VocabularyEnrichmentWorker.enqueue(context, word) }
                                vocabularyDraft = null
                                selection = null
                                webViewSelectedText = null
                                snackbar.showSnackbar(context.getString(if (added) R.string.vocabulary_added else R.string.vocabulary_exists))
                            } catch (failure: Exception) {
                                snackbar.showSnackbar(failure.message ?: context.getString(R.string.vocabulary_add_failed))
                            } finally {
                                vocabularySaving = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(enabled = !vocabularySaving, onClick = { vocabularyDraft = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (annotationDialog) {
        AlertDialog(
            onDismissRequest = { if (!annotationSaving) annotationDialog = false },
            title = { Text(stringResource(if (editingAnnotation == null) R.string.add_annotation else R.string.edit_annotation)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(draftContent, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draftType == "highlight", onClick = { draftType = "highlight" },
                            label = { Text(stringResource(R.string.highlight)) })
                        FilterChip(selected = draftType == "underline", onClick = { draftType = "underline" },
                            label = { Text(stringResource(R.string.underline)) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnnotationColors.forEach { hex ->
                            val color = Color(0xFF000000 or hex.toLong(16))
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .border(if (draftColor == hex) 3.dp else 1.dp, InkflowPalette.Ink)
                                    .background(color)
                                    .clickable { draftColor = hex }
                                    .semantics { contentDescription = "#$hex" },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draftNote,
                        onValueChange = { draftNote = it },
                        label = { Text(stringResource(R.string.annotation_note)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !annotationSaving,
                    onClick = {
                        annotationSaving = true
                        scope.launch {
                            try {
                                val mutation = withContext(Dispatchers.IO) {
                                    store.saveAnnotation(book.id, draftContent, draftCfi, draftChapter, draftType, draftColor, draftNote, editingAnnotation)
                                }
                                annotations = withContext(Dispatchers.IO) { store.annotations(book.id) }
                                annotationDialog = false
                                selection = null
                                if (snackbar.showSnackbar(context.getString(R.string.annotation_saved), context.getString(R.string.undo)) == SnackbarResult.ActionPerformed) {
                                    val result = withContext(Dispatchers.IO) { store.undoAnnotation(mutation.actionId) }
                                    annotations = withContext(Dispatchers.IO) { store.annotations(book.id) }
                                    if (result != AnnotationUndoResult.UNDONE) {
                                        snackbar.showSnackbar(context.getString(R.string.annotation_undo_failed))
                                    }
                                }
                            } catch (failure: Exception) {
                                snackbar.showSnackbar(context.getString(R.string.language_error_save_annotation))
                            } finally {
                                annotationSaving = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    editingAnnotation?.let { annotation ->
                        TextButton(
                            enabled = !annotationSaving,
                            onClick = {
                                annotationSaving = true
                                scope.launch {
                                    try {
                                        val actionId = withContext(Dispatchers.IO) { store.deleteAnnotation(book.id, annotation) }
                                        annotations = withContext(Dispatchers.IO) { store.annotations(book.id) }
                                        annotationDialog = false
                                        if (snackbar.showSnackbar(context.getString(R.string.annotation_deleted), context.getString(R.string.undo)) == SnackbarResult.ActionPerformed) {
                                            val result = withContext(Dispatchers.IO) { store.undoAnnotation(actionId) }
                                            annotations = withContext(Dispatchers.IO) { store.annotations(book.id) }
                                            if (result != AnnotationUndoResult.UNDONE) {
                                                snackbar.showSnackbar(context.getString(R.string.annotation_undo_failed))
                                            }
                                        }
                                    } catch (failure: Exception) {
                                        snackbar.showSnackbar(context.getString(R.string.language_error_delete_annotation))
                                    } finally {
                                        annotationSaving = false
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.delete_annotation)) }
                    }
                    TextButton(enabled = !annotationSaving, onClick = { annotationDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            },
        )
    }

    if (bookmarkDialog) {
        AlertDialog(
            onDismissRequest = { bookmarkDialog = false },
            title = { Text(stringResource(R.string.bookmarks)) },
            text = {
                if (bookmarks.isEmpty()) Text(stringResource(R.string.no_bookmarks))
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val chapters = document?.chapters.orEmpty()
                                    val target = EpubCfi.chapterIndex(bookmark.cfi, chapters) ?: return@TextButton
                                    bookmarkDialog = false
                                    if (target == position) {
                                        resumeOffset = readable?.offsetForCfi(bookmark.cfi) ?: 0
                                    } else {
                                        pendingCfi = bookmark.cfi
                                        position = target
                                    }
                                    scope.launch(Dispatchers.IO) {
                                        store.savePosition(book.id, target, bookmark.cfi, bookmark.percentage)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(bookmark.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(bookmark.chapter, style = MaterialTheme.typography.bodySmall, color = InkflowPalette.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.deleteBookmark(book.id, bookmark.id) }
                                    bookmarks = withContext(Dispatchers.IO) { store.bookmarks(book.id) }
                                }
                            }) { Icon(Icons.Default.Delete, stringResource(R.string.delete_bookmark)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !bookmarkSaving && readable != null && currentCfi.isNotBlank(),
                    onClick = {
                        val chapters = document?.chapters ?: return@TextButton
                        val body = readable ?: return@TextButton
                        val chapter = chapters.getOrNull(position) ?: return@TextButton
                        val cfi = currentCfi.ifBlank { return@TextButton }
                        val percentage = (position + currentOffset.toDouble() / body.text.length.coerceAtLeast(1)) / chapters.size
                        val excerpt = body.text.substring(currentOffset.coerceIn(0, body.text.length))
                            .replace("\uFFFC", "").trim().take(120).ifBlank { chapter.title }
                        bookmarkSaving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.addBookmark(book.id, cfi, chapter.title, excerpt, percentage) }
                                bookmarks = withContext(Dispatchers.IO) { store.bookmarks(book.id) }
                            } finally {
                                bookmarkSaving = false
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.Add, null)
                    Text(stringResource(R.string.add_bookmark))
                }
            },
            dismissButton = { TextButton(onClick = { bookmarkDialog = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun ReaderTextBackground(
    appearance: ReaderAppearance,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val image = appearance.backgroundImage
    val bitmap by produceState<Bitmap?>(null, context, image, appearance.isDark) {
        value = if (image == null) null else withContext(Dispatchers.IO) {
            ReaderAppearanceStore(context).bitmap(image, appearance.isDark)
        }
    }
    Box(modifier.background(appearance.background)) {
        if (image != null && bitmap != null) {
            val alignment = when (image.alignment) {
                "top" -> Alignment.TopCenter
                "bottom" -> Alignment.BottomCenter
                "left" -> Alignment.CenterStart
                "right" -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(image.blur.dp),
                alignment = alignment,
                contentScale = if (image.fit == "stretch") ContentScale.FillBounds else ContentScale.Crop,
                alpha = image.opacity,
            )
        }
        content()
    }
}

private fun vocabularySentence(text: String, start: Int, end: Int): String {
    val boundaries = charArrayOf('\n', '.', '!', '?', '。', '！', '？')
    val before = text.lastIndexOfAny(boundaries, startIndex = (start - 1).coerceAtLeast(0))
    val after = text.indexOfAny(boundaries, startIndex = end.coerceIn(0, text.length))
    val from = maxOf(0, start - 160, before + 1)
    val until = minOf(text.length, end + 160, if (after < 0) text.length else after + 1)
    return text.substring(from.coerceIn(0, text.length), until.coerceIn(from.coerceIn(0, text.length), text.length)).trim()
}
