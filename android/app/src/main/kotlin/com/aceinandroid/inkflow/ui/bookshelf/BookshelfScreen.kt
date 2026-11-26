package com.aceinandroid.inkflow.ui.bookshelf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.BookGroup
import com.aceinandroid.inkflow.BookSortField
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.BookshelfSortStore
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ReaderControlsStore
import com.aceinandroid.inkflow.StoredBook
import com.aceinandroid.inkflow.ui.theme.LocalEInkConfig
import com.aceinandroid.inkflow.core.BookshelfViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    books: List<StoredBook>,
    store: BookStore,
    importing: Boolean,
    snackbar: SnackbarHostState,
    onLibraryChanged: () -> Unit,
    onImport: () -> Unit,
    onOpen: (StoredBook) -> Unit,
    onRemove: (StoredBook) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val eInk = LocalEInkConfig.current
    val sortStore = remember(context) { BookshelfSortStore(context) }
    val controlsStore = remember(context) { ReaderControlsStore(context) }
    var query by remember { mutableStateOf("") }
    var sorting by remember { mutableStateOf(sortStore.load()) }
    var viewMode by remember { mutableStateOf(controlsStore.bookshelfViewMode()) }
    var sortMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var groups by remember(store) { mutableStateOf(store.groups()) }
    var selectedGroupId by remember { mutableStateOf<Long?>(null) }
    var editingGroup by remember { mutableStateOf<BookGroup?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf<BookGroup?>(null) }
    var movingGroup by remember { mutableStateOf<BookGroup?>(null) }
    var movingBook by remember { mutableStateOf<StoredBook?>(null) }
    var detailBook by remember { mutableStateOf<StoredBook?>(null) }

    val filtered = remember(books, query, sorting, selectedGroupId) {
        sorting.apply(books.filter {
            (selectedGroupId == null || it.groupId == selectedGroupId) &&
                (it.title.contains(query, ignoreCase = true) ||
                    it.author.contains(query, ignoreCase = true))
        })
    }

    fun changeGroups(action: () -> Unit, after: () -> Unit = {}) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                groups = withContext(Dispatchers.IO) { store.groups() }
                onLibraryChanged()
                after()
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: context.getString(R.string.group_action_failed))
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.library), fontWeight = FontWeight.Bold)
                        if (books.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "${filtered.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val next = if (viewMode == BookshelfViewMode.GRID) BookshelfViewMode.LIST else BookshelfViewMode.GRID
                        viewMode = next
                        controlsStore.setBookshelfViewMode(next)
                    }) {
                        Icon(
                            if (viewMode == BookshelfViewMode.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = stringResource(if (viewMode == BookshelfViewMode.GRID) R.string.view_list else R.string.view_grid),
                        )
                    }
                    IconButton(onClick = onImport, enabled = !importing) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.import_book))
                    }
                },
            )
        },
        floatingActionButton = {
            if (books.isNotEmpty() && !importing) {
                FloatingActionButton(
                    onClick = onImport,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.import_book))
                }
            }
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text(stringResource(R.string.search_books)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort_books))
                        }
                        DropdownMenu(
                            expanded = sortMenu,
                            onDismissRequest = { sortMenu = false },
                            modifier = Modifier.width(260.dp),
                        ) {
                            BookSortField.entries.forEach { field ->
                                val label = when (field) {
                                    BookSortField.TITLE -> R.string.sort_title
                                    BookSortField.AUTHOR -> R.string.sort_author
                                    BookSortField.LAST_READ -> R.string.sort_last_read
                                    BookSortField.PROGRESS -> R.string.sort_progress
                                    BookSortField.IMPORTED -> R.string.sort_imported
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    trailingIcon = if (sorting.field == field) {
                                        { Icon(Icons.Default.Check, null) }
                                    } else null,
                                    onClick = {
                                        sorting = sorting.copy(field = field)
                                        sortStore.save(sorting)
                                        sortMenu = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                                listOf(false, true).forEachIndexed { index, descending ->
                                    SegmentedButton(
                                        selected = sorting.descending == descending,
                                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                                        onClick = {
                                            sorting = sorting.copy(descending = descending)
                                            sortStore.save(sorting)
                                        },
                                    ) {
                                        Text(stringResource(if (descending) R.string.sort_descending else R.string.sort_ascending))
                                    }
                                }
                            }
                        }
                    }
                },
            )

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = selectedGroupId == null,
                        onClick = { selectedGroupId = null },
                        label = { Text(stringResource(R.string.group_all)) },
                    )
                    FilterChip(
                        selected = selectedGroupId == 0L,
                        onClick = { selectedGroupId = 0L },
                        label = { Text(stringResource(R.string.group_unfiled)) },
                    )
                    groups.forEach { group ->
                        val bringIntoView = remember(group.id) { BringIntoViewRequester() }
                        LaunchedEffect(selectedGroupId, group.id) {
                            if (selectedGroupId == group.id) bringIntoView.bringIntoView()
                        }
                        FilterChip(
                            selected = selectedGroupId == group.id,
                            onClick = { selectedGroupId = group.id },
                            modifier = Modifier.bringIntoViewRequester(bringIntoView),
                            label = {
                                Text(
                                    groupPath(group, groups),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp),
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) },
                        )
                    }
                }
                IconButton(onClick = { creatingGroup = true }) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = stringResource(R.string.group_create),
                    )
                }
                groups.firstOrNull { it.id == selectedGroupId }?.let { selected ->
                    var groupMenu by remember(selected.id) { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { groupMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.group_options))
                        }
                        DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { groupMenu = false; editingGroup = selected },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_move_folder)) },
                                leadingIcon = { Icon(Icons.Default.Folder, null) },
                                onClick = { groupMenu = false; movingGroup = selected },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { groupMenu = false; deletingGroup = selected },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }

            when {
                books.isEmpty() && !importing -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                stringResource(R.string.empty_library),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            FilledTonalButton(onClick = onImport) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.import_book))
                            }
                        }
                    }
                }
                filtered.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.group_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                viewMode == BookshelfViewMode.GRID -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 112.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                    ) {
                        items(filtered, key = { it.id }) { book ->
                            BookGridItem(
                                book = book,
                                eInk = eInk.enabled,
                                onOpen = { onOpen(book) },
                                onDetails = { detailBook = book },
                                onMove = { movingBook = book },
                                onRemove = { onRemove(book) },
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                    ) {
                        items(filtered, key = { it.id }) { book ->
                            BookListItem(
                                book = book,
                                eInk = eInk.enabled,
                                onOpen = { onOpen(book) },
                                onDetails = { detailBook = book },
                                onMove = { movingBook = book },
                                onRemove = { onRemove(book) },
                            )
                        }
                    }
                }
            }
        }
    }

    detailBook?.let { selected ->
        BookDetailScreen(selected, store,
            onDismiss = { detailBook = null },
            onSaved = { detailBook = null; onLibraryChanged() },
            onRead = { detailBook = null; onOpen(selected) })
    }

    if (creatingGroup || editingGroup != null) {
        val current = editingGroup
        var name by remember(current, creatingGroup) { mutableStateOf(current?.name.orEmpty()) }
        val parentId = selectedGroupId?.takeIf { it > 0 } ?: 0L
        AlertDialog(
            onDismissRequest = { creatingGroup = false; editingGroup = null },
            title = { Text(stringResource(if (current == null) R.string.group_create else R.string.group_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = name.trim().isNotEmpty(), onClick = {
                    val value = name
                    creatingGroup = false
                    editingGroup = null
                    var createdId = 0L
                    changeGroups({
                        if (current == null) createdId = store.createGroup(value, parentId).id
                        else store.renameGroup(current.id, value)
                    }, after = { if (createdId > 0) selectedGroupId = createdId })
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { creatingGroup = false; editingGroup = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deletingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            title = { Text(stringResource(R.string.group_delete)) },
            text = { Text(stringResource(R.string.group_delete_confirm, group.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingGroup = null
                    changeGroups({ store.deleteGroup(group.id) }, after = { selectedGroupId = group.parentId })
                }) { Text(stringResource(R.string.group_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingGroup = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    movingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { movingGroup = null },
            title = { Text(stringResource(R.string.group_move_folder)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    (listOf(BookGroup(0, stringResource(R.string.group_top_level), 0)) + groups)
                        .filter { it.id != group.parentId && canBeGroupParent(group.id, it.id, groups) }
                        .forEach { destination ->
                            TextButton(onClick = {
                                movingGroup = null
                                changeGroups({ store.moveGroup(group.id, destination.id) })
                            }) {
                                Text(
                                    if (destination.id == 0L) destination.name else groupPath(destination, groups),
                                    Modifier.fillMaxWidth(),
                                )
                            }
                        }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { movingGroup = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    movingBook?.let { book ->
        AlertDialog(
            onDismissRequest = { movingBook = null },
            title = { Text(stringResource(R.string.group_move_book)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    (listOf(BookGroup(0, stringResource(R.string.group_unfiled), 0)) + groups).forEach { group ->
                        TextButton(
                            onClick = {
                                movingBook = null
                                changeGroups({ store.moveBookToGroup(book.id, group.id) })
                            },
                            enabled = book.groupId != group.id,
                        ) {
                            Text(
                                if (group.id == 0L) group.name else groupPath(group, groups),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { movingBook = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun BookGridItem(
    book: StoredBook,
    eInk: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember(book.id) { mutableStateOf(false) }
    val percent = (book.progress.coerceIn(0.0, 1.0) * 100).roundToInt()

    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (eInk) 0.dp else 1.5.dp),
        border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                BookCoverGraphic(book, Modifier.fillMaxSize())
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                ) {
                    Text(
                        book.format.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.book_options),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    BookItemMenu(menu, onDismiss = { menu = false }, onDetails = onDetails, onMove = onMove, onRemove = onRemove)
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { book.progress.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                    Text(
                        "$percent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookListItem(
    book: StoredBook,
    eInk: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember(book.id) { mutableStateOf(false) }
    val percent = (book.progress.coerceIn(0.0, 1.0) * 100).roundToInt()

    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (eInk) 0.dp else 1.dp),
        border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverGraphic(
                book = book,
                modifier = Modifier
                    .size(54.dp, 76.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { book.progress.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                    Text(
                        "$percent% · ${book.format.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.book_options))
                }
                BookItemMenu(menu, onDismiss = { menu = false }, onDetails = onDetails, onMove = onMove, onRemove = onRemove)
            }
        }
    }
}

@Composable
private fun BookItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.migration_book_details)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
            onClick = { onDismiss(); onDetails() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.group_move_book)) },
            leadingIcon = { Icon(Icons.Default.Folder, null) },
            onClick = { onDismiss(); onMove() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remove_book)) },
            leadingIcon = { Icon(Icons.Default.Delete, null) },
            onClick = { onDismiss(); onRemove() },
        )
    }
}

@Composable
private fun BookCoverGraphic(book: StoredBook, modifier: Modifier) {
    val thumbnail by produceState<Bitmap?>(null, book.cover?.absolutePath) {
        value = withContext(Dispatchers.IO) { book.cover?.let(::decodeCoverThumbnail) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                book.format.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun decodeCoverThumbnail(file: File): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 384) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
} catch (_: Exception) { null }

private fun groupPath(group: BookGroup, groups: List<BookGroup>): String {
    val byId = groups.associateBy(BookGroup::id)
    val names = mutableListOf<String>()
    val seen = mutableSetOf<Long>()
    var current: BookGroup? = group
    while (current != null && seen.add(current.id)) {
        names += current.name
        current = byId[current.parentId]
    }
    return names.asReversed().joinToString(" / ")
}

private fun canBeGroupParent(groupId: Long, parentId: Long, groups: List<BookGroup>): Boolean {
    val byId = groups.associateBy(BookGroup::id)
    val seen = mutableSetOf<Long>()
    var current = parentId
    while (current != 0L) {
        if (!seen.add(current) || current == groupId) return false
        current = byId[current]?.parentId ?: return false
    }
    return true
}
