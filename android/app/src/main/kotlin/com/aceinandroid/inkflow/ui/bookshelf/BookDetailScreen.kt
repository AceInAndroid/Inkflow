package com.aceinandroid.inkflow.ui.bookshelf

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.StoredAnnotation
import com.aceinandroid.inkflow.StoredBook
import com.aceinandroid.inkflow.decodeBookBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local metadata and notes, backed by the existing book row and annotation projection. */
@Composable
fun BookDetailScreen(book: StoredBook, store: BookStore, onDismiss: () -> Unit,
    onSaved: () -> Unit, onRead: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    var coverUri by remember(book.id) { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showNotes by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> coverUri = uri }
    val cover by produceState<Bitmap?>(null, book.cover, coverUri) {
        value = withContext(Dispatchers.IO) { runCatching {
            if (coverUri != null) decodeBookBitmap {
                context.contentResolver.openInputStream(coverUri!!) ?: error("Cannot open cover")
            } else book.cover?.let { file -> decodeBookBitmap { file.inputStream() } }
        }.getOrNull() }
    }
    val details by produceState<Pair<Int, List<StoredAnnotation>>?>(null, book.id) {
        value = withContext(Dispatchers.IO) {
            val seconds = store.readingSummary().books.firstOrNull { it.id == book.id }?.seconds ?: 0
            seconds to store.annotations(book.id)
        }
    }
    Dialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.migration_book_details), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.close)) }
                }
                cover?.let { Image(it.asImageBitmap(), stringResource(R.string.migration_cover),
                    Modifier.fillMaxWidth().height(240.dp), contentScale = ContentScale.Fit) }
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !saving) {
                    Text(stringResource(R.string.migration_change_cover))
                }
                OutlinedTextField(title, onValueChange = { title = it.take(500) }, enabled = !saving,
                    label = { Text(stringResource(R.string.migration_book_title)) },
                    isError = title.isBlank(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(author, onValueChange = { author = it.take(500) }, enabled = !saving,
                    label = { Text(stringResource(R.string.migration_book_author)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.migration_book_format, book.format.uppercase()))
                Text(stringResource(R.string.migration_book_progress, (book.progress.coerceIn(0.0, 1.0) * 100).toInt()))
                details?.let { (seconds, notes) ->
                    Text(stringResource(R.string.migration_book_time, seconds / 60))
                    OutlinedButton(onClick = { showNotes = !showNotes }) {
                        Text(stringResource(R.string.migration_book_notes, notes.size))
                    }
                    if (showNotes) {
                        if (notes.isEmpty()) Text(stringResource(R.string.migration_no_notes))
                        notes.forEach { note ->
                            HorizontalDivider()
                            Text(note.chapter, style = MaterialTheme.typography.labelLarge)
                            Text(note.content)
                            if (note.readerNote.isNotBlank()) Text(note.readerNote)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = !saving && title.isNotBlank(), onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.saveBookMetadata(book.id, title, author, coverUri) }
                                onSaved()
                            } catch (failure: Exception) {
                                error = failure.message ?: context.getString(R.string.migration_metadata_failed)
                            } finally { saving = false }
                        }
                    }) { Text(stringResource(R.string.migration_save_details)) }
                    TextButton(onClick = onRead, enabled = !saving) { Text(stringResource(R.string.migration_read_book)) }
                }
            }
        }
    }
}
