package com.aceinandroid.inkflow.dictionary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.core.dictionary.ImportedDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Embeddable settings destination. The host supplies navigation/back chrome. */
@Composable
fun DictionarySettingsPanel(modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val imports = remember(context) { NativeDictionaryImports(context) }
    val scope = rememberCoroutineScope()
    var dictionaries by remember { mutableStateOf<List<ImportedDictionary>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val success = stringResource(R.string.dictionary_import_success)
    fun runOperation(operation: () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                dictionaries = withContext(Dispatchers.IO) { operation(); imports.repository.list() }
                message = success
                onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runOperation { imports.importDocument(it) } }
    }
    LaunchedEffect(imports) { dictionaries = withContext(Dispatchers.IO) { imports.repository.list() } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.dictionary_settings_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dictionary_import_help), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.dictionary_import_file))
        }
        OutlinedButton(enabled = !busy, onClick = { runOperation { imports.importBundledChinese() } }) {
            Text(stringResource(R.string.dictionary_import_chinese))
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        dictionaries.forEach { dictionary ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(dictionary.name)
                    Text(stringResource(R.string.dictionary_entry_count, dictionary.entryCount),
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = dictionary.enabled, enabled = !busy,
                    onCheckedChange = { enabled -> runOperation { imports.repository.setEnabled(dictionary.id, enabled) } })
            }
        }
    }
}
