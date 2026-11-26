package com.aceinandroid.inkflow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class NoteCollection(val label: Int) {
    RECENT(R.string.note_recent), INBOX(R.string.note_inbox), QUESTIONS(R.string.note_questions),
    ARCHIVED(R.string.note_archived),
    FAVORITES(R.string.note_favorites), TRASH(R.string.note_trash);

    fun includes(note: LibraryNote): Boolean = when (this) {
        RECENT -> note.status != "trashed"
        INBOX -> note.status == "inbox"
        QUESTIONS -> note.type == "question" && note.status != "trashed"
        ARCHIVED -> note.status == "archived"
        FAVORITES -> note.favorite && note.status != "trashed"
        TRASH -> note.status == "trashed"
    }
}

private val captureLabels = listOf(
    "highlight" to R.string.highlight, "keyPoint" to R.string.note_key_point,
    "question" to R.string.note_questions, "disagree" to R.string.note_disagree,
    "actionable" to R.string.note_actionable, "later" to R.string.note_later,
    "manual" to R.string.note_manual,
)

private val sourceLabels = listOf(
    "annotation" to R.string.note_source_annotation,
    "difficulty" to R.string.note_source_difficulty,
    "aiSession" to R.string.note_source_ai_session,
    "memoryTopic" to R.string.note_source_memory_topic,
    "knowledgeCard" to R.string.note_source_knowledge_card,
    "guide" to R.string.note_source_guide,
    "quiz" to R.string.note_source_quiz,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryNotesScreen(
    notes: List<LibraryNote>,
    availableBooks: List<StoredBook>,
    loading: Boolean,
    snackbar: SnackbarHostState,
    store: BookStore,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpen: (LibraryNote) -> Unit,
    onExport: (List<LibraryNote>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf(NoteCollection.RECENT) }
    var type by remember { mutableStateOf("") }
    var captureKind by remember { mutableStateOf("") }
    var sourceType by remember { mutableStateOf("") }
    var bookId by remember { mutableStateOf<Long?>(null) }
    var tag by remember { mutableStateOf<String?>(null) }
    var bookMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    var captureMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var newNoteMenu by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editing by remember { mutableStateOf<LibraryNote?>(null) }
    var editorVersion by remember { mutableStateOf(0) }
    var historyOpen by remember { mutableStateOf(false) }
    var historyLoading by remember { mutableStateOf(false) }
    var revisions by remember { mutableStateOf<List<ReadingNoteRevision>>(emptyList()) }
    var restoreCandidate by remember { mutableStateOf<ReadingNoteRevision?>(null) }
    var editorBlocks by remember { mutableStateOf<List<WorkspaceBlock>>(emptyList()) }
    var blocksLoading by remember { mutableStateOf(false) }
    var blocksVersion by remember { mutableStateOf(0) }
    LaunchedEffect(editing?.workspaceId, blocksVersion) {
        val noteId = editing?.workspaceId
        if (noteId == null) {
            editorBlocks = emptyList()
            return@LaunchedEffect
        }
        blocksLoading = true
        try {
            editorBlocks = withContext(Dispatchers.IO) {
                ReadingNoteWorkspaceStore(store).blocks(noteId)
            }
        } catch (failure: Exception) {
            snackbar.showSnackbar(failure.message ?: context.getString(R.string.note_operation_failed))
        } finally { blocksLoading = false }
    }
    fun changeBlock(change: (ReadingNoteWorkspaceStore, String) -> Unit,
                    onSuccess: () -> Unit = {}) {
        val noteId = editing?.workspaceId ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { change(ReadingNoteWorkspaceStore(store), noteId) }
                onSuccess()
                blocksVersion++
                onRefresh()
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: context.getString(R.string.note_operation_failed))
            }
        }
    }
    val books = availableBooks
    val tags = remember(notes) { notes.flatMap(LibraryNote::tags).distinct().sorted() }
    val captureOptions = remember(notes) { notes.map(LibraryNote::type).toSet() }
    val sourceOptions = remember(notes) { notes.flatMap { it.sourceTypes }.toSet() }
    val filtered = remember(notes, query, collection, type, captureKind, sourceType, bookId, tag) {
        val term = query.trim()
        notes.filter { note ->
            collection.includes(note) && (type.isEmpty() || note.type == type) &&
                (captureKind.isEmpty() || note.type == captureKind) &&
                (sourceType.isEmpty() || if (sourceType == "none") note.sourceTypes.isEmpty()
                    else sourceType in note.sourceTypes) &&
                (bookId == null || note.book.id == bookId) &&
                (tag == null || tag in note.tags) &&
                (term.isEmpty() || listOf(note.book.title, note.title, note.content,
                    note.chapter, note.readerNote).plus(note.tags).any {
                    it.contains(term, ignoreCase = true)
                })
        }
    }
    fun openEditor(note: LibraryNote) {
        if (note.workspaceId != null) {
            editing = note
            return
        }
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val workspace = ReadingNoteWorkspaceStore(store)
                    val id = workspace.ensureWorkspace(note)
                    store.libraryNotes().first { it.workspaceId == id }
                }
                editing = updated
                onRefresh()
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: context.getString(R.string.note_operation_failed))
            }
        }
    }
    fun runBatch(action: NoteBatchAction) {
        val selected = notes.filter { it.identity in selectedIds }
        if (selected.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ReadingNoteWorkspaceStore(store).batchUpdate(selected, action) }
                selectedIds = emptySet()
                onRefresh()
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: context.getString(R.string.note_operation_failed))
            }
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(if (selectedIds.isEmpty()) stringResource(R.string.reading_notes)
            else stringResource(R.string.note_selected, selectedIds.size)) }, actions = {
            if (selectedIds.isNotEmpty()) {
                if (collection == NoteCollection.TRASH) IconButton(onClick = { runBatch(NoteBatchAction.RESTORE) }) {
                    Icon(Icons.Default.RestoreFromTrash, stringResource(R.string.note_restore))
                } else {
                    IconButton(onClick = { runBatch(NoteBatchAction.FAVORITE) }) {
                        Icon(Icons.Default.Star, stringResource(R.string.note_favorite))
                    }
                    IconButton(onClick = { runBatch(NoteBatchAction.ARCHIVE) }) {
                        Icon(Icons.Default.Archive, stringResource(R.string.note_archive))
                    }
                    IconButton(onClick = { runBatch(NoteBatchAction.TRASH) }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.note_trash))
                    }
                }
            } else {
                Box {
                    IconButton(onClick = { newNoteMenu = true }, enabled = books.isNotEmpty()) {
                        Icon(Icons.Default.Add, stringResource(R.string.note_new))
                    }
                    DropdownMenu(expanded = newNoteMenu, onDismissRequest = { newNoteMenu = false }) {
                        books.forEach { book -> DropdownMenuItem(text = { Text(book.title) }, onClick = {
                            newNoteMenu = false
                            editing = LibraryNote(0, book, "", "", "", "manual", "")
                        }) }
                    }
                }
                IconButton(onClick = { onExport(filtered) }, enabled = filtered.isNotEmpty() && !loading) {
                    Icon(Icons.Default.FileDownload, stringResource(R.string.export_notes))
                }
            }
        }, navigationIcon = {
            IconButton(onClick = { if (selectedIds.isNotEmpty()) selectedIds = emptySet() else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.search_notes)) },
                leadingIcon = { Icon(Icons.Default.Search, null) })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteCollection.entries.forEach { option ->
                    FilterChip(selected = collection == option, onClick = {
                        collection = option; selectedIds = emptySet()
                    }, label = { Text(stringResource(R.string.note_collection_count,
                        stringResource(option.label), notes.count {
                            (bookId == null || it.book.id == bookId) && option.includes(it)
                        })) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf("" to R.string.all_notes, "highlight" to R.string.highlight,
                    "underline" to R.string.underline, "bookmark" to R.string.bookmarks).forEach { (value, label) ->
                    FilterChip(selected = type == value, onClick = { type = value },
                        label = { Text(stringResource(label)) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { bookMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null)
                        Text(books.firstOrNull { it.id == bookId }?.title ?: stringResource(R.string.all_books),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.all_books)) }, onClick = {
                            bookId = null; bookMenu = false
                        })
                        books.forEach { book -> DropdownMenuItem(text = { Text(book.title) }, onClick = {
                            bookId = book.id; bookMenu = false
                        }) }
                    }
                }
                if (tags.isNotEmpty()) Box {
                    TextButton(onClick = { tagMenu = true }) {
                        Icon(Icons.Default.Sell, null)
                        Text(tag ?: stringResource(R.string.all_tags))
                    }
                    DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.all_tags)) }, onClick = {
                            tag = null; tagMenu = false
                        })
                        tags.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = {
                            tag = option; tagMenu = false
                        }) }
                    }
                }
                Box {
                    TextButton(onClick = { captureMenu = true }) {
                        Icon(Icons.Default.Category, null)
                        Text(captureLabels.firstOrNull { it.first == captureKind }?.let {
                            stringResource(it.second)
                        } ?: stringResource(R.string.note_all_capture_kinds))
                    }
                    DropdownMenu(expanded = captureMenu, onDismissRequest = { captureMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_all_capture_kinds)) },
                            onClick = { captureKind = ""; captureMenu = false })
                        captureLabels.filter { it.first in captureOptions }.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) },
                                onClick = { captureKind = value; captureMenu = false })
                        }
                    }
                }
                Box {
                    TextButton(onClick = { sourceMenu = true }) {
                        Icon(Icons.Default.Link, null)
                        Text(when (sourceType) {
                            "none" -> stringResource(R.string.note_source_none)
                            else -> sourceLabels.firstOrNull { it.first == sourceType }?.let {
                                stringResource(it.second)
                            } ?: stringResource(R.string.note_all_sources)
                        })
                    }
                    DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_all_sources)) },
                            onClick = { sourceType = ""; sourceMenu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.note_source_none)) },
                            onClick = { sourceType = "none"; sourceMenu = false })
                        sourceLabels.filter { it.first in sourceOptions }.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) },
                                onClick = { sourceType = value; sourceMenu = false })
                        }
                    }
                }
            }
            if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            } else if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_notes), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = LibraryNote::identity) { note ->
                    val selected = note.identity in selectedIds
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (note.type) {
                                "bookmark" -> Icons.Default.Bookmark
                                "underline" -> Icons.Default.FormatUnderlined
                                else -> Icons.Default.Highlight
                            }, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).combinedClickable(
                                onClick = {
                                    if (selectedIds.isNotEmpty()) selectedIds = if (selected) selectedIds - note.identity
                                        else selectedIds + note.identity
                                    else if (note.workspaceId != null) editing = note else onOpen(note)
                                },
                                onLongClick = { selectedIds = selectedIds + note.identity }
                            )) {
                                if (note.title.isNotBlank()) Text(note.title, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(note.content.ifBlank { note.chapter.ifBlank { note.readerNote } }, maxLines = 3,
                                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                if (note.readerNote.isNotBlank() && note.content.isNotBlank()) Text(note.readerNote,
                                    modifier = Modifier.padding(top = 4.dp), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Text(listOf(note.book.title, note.chapter).filter(String::isNotBlank)
                                    .joinToString(" / "), modifier = Modifier.padding(top = 6.dp),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" · "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            if (note.favorite && selectedIds.isEmpty()) Icon(Icons.Default.Star, null,
                                tint = MaterialTheme.colorScheme.primary)
                            if (selectedIds.isEmpty()) IconButton(onClick = { openEditor(note) }) {
                                Icon(Icons.Default.Edit, stringResource(R.string.note_edit))
                            }
                            IconButton(onClick = {
                                selectedIds = if (selected) selectedIds - note.identity
                                    else selectedIds + note.identity
                            }) {
                                Icon(if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    stringResource(R.string.note_select, note.title.ifBlank {
                                        note.content.ifBlank { note.book.title }
                                    }))
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { note -> key(note.identity, editorVersion) {
        NoteEditorDialog(note, editorBlocks, blocksLoading,
            onDismiss = { editing = null; historyOpen = false }, onOpenSource = {
            editing = null; onOpen(note)
        }, onHistory = {
            historyOpen = true
            historyLoading = true
            revisions = emptyList()
            scope.launch {
                try {
                    revisions = withContext(Dispatchers.IO) {
                        ReadingNoteWorkspaceStore(store).revisions(note.workspaceId!!)
                    }
                } catch (failure: Exception) {
                    historyOpen = false
                    snackbar.showSnackbar(failure.message ?: context.getString(R.string.note_operation_failed))
                } finally { historyLoading = false }
            }
        }, onSave = { title, body, tagNames, status, favorite ->
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val workspace = ReadingNoteWorkspaceStore(store)
                        if (note.workspaceId == null) workspace.createManual(note.book.id,
                            title, body, tagNames, status, favorite)
                        else workspace.save(note.workspaceId, title, body, tagNames, status, favorite)
                    }
                    editing = null
                    if (note.workspaceId == null) {
                        collection = NoteCollection.RECENT
                        type = ""
                        bookId = note.book.id
                        tag = null
                        query = ""
                    }
                    onRefresh()
                } catch (failure: Exception) {
                    snackbar.showSnackbar(failure.message ?: context.getString(R.string.note_operation_failed))
                }
            }
        }, onAddChecklist = { content, onSuccess ->
            changeBlock({ workspace, id -> workspace.addChecklistItem(id, content) }, onSuccess)
        }, onUpdateChecklist = { block, content, checked ->
            changeBlock({ workspace, id -> workspace.updateChecklistItem(id, block.id, content, checked) })
        }, onDeleteChecklist = { block ->
            changeBlock({ workspace, id -> workspace.deleteChecklistItem(id, block.id) })
        }, onDelete = {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ReadingNoteWorkspaceStore(store).deletePermanently(note.workspaceId!!)
                    }
                    editing = null
                    onRefresh()
                } catch (failure: Exception) {
                    snackbar.showSnackbar(failure.message ?: context.getString(R.string.note_operation_failed))
                }
            }
        })
    } }
    if (historyOpen && editing != null) {
        AlertDialog(onDismissRequest = { historyOpen = false },
            title = { Text(stringResource(R.string.note_history)) },
            text = {
                if (historyLoading) CircularProgressIndicator()
                else if (revisions.isEmpty()) Text(stringResource(R.string.note_no_history))
                else Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    revisions.forEachIndexed { index, revision ->
                        TextButton(onClick = { restoreCandidate = revision },
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.note_version_number, revisions.size - index),
                                    fontWeight = FontWeight.SemiBold)
                                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(revision.createdAt)),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(revision.title.ifBlank { revision.body }, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { historyOpen = false }) {
                Text(stringResource(R.string.close))
            } })
    }
    restoreCandidate?.let { revision ->
        AlertDialog(onDismissRequest = { restoreCandidate = null },
            title = { Text(stringResource(R.string.note_restore_version)) },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.note_restore_version_confirm))
                if (revision.title.isNotBlank()) Text(revision.title,
                    fontWeight = FontWeight.SemiBold)
                Text(revision.body.ifBlank { stringResource(R.string.note_empty_body) })
                if (revision.tags.isNotEmpty()) Text(revision.tags.joinToString(" · "))
            } },
            confirmButton = { TextButton(onClick = {
                val noteId = editing?.workspaceId ?: return@TextButton
                scope.launch {
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            ReadingNoteWorkspaceStore(store).restoreRevision(noteId, revision.id)
                            store.libraryNotes().first { it.workspaceId == noteId }
                        }
                        restoreCandidate = null
                        historyOpen = false
                        editorVersion++
                        editing = updated
                        onRefresh()
                    } catch (failure: Exception) {
                        snackbar.showSnackbar(failure.message ?: context.getString(R.string.note_operation_failed))
                    }
                }
            }) { Text(stringResource(R.string.note_restore_version)) } },
            dismissButton = { TextButton(onClick = { restoreCandidate = null }) {
                Text(stringResource(R.string.cancel))
            } })
    }
}

