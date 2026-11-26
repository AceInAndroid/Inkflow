package com.aceinandroid.inkflow.ui.ai

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.PronunciationState
import com.aceinandroid.inkflow.VocabularyPronouncer
import com.aceinandroid.inkflow.dictionary.DictionarySettingsPanel
import com.aceinandroid.inkflow.translate.TranslationPanel
import com.aceinandroid.inkflow.core.translate.TranslationRequest
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ai.NativeOfflineDictionary
import com.aceinandroid.inkflow.core.OfflineLookupEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookupSheet(
    query: String,
    store: BookStore,
    onAskAi: () -> Unit,
    onAddVocabulary: (definition: String) -> Unit,
    onDismiss: () -> Unit,
    onAddVocabularyEntry: ((OfflineLookupEntry) -> Unit)? = null,
    onTranslationRequest: ((TranslationRequest) -> Unit)? = null,
) {
    val context = LocalContext.current
    val dictionary = remember(context, store) { NativeOfflineDictionary(context, store) }
    val scope = rememberCoroutineScope()
    val pronouncer = remember(context) { VocabularyPronouncer(context) }
    var pronunciationFailed by remember(query) { mutableStateOf(false) }
    DisposableEffect(pronouncer) {
        pronouncer.onFailure = { pronunciationFailed = true }
        onDispose { pronouncer.close() }
    }
    DisposableEffect(query) { onDispose { pronouncer.stop() } }
    var showDictionaries by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(false) }
    var dictionaryRevision by remember { mutableStateOf(0) }

    var loading by remember(query) { mutableStateOf(true) }
    var entry by remember(query) { mutableStateOf<OfflineLookupEntry?>(null) }
    var onlineError by remember(query) { mutableStateOf<String?>(null) }
    var searchedOnline by remember(query) { mutableStateOf(false) }
    val onlineNotFound = stringResource(R.string.dictionary_online_not_found)

    LaunchedEffect(query, dictionaryRevision) {
        loading = true
        onlineError = null
        searchedOnline = false
        try {
            entry = withContext(Dispatchers.IO) { dictionary.lookupOffline(query) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onlineError = context.getString(R.string.localization_dictionary_lookup_failed) }
        finally { loading = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry?.headword ?: query.trim(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    val meta = listOfNotNull(entry?.phonetic, entry?.partOfSpeech, entry?.sourceLabel)
                        .joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.lookup_searching))
                }
                entry != null -> {
                    entry!!.definitions.forEachIndexed { idx, def ->
                        Text("${idx + 1}. $def", style = MaterialTheme.typography.bodyMedium)
                    }
                    entry!!.example?.takeIf(String::isNotBlank)?.let { ex ->
                        Text(
                            text = "“$ex”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    Text(
                        text = onlineError ?: stringResource(R.string.lookup_offline_not_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (entry != null) onlineError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!searchedOnline) {
                    OutlinedButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            searchedOnline = true
                            onlineError = null
                            scope.launch {
                                try {
                                    val found = withContext(Dispatchers.IO) { dictionary.lookupOnline(query) }
                                    if (found != null) entry = found else onlineError = onlineNotFound
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) {
                                    onlineError = context.getString(R.string.localization_dictionary_online_failed)
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Public, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.lookup_search_online))
                    }
                }
                FilledTonalButton(onClick = {
                    onDismiss()
                    onAskAi()
                }) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ai_explain))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !loading, onClick = {
                    val resolved = entry
                    if (resolved != null && onAddVocabularyEntry != null) onAddVocabularyEntry(resolved)
                    else onAddVocabulary(resolved?.definitions?.joinToString("\n").orEmpty())
                }) {
                    Icon(Icons.Default.School, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.vocabulary_add))
                }
            }
            OutlinedButton(enabled = !loading, onClick = {
                pronunciationFailed = false
                pronouncer.play(entry?.headword ?: query.trim(), null)
            }) {
                Text(stringResource(R.string.dictionary_pronounce))
                if (pronouncer.state != PronunciationState.IDLE) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            if (pronunciationFailed) Text(stringResource(R.string.dictionary_pronounce_failed))
            OutlinedButton(onClick = { showTranslation = !showTranslation }) {
                Text(stringResource(R.string.dictionary_translation_show))
            }
            if (showTranslation) TranslationPanel(query) { request ->
                onDismiss()
                if (onTranslationRequest != null) onTranslationRequest(request) else onAskAi()
            }
            OutlinedButton(onClick = { showDictionaries = !showDictionaries }) {
                Text(stringResource(R.string.dictionary_manage))
            }
            if (showDictionaries) DictionarySettingsPanel(onChanged = { dictionaryRevision++ })
        }
    }
}
