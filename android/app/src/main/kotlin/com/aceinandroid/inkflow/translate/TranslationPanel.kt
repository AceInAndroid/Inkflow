package com.aceinandroid.inkflow.translate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.core.translate.*
import com.aceinandroid.inkflow.ui.localization.localizedLanguageName
import com.aceinandroid.inkflow.ui.localization.localizedTranslationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Providers/languages are passive choices; only the action button can dispatch a request. */
@Composable
fun TranslationPanel(text: String, onAskAi: (TranslationRequest) -> Unit) {
    val context = LocalContext.current
    var provider by remember { mutableStateOf(TranslationProvider.AI) }
    var source by remember { mutableStateOf("auto") }
    var target by remember { mutableStateOf("zh") }
    var key by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var deepLFree by remember { mutableStateOf(true) }
    var result by remember(text) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val adapter = remember { NativeTranslationAdapter() }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dictionary_translation_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dictionary_translation_help), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            provider = TranslationProvider.entries[(provider.ordinal + 1) % TranslationProvider.entries.size]
            result = null
        }) { Text(localizedTranslationProvider(provider)) }
        OutlinedTextField(source, { source = it }, enabled = !busy,
            label = { Text(stringResource(R.string.dictionary_source_language)) },
            supportingText = { Text(localizedLanguageName(source)) }, singleLine = true)
        OutlinedTextField(target, { target = it }, enabled = !busy,
            label = { Text(stringResource(R.string.dictionary_target_language)) },
            supportingText = { Text(localizedLanguageName(target)) }, singleLine = true)
        if (provider != TranslationProvider.AI) {
            OutlinedTextField(key, { key = it }, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.dictionary_api_key_session)) }, singleLine = true)
            if (provider == TranslationProvider.MICROSOFT) OutlinedTextField(region, { region = it }, enabled = !busy,
                label = { Text(stringResource(R.string.dictionary_region)) }, singleLine = true)
            if (provider == TranslationProvider.DEEPL) Row {
                Text(stringResource(R.string.dictionary_deepl_free), Modifier.weight(1f))
                Switch(deepLFree, { deepLFree = it }, enabled = !busy)
            }
        }
        FilledTonalButton(enabled = !busy, onClick = {
            val request = TranslationRequest(text, target.trim(), source.trim(), provider, userInitiated = true)
            result = null
            busy = true
            scope.launch {
                try {
                    val credentials = TranslationCredentials(key, region, deepLFree)
                    val dispatcher = TranslationDispatcher(onAskAi) { adapter.translate(it, credentials) }
                    if (request.provider == TranslationProvider.AI) dispatcher.dispatch(request)
                    else result = withContext(Dispatchers.IO) { dispatcher.dispatch(request)?.text }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result = context.getString(R.string.localization_translation_failed) }
                finally { busy = false }
            }
        }) { Text(stringResource(if (provider == TranslationProvider.AI) R.string.dictionary_translate_ai else R.string.dictionary_translate)) }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
