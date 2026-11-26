package com.aceinandroid.inkflow.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.NativeNarrator
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ui.localization.localizedLanguageName

/** SettingsScreen hook. Existing rate/pitch/continuous controls continue to use this narrator. */
@Composable
fun TtsProviderSettings(narrator: NativeNarrator) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val onlineSpeechLabel = stringResource(R.string.p2_tts_online)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(onlineSpeechLabel, Modifier.weight(1f))
            Switch(modifier = Modifier.semantics { contentDescription = onlineSpeechLabel },
                checked = narrator.onlineEnabled, onCheckedChange = { enabled ->
                if (enabled) editing = true else {
                    val old = narrator.onlineSettings()
                    runCatching { narrator.updateOnlineTts(OnlineTtsConfig(false, old.endpoint, old.apiKey, old.model, old.voice, old.provider, old.language)) }
                        .onFailure { message = context.getString(R.string.localization_tts_settings_failed) }
                }
            })
        }
        Text(stringResource(if (narrator.onlineEnabled) R.string.p2_tts_online_active else R.string.p2_tts_system_active),
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.p2_tts_configure)) }
        if (narrator.onlineError != null) Text(stringResource(R.string.localization_tts_online_failed),
            color = MaterialTheme.colorScheme.error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
    }
    if (editing) {
        val initial = remember { narrator.onlineSettings() }
        var provider by remember { mutableStateOf(initial.provider) }
        var language by remember { mutableStateOf(initial.language) }
        var endpoint by remember { mutableStateOf(initial.endpoint) }
        var apiKey by remember { mutableStateOf(initial.apiKey) }
        var model by remember { mutableStateOf(initial.model) }
        var voice by remember { mutableStateOf(initial.voice) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.p2_tts_configure)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.p2_tts_privacy), style = MaterialTheme.typography.bodySmall)
                OnlineTtsProvider.entries.forEach { choice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = provider == choice, onClick = {
                            provider = choice
                            if (choice == OnlineTtsProvider.AZURE && voice == "alloy") voice = "en-US-JennyNeural"
                            else if (choice == OnlineTtsProvider.OPENAI && voice == "en-US-JennyNeural") voice = "alloy"
                        })
                        Text(stringResource(if (choice == OnlineTtsProvider.AZURE) R.string.p2_tts_azure else R.string.p2_tts_openai))
                    }
                }
                if (provider == OnlineTtsProvider.AZURE) {
                    Text(stringResource(R.string.p2_tts_azure_help), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.p2_tts_endpoint)) }, singleLine = true)
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.p2_tts_api_key)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                if (provider == OnlineTtsProvider.OPENAI) {
                    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.p2_tts_model)) }, singleLine = true)
                } else {
                    OutlinedTextField(language, { language = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.p2_tts_language)) },
                        supportingText = { Text(localizedLanguageName(language)) }, singleLine = true)
                }
                OutlinedTextField(voice, { voice = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.p2_tts_voice)) }, singleLine = true)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                try {
                    narrator.updateOnlineTts(OnlineTtsConfig(true, endpoint, apiKey, model, voice, provider, language))
                    message = ""
                    editing = false
                } catch (_: Exception) { error = context.getString(R.string.localization_tts_settings_failed) }
            }) { Text(stringResource(R.string.p2_tts_save_enable)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
