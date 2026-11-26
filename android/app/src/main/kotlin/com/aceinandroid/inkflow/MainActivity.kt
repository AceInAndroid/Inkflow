package com.aceinandroid.inkflow

import android.content.res.Configuration
import android.content.Context
import android.content.res.Resources
import com.aceinandroid.inkflow.localization.NativeLocaleContext
import com.aceinandroid.inkflow.localization.LocalizedApp
import com.aceinandroid.inkflow.localization.NativeLocaleController
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aceinandroid.inkflow.ui.bookshelf.BookshelfScreen
import com.aceinandroid.inkflow.ui.reader.ReaderScreen
import com.aceinandroid.inkflow.ui.splash.InkflowSplashOverlay
import com.aceinandroid.inkflow.ui.theme.InkflowTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var incomingIntent by mutableStateOf<Intent?>(null)
    internal var readerKeyHandler: ((Int) -> Boolean)? = null
    private val consumedPageKeys = mutableSetOf<Int>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(NativeLocaleContext(newBase))
    }

    // Compose dialogs create their own resource locals from the Activity's View context.
    // Read through the stable wrapper instead of ContextThemeWrapper's cached Resources.
    override fun getResources(): Resources = baseContext?.resources ?: super.getResources()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeBackupManager(applicationContext).recoverInterrupted()
        PDFBoxResourceLoader.init(applicationContext)
        incomingIntent = intent
        setContent { LocalizedApp { NativeApp(incomingIntent) } }
    }

    override fun onResume() {
        super.onResume()
        NativeLocaleController.refresh()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        NativeLocaleController.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntent = intent
    }

    // ComponentActivity marks this framework override restricted; unhandled keys must reach super.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val volumeKey = event.keyCode in volumePageKeys
        if (!volumeKey && event.keyCode !in forwardPageKeys && event.keyCode !in backwardPageKeys)
            return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && consumedPageKeys.remove(event.keyCode)) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in consumedPageKeys) return true
        if (event.action == KeyEvent.ACTION_DOWN && (!volumeKey || ReaderControlsStore(this).volumeKeysTurnPages()) &&
            readerKeyHandler?.invoke(event.keyCode) == true
        ) {
            consumedPageKeys.add(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

private val volumePageKeys = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
private val forwardPageKeys = setOf(
    KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE,
)
private val backwardPageKeys = setOf(
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
)

private enum class HomeTab(val labelRes: Int, val icon: ImageVector) {
    LIBRARY(R.string.library, Icons.AutoMirrored.Filled.MenuBook),
    NOTES(R.string.reading_notes, Icons.Default.NoteAlt),
    VOCABULARY(R.string.vocabulary_title, Icons.Default.School),
    STATISTICS(R.string.reading_statistics, Icons.Default.BarChart),
    SETTINGS(R.string.settings, Icons.Default.Settings),
}

@Suppress("DEPRECATION")
internal fun incomingBookUris(intent: Intent): List<Uri> {
    val streams = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM))
            .filterIsInstance<Uri>()
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
            .orEmpty().filterIsInstance<Uri>()
        else -> emptyList()
    }
    val clipped = if (intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE))
        intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
            .orEmpty() else emptyList()
    return (streams + clipped).distinct()
}

