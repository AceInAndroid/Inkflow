package com.aceinandroid.inkflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aceinandroid.inkflow.core.sync.VocabularyDelta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeVocabularyScreen(store: NativeVocabularyStore, onBack: () -> Unit,
                                    onOpen: (NativeVocabularyItem) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pronouncer = remember(context) { VocabularyPronouncer(context) }
    var playingEntryId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(pronouncer) {
        pronouncer.onFailure = {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.vocabulary_pronunciation_failed)) }
        }
        onDispose { pronouncer.close() }
    }
    DisposableEffect(pronouncer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) pronouncer.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var entries by remember { mutableStateOf<List<NativeVocabularyItem>>(emptyList()) }
    var dueOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<NativeVocabularyItem?>(null) }
    DisposableEffect(selected?.id) { onDispose { pronouncer.stop() } }
    var removing by remember { mutableStateOf<NativeVocabularyItem?>(null) }
    var enrichmentRevision by remember { mutableIntStateOf(0) }
    suspend fun reload() {
        try { entries = withContext(Dispatchers.IO) { store.all() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            snackbar.showSnackbar(failure.message ?: context.getString(R.string.vocabulary_load_failed))
        }
    }
    DisposableEffect(context, lifecycleOwner) {
        val changes = WorkManager.getInstance(context)
            .getWorkInfosByTagLiveData(VocabularyEnrichmentWorker.TAG)
        val observer = Observer<List<WorkInfo>> { enrichmentRevision++ }
        changes.observe(lifecycleOwner, observer)
        onDispose { changes.removeObserver(observer) }
    }
    LaunchedEffect(store, enrichmentRevision) { reload() }
    val now = System.currentTimeMillis()
    val visible = entries.filter {
        (!dueOnly || it.isDue(now)) && (query.isBlank() ||
            it.word.contains(query, ignoreCase = true) ||
            it.definitionCn.orEmpty().contains(query, ignoreCase = true) ||
            it.definitionEn.orEmpty().contains(query, ignoreCase = true))
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.vocabulary_title)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryValue(entries.size, stringResource(R.string.vocabulary_total))
                SummaryValue(entries.count { it.isDue(now) }, stringResource(R.string.vocabulary_due))
                SummaryValue(entries.count { it.isMastered }, stringResource(R.string.vocabulary_mastered))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !dueOnly, onClick = { dueOnly = false },
                    label = { Text(stringResource(R.string.vocabulary_all)) })
                FilterChip(selected = dueOnly, onClick = { dueOnly = true },
                    label = { Text(stringResource(R.string.vocabulary_due)) })
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.vocabulary_search)) })
            if (visible.isEmpty()) {
                Text(stringResource(if (dueOnly) R.string.vocabulary_no_due else
                    R.string.vocabulary_empty), Modifier.padding(top = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth().clickable { selected = entry }
                        .padding(vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.word, Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            if (entry.isDue(now)) Text(stringResource(R.string.vocabulary_review_now),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        val definition = entry.definitionCn?.takeIf(String::isNotBlank)
                            ?: entry.definitionEn?.takeIf(String::isNotBlank)
                        if (definition != null) Text(definition, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        entry.bookTitle?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null },
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.word, Modifier.weight(1f), maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                IconButton(onClick = {
                    if (playingEntryId == entry.id && pronouncer.state != PronunciationState.IDLE)
                        pronouncer.stop()
                    else {
                        playingEntryId = entry.id
                        pronouncer.play(entry.word, entry.audioUrl)
                    }
                }) {
                    when {
                        playingEntryId == entry.id && pronouncer.state == PronunciationState.LOADING ->
                            CircularProgressIndicator(Modifier.padding(14.dp), strokeWidth = 2.dp)
                        playingEntryId == entry.id && pronouncer.state == PronunciationState.PLAYING ->
                            Icon(Icons.Default.Stop, stringResource(R.string.stop))
                        else -> Icon(Icons.AutoMirrored.Filled.VolumeUp,
                            stringResource(R.string.vocabulary_pronunciation))
                    }
                }
            } },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.phonetic?.takeIf(String::isNotBlank)?.let { Text(it) }
                entry.partOfSpeech?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(entry.contextualDefinition?.takeIf(String::isNotBlank)
                    ?: entry.definitionCn?.takeIf(String::isNotBlank)
                    ?: entry.definitionEn?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.vocabulary_no_definition))
                entry.definitionEn?.takeIf { it.isNotBlank() && it != entry.definitionCn &&
                    it != entry.contextualDefinition }?.let { Text(it) }
                entry.sourceSentence.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                entry.exampleSentence?.takeIf(String::isNotBlank)?.let { Text(it) }
                val source = listOfNotNull(entry.bookTitle, entry.chapterTitle)
                    .filter(String::isNotBlank).joinToString(" / ")
                if (source.isNotBlank()) Text(source, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.vocabulary_next_review,
                    DateFormat.getDateInstance(DateFormat.MEDIUM, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(entry.nextReviewAt))),
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    VocabularyAction(stringResource(R.string.vocabulary_hard)) {
                        scope.launch {
                            try { withContext(Dispatchers.IO) { store.review(entry.id, VocabularyReview.HARD) }; reload() }
                            catch (failure: Exception) { snackbar.showSnackbar(failure.message.orEmpty()) }
                            selected = null
                        }
                    }
                    VocabularyAction(stringResource(R.string.vocabulary_familiar)) {
                        scope.launch {
                            try { withContext(Dispatchers.IO) { store.review(entry.id, VocabularyReview.FAMILIAR) }; reload() }
                            catch (failure: Exception) { snackbar.showSnackbar(failure.message.orEmpty()) }
                            selected = null
                        }
                    }
                    VocabularyAction(stringResource(R.string.vocabulary_mastered)) {
                        scope.launch {
                            try { withContext(Dispatchers.IO) { store.review(entry.id, VocabularyReview.MASTERED) }; reload() }
                            catch (failure: Exception) { snackbar.showSnackbar(failure.message.orEmpty()) }
                            selected = null
                        }
                    }
                }
            } },
            confirmButton = { Row(verticalAlignment = Alignment.CenterVertically) {
                if (!entry.position.isNullOrBlank()) TextButton(onClick = {
                    selected = null
                    onOpen(entry)
                }) { Text(stringResource(R.string.vocabulary_open_source)) }
                IconButton(onClick = { selected = null; removing = entry }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.vocabulary_remove))
                }
            } },
            dismissButton = { TextButton(onClick = { selected = null }) {
                Text(stringResource(R.string.close))
            } })
    }
    removing?.let { entry ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.vocabulary_remove)) },
            text = { Text(stringResource(R.string.vocabulary_remove_confirm, entry.word)) },
            confirmButton = { TextButton(onClick = {
                removing = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            NativeIncrementalSync(context).deleteVocabulary(VocabularyDelta.normalizeWord(entry.word))
                        }
                        reload()
                    }
                    catch (failure: Exception) { snackbar.showSnackbar(failure.message.orEmpty()) }
                }
            }) { Text(stringResource(R.string.vocabulary_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) {
                Text(stringResource(R.string.cancel))
            } })
    }
}

@Composable
private fun SummaryValue(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun VocabularyAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}