@Composable
private fun NoteEditorDialog(
    note: LibraryNote,
    blocks: List<WorkspaceBlock>,
    blocksLoading: Boolean,
    onDismiss: () -> Unit,
    onOpenSource: () -> Unit,
    onHistory: () -> Unit,
    onSave: (String, String, List<String>, String, Boolean) -> Unit,
    onAddChecklist: (String, () -> Unit) -> Unit,
    onUpdateChecklist: (WorkspaceBlock, String?, Boolean?) -> Unit,
    onDeleteChecklist: (WorkspaceBlock) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(note.identity) { mutableStateOf(note.title) }
    var body by remember(note.identity) { mutableStateOf(note.readerNote) }
    var tags by remember(note.identity) { mutableStateOf(note.tags.joinToString(", ")) }
    var status by remember(note.identity) { mutableStateOf(note.status) }
    var favorite by remember(note.identity) { mutableStateOf(note.favorite) }
    var statusMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newChecklistItem by remember { mutableStateOf("") }
    var editingChecklist by remember { mutableStateOf<WorkspaceBlock?>(null) }
    var checklistDraft by remember { mutableStateOf("") }
    var deletingChecklist by remember { mutableStateOf<WorkspaceBlock?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (note.workspaceId == null) R.string.note_new
                else R.string.reading_notes), modifier = Modifier.weight(1f))
            IconButton(onClick = { favorite = !favorite }) {
                Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    stringResource(R.string.note_favorite))
            }
        } },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.note_title)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (note.content.isNotBlank()) Text(note.content, style = MaterialTheme.typography.bodyMedium)
            Text(listOf(note.book.title, note.chapter).filter(String::isNotBlank).joinToString(" / "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (note.cfi.isNotBlank() && note.book.format == "epub")
                TextButton(onClick = onOpenSource) { Text(stringResource(R.string.note_open_source)) }
            if (note.workspaceId != null) TextButton(onClick = onHistory) {
                Icon(Icons.Default.History, null)
                Text(stringResource(R.string.note_history))
            }
            OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.note_body)) },
                modifier = Modifier.fillMaxWidth(), minLines = 4)
            if (note.workspaceId != null) {
                Text(stringResource(R.string.note_checklist),
                    style = MaterialTheme.typography.titleSmall)
                if (blocksLoading) CircularProgressIndicator()
                blocks.filter { it.type == "checklist" }.forEach { block ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = block.checked,
                            onCheckedChange = { onUpdateChecklist(block, null, it) },
                            enabled = block.origin == "user")
                        Text(block.content, modifier = Modifier.weight(1f))
                        if (block.origin == "user") {
                            IconButton(onClick = {
                                editingChecklist = block
                                checklistDraft = block.content
                            }) { Icon(Icons.Default.Edit, stringResource(R.string.note_edit_checklist)) }
                            IconButton(onClick = { deletingChecklist = block }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.note_delete_checklist))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newChecklistItem, { newChecklistItem = it },
                        label = { Text(stringResource(R.string.note_new_checklist_item)) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (newChecklistItem.isNotBlank())
                                onAddChecklist(newChecklistItem) { newChecklistItem = "" }
                        }))
                    IconButton(onClick = {
                        onAddChecklist(newChecklistItem) { newChecklistItem = "" }
                    }, enabled = newChecklistItem.isNotBlank()) {
                        Icon(Icons.Default.Add, stringResource(R.string.note_add_checklist))
                    }
                }
                if (blocks.any { it.type == "ai" }) {
                    Text(stringResource(R.string.note_ai_content),
                        style = MaterialTheme.typography.titleSmall)
                    blocks.filter { it.type == "ai" }.forEach { block ->
                        Text(block.content)
                        val provenance = listOf(block.provider, block.model)
                            .filter(String::isNotBlank).joinToString(" · ")
                        if (provenance.isNotBlank()) Text(provenance,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            body = listOf(body.trim(), block.content.trim())
                                .filter(String::isNotBlank).joinToString("\n\n")
                        }) {
                            Icon(Icons.Default.ContentCopy, null)
                            Text(stringResource(R.string.note_copy_ai_to_body))
                        }
                    }
                }
            }
            OutlinedTextField(tags, { tags = it }, label = { Text(stringResource(R.string.all_tags)) },
                modifier = Modifier.fillMaxWidth())
            Box {
                TextButton(onClick = { statusMenu = true }) {
                    Text(stringResource(when (status) {
                        "inbox" -> R.string.note_inbox
                        "archived" -> R.string.note_archived
                        "trashed" -> R.string.note_trash
                        else -> R.string.note_active
                    }))
                }
                DropdownMenu(statusMenu, onDismissRequest = { statusMenu = false }) {
                    listOf("inbox" to R.string.note_inbox, "active" to R.string.note_active,
                        "archived" to R.string.note_archived, "trashed" to R.string.note_trash)
                        .forEach { (value, label) -> DropdownMenuItem(
                            text = { Text(stringResource(label)) }, onClick = {
                                status = value; statusMenu = false
                            }) }
                }
            }
            if (status == "trashed" && note.workspaceId != null) TextButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, null)
                Text(stringResource(R.string.note_delete_permanently))
            }
        } },
        confirmButton = { TextButton(enabled = note.workspaceId != null || title.isNotBlank() || body.isNotBlank(), onClick = {
            onSave(title, body, tags.split(',', '，'), status, favorite)
        }) { Text(stringResource(if (note.workspaceId == null) R.string.save
            else R.string.note_save_version)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.note_delete_permanently)) },
        text = { Text(stringResource(R.string.note_delete_confirm)) },
        confirmButton = { TextButton(onClick = onDelete) {
            Text(stringResource(R.string.note_delete_permanently))
        } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) {
            Text(stringResource(R.string.cancel))
        } })
    editingChecklist?.let { block -> AlertDialog(onDismissRequest = { editingChecklist = null },
        title = { Text(stringResource(R.string.note_edit_checklist)) },
        text = { OutlinedTextField(checklistDraft, { checklistDraft = it },
            label = { Text(stringResource(R.string.note_checklist_item)) }) },
        confirmButton = { TextButton(enabled = checklistDraft.isNotBlank(), onClick = {
            onUpdateChecklist(block, checklistDraft, null)
            editingChecklist = null
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { editingChecklist = null }) {
            Text(stringResource(R.string.cancel))
        } }) }
    deletingChecklist?.let { block -> AlertDialog(onDismissRequest = { deletingChecklist = null },
        title = { Text(stringResource(R.string.note_delete_checklist)) },
        text = { Text(block.content) },
        confirmButton = { TextButton(onClick = {
            onDeleteChecklist(block)
            deletingChecklist = null
        }) { Text(stringResource(R.string.note_delete_checklist)) } },
        dismissButton = { TextButton(onClick = { deletingChecklist = null }) {
            Text(stringResource(R.string.cancel))
        } }) }
}