@Composable
private fun NativeApp(initialIntent: Intent?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember { BookStore(context) }
    val vocabulary = remember(store) { NativeVocabularyStore(store) }
    val progressSync = remember(context) { NativeProgressSync(context) }
    val controlsStore = remember(context) { ReaderControlsStore(context) }

    var eInkConfig by remember { mutableStateOf(controlsStore.eInkConfig()) }
    var books by remember { mutableStateOf(store.all()) }
    var selected by rememberSaveable(stateSaver = Saver<StoredBook?, Long>(
        save = { it?.id ?: -1L }, restore = { id -> store.all().firstOrNull { it.id == id } },
    )) { mutableStateOf<StoredBook?>(null) }
    var selectedOffset by rememberSaveable { mutableStateOf<Int?>(null) }
    var foregroundRevision by remember { mutableIntStateOf(0) }
    var activeTab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    var libraryNotes by remember { mutableStateOf<List<LibraryNote>>(emptyList()) }
    var notesLoading by remember { mutableStateOf(false) }
    var readingSummary by remember { mutableStateOf(ReadingSummary(0, 0, 0, emptyList(), emptyList())) }
    var statisticsLoading by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<StoredBook?>(null) }
    var importing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var exportingBackup by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var pendingExport by remember { mutableStateOf<List<LibraryNote>?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    BackHandler(selected != null) {
        books = store.all()
        selected = null
        selectedOffset = null
    }
    BackHandler(selected == null && activeTab != HomeTab.LIBRARY) { activeTab = HomeTab.LIBRARY }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foregroundRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(selected, foregroundRevision, restoring, importing, exportingBackup) {
        if (selected != null || restoring || importing || exportingBackup) return@LaunchedEffect
        try {
            val synced = withContext(Dispatchers.IO) { progressSync.synchronizeAutomatically(store) }
            if (synced != null) books = withContext(Dispatchers.IO) { store.all() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("NativeProgressSync", "Automatic sync failed: ${failure.javaClass.simpleName}")
        }
        try {
            withContext(Dispatchers.IO) { NativeIncrementalSync(context).synchronizeAutomatically(store) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("NativeIncrementalSync", "Automatic sync failed: ${failure.javaClass.simpleName}")
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val notes = pendingExport
        pendingExport = null
        if (uri != null && notes != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: error("Cannot open export document")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(notesAsMarkdown(notes)) }
                }
                snackbar.showSnackbar(context.getString(R.string.notes_exported))
            } catch (failure: Exception) {
                Log.e("NativeNotes", "Cannot export notes", failure)
                snackbar.showSnackbar(context.getString(R.string.notes_export_failed))
            }
        }
    }

    val importUris: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = true
            try {
                val (imported, failures) = withContext(Dispatchers.IO) {
                    val successes = mutableListOf<StoredBook>()
                    val errors = mutableListOf<Exception>()
                    uris.forEach { uri ->
                        try { successes += store.import(uri) }
                        catch (failure: Exception) {
                            Log.e("NativeImport", "Import failed", failure)
                            errors += failure
                        }
                    }
                    successes to errors
                }
                books = withContext(Dispatchers.IO) { store.all() }
                if (uris.size == 1 && imported.size == 1) selected = imported.first()
                if (failures.isNotEmpty()) snackbar.showSnackbar(
                    if (uris.size == 1) failures.first().message ?: context.getString(R.string.import_failed)
                    else context.getString(R.string.import_partial, imported.size, uris.size),
                )
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: context.getString(R.string.import_failed))
            } finally {
                importing = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importUris(it) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pendingRestore = it }
    val backupExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            exportingBackup = true
            try {
                withContext(Dispatchers.IO) { NativeSyncIntegration.localMutation { NativeBackupManager(context).export(uri, store) } }
                snackbar.showSnackbar(context.getString(R.string.export_backup_done))
            } catch (failure: Exception) {
                snackbar.showSnackbar(context.getString(R.string.export_backup_failed, failure.message ?: ""))
            } finally {
                exportingBackup = false
            }
        }
    }

    LaunchedEffect(initialIntent) {
        if (initialIntent != null) importUris(incomingBookUris(initialIntent))
    }

    LaunchedEffect(activeTab, selected) {
        if (activeTab != HomeTab.NOTES || selected != null) return@LaunchedEffect
        notesLoading = true
        try {
            libraryNotes = withContext(Dispatchers.IO) { store.libraryNotes() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            snackbar.showSnackbar(context.getString(R.string.language_error_load_notes))
        } finally {
            notesLoading = false
        }
    }

    LaunchedEffect(activeTab, selected) {
        if (activeTab != HomeTab.STATISTICS || selected != null) return@LaunchedEffect
        statisticsLoading = true
        try {
            readingSummary = withContext(Dispatchers.IO) { store.readingSummary() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            snackbar.showSnackbar(context.getString(R.string.language_error_statistics))
        } finally {
            statisticsLoading = false
        }
    }

    InkflowTheme(eInkConfig = eInkConfig) {
        if (selected != null) {
            ReaderScreen(
                book = selected!!,
                store = store,
                snackbar = snackbar,
                initialOffset = selectedOffset,
                onBack = {
                    books = store.all()
                    selected = null
                    selectedOffset = null
                },
            )
        } else {
            val activeContent: @Composable (Modifier) -> Unit = { contentModifier ->
                Box(contentModifier.fillMaxSize()) {
                    when (activeTab) {
                        HomeTab.LIBRARY -> BookshelfScreen(
                            books = books,
                            store = store,
                            importing = importing,
                            snackbar = snackbar,
                            onLibraryChanged = { books = store.all() },
                            onImport = { picker.launch(arrayOf("*/*")) },
                            onOpen = { selected = it },
                            onRemove = { removing = it },
                        )
                        HomeTab.NOTES -> LibraryNotesScreen(
                            notes = libraryNotes,
                            availableBooks = books,
                            loading = notesLoading,
                            snackbar = snackbar,
                            store = store,
                            onRefresh = {
                                scope.launch {
                                    try { libraryNotes = withContext(Dispatchers.IO) { store.libraryNotes() } }
                                    catch (failure: Exception) {
                                        snackbar.showSnackbar(failure.message ?: context.getString(R.string.no_notes))
                                    }
                                }
                            },
                            onBack = { activeTab = HomeTab.LIBRARY },
                            onOpen = { note ->
                                when {
                                    !note.book.file.exists() -> scope.launch {
                                        snackbar.showSnackbar(context.getString(R.string.book_file_missing))
                                    }
                                    note.cfi.isNotBlank() && note.book.format == "epub" ->
                                        selected = note.book.copy(cfi = note.cfi)
                                    else -> scope.launch {
                                        snackbar.showSnackbar(context.getString(R.string.note_source_unavailable))
                                    }
                                }
                            },
                            onExport = { notes ->
                                pendingExport = notes.toList()
                                exportPicker.launch("inkflow-reading-notes.md")
                            },
                        )
                        HomeTab.VOCABULARY -> NativeVocabularyScreen(
                            store = vocabulary,
                            onBack = { activeTab = HomeTab.LIBRARY },
                            onOpen = { entry ->
                                val source = books.firstOrNull { it.id.toString() == entry.bookId && it.file.exists() }
                                val location = entry.position.orEmpty()
                                val native = Regex("native:(chapter|page):(\\d+):(\\d+)").matchEntire(location)
                                val target = native?.groupValues?.get(2)?.toIntOrNull()
                                val offset = native?.groupValues?.get(3)?.toIntOrNull()
                                when {
                                    source == null -> scope.launch {
                                        snackbar.showSnackbar(context.getString(R.string.book_file_missing))
                                    }
                                    source.format == "epub" && location.startsWith("epubcfi(") -> {
                                        selectedOffset = null
                                        selected = source.copy(cfi = location)
                                    }
                                    native != null && target != null && offset != null &&
                                        ((native.groupValues[1] == "page") == (source.format == "pdf")) -> {
                                        selectedOffset = offset
                                        selected = source.copy(position = target)
                                    }
                                    else -> scope.launch {
                                        snackbar.showSnackbar(context.getString(R.string.note_source_unavailable))
                                    }
                                }
                            },
                        )
                        HomeTab.STATISTICS -> ReadingStatisticsScreen(
                            summary = readingSummary,
                            loading = statisticsLoading,
                            snackbar = snackbar,
                            onBack = { activeTab = HomeTab.LIBRARY },
                        )
                        HomeTab.SETTINGS -> SettingsScreen(
                            store = store,
                            onSynced = { books = store.all() },
                            onBack = { activeTab = HomeTab.LIBRARY },
                            restoring = restoring,
                            exportingBackup = exportingBackup,
                            onRestore = { restorePicker.launch(arrayOf("*/*")) },
                            onExportBackup = { backupExportPicker.launch("Inkflow-Backup.zip") },
                            onWebDavDownloaded = {
                                progressSync.onLibraryRestored()
                                books = store.all()
                            },
                            onEInkChanged = { enabled -> eInkConfig = eInkConfig.copy(enabled = enabled) },
                        )
                    }
                }
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val useRail = maxWidth >= 720.dp
                if (useRail) {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(Modifier.fillMaxHeight()) {
                            HomeTab.entries.forEach { tab ->
                                val label = stringResource(tab.labelRes)
                                NavigationRailItem(
                                    selected = activeTab == tab,
                                    onClick = { activeTab = tab },
                                    icon = { Icon(tab.icon, contentDescription = label) },
                                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                        activeContent(Modifier.weight(1f))
                    }
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                HomeTab.entries.forEach { tab ->
                                    val label = stringResource(tab.labelRes)
                                    NavigationBarItem(
                                        selected = activeTab == tab,
                                        onClick = { activeTab = tab },
                                        icon = { Icon(tab.icon, contentDescription = label) },
                                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    )
                                }
                            }
                        },
                    ) { innerPadding ->
                        activeContent(Modifier.padding(innerPadding))
                    }
                }
            }
        }

        removing?.let { book ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(stringResource(R.string.remove_book)) },
                text = { Text(stringResource(R.string.remove_book_confirm, book.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        removing = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.removeBook(book.id) }
                                books = withContext(Dispatchers.IO) { store.all() }
                            } catch (failure: Exception) {
                                snackbar.showSnackbar(context.getString(R.string.language_error_remove_book))
                            }
                        }
                    }) { Text(stringResource(R.string.remove_book)) }
                },
                dismissButton = {
                    TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }

        pendingRestore?.let { uri ->
            AlertDialog(
                onDismissRequest = { if (!restoring) pendingRestore = null },
                title = { Text(stringResource(R.string.restore_backup)) },
                text = { Text(stringResource(R.string.restore_backup_confirm)) },
                confirmButton = {
                    TextButton(
                        enabled = !restoring,
                        onClick = {
                            if (restoring) return@TextButton
                            restoring = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        NativeSyncIntegration.localMutation { NativeBackupManager(context).restore(uri, store) }
                                        progressSync.onLibraryRestored()
                                    }
                                    books = withContext(Dispatchers.IO) { store.all() }
                                    selected = null
                                    activeTab = HomeTab.LIBRARY
                                    snackbar.showSnackbar(context.getString(R.string.restore_backup_done))
                                } catch (failure: Exception) {
                                    snackbar.showSnackbar(context.getString(R.string.restore_backup_failed, failure.message ?: ""))
                                } finally {
                                    pendingRestore = null
                                    restoring = false
                                }
                            }
                        },
                    ) {
                        if (restoring) CircularProgressIndicator(Modifier.size(20.dp))
                        else Text(stringResource(R.string.restore_backup))
                    }
                },
                dismissButton = {
                    TextButton(enabled = !restoring, onClick = { pendingRestore = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        InkflowSplashOverlay(
            skip = eInkConfig.enabled || initialIntent?.let(::incomingBookUris)?.isNotEmpty() == true,
        )
    }
}
