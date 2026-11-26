package com.aceinandroid.inkflow.ui.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ai.NativeAiGateway
import com.aceinandroid.inkflow.ai.NativeAiStore
import com.aceinandroid.inkflow.core.*
import com.aceinandroid.inkflow.core.ai.*
import kotlinx.coroutines.*
import java.util.UUID

@Composable
fun AiSettingsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { NativeAiStore(context) }
    var registry by remember { mutableStateOf(store.loadRegistry()) }
    var editing by remember { mutableStateOf<RegisteredAiProvider?>(null) }
    var creating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(store.usageSummary()) }
    var customSkills by remember { mutableStateOf(store.skills().skills.filter { it.id.startsWith("custom.") }) }
    var exportId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun save(updated: AiProviderRegistry) { store.saveRegistry(updated); registry = updated }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = runCatching { withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input -> val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while (output.size() <= 128000) { val count = input.read(buffer, 0, minOf(buffer.size, 128001 - output.size())); if (count < 0) break; output.write(buffer, 0, count) }; output.toByteArray() } ?: error("Cannot read skill")
                require(bytes.size <= 128000) { "Skill file is too large" }
                store.importSkillJson(bytes.toString(Charsets.UTF_8))
            }; customSkills = store.skills().skills.filter { it.id.startsWith("custom.") }; context.getString(R.string.ai_skill_imported) }.getOrElse { it.message.orEmpty() }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            status = runCatching { withContext(Dispatchers.IO) {
                val json = store.exportSkillJson(exportId)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("Cannot export skill")
            }; context.getString(R.string.ai_skill_exported) }.getOrElse { it.message.orEmpty() }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ai_settings), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.ai_roles_hint), style = MaterialTheme.typography.bodySmall)
                registry.providers.forEach { provider ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${provider.name} · ${provider.config.model}")
                            Text("${provider.apiKeys.filter(String::isNotBlank).size} ${stringResource(R.string.ai_key_count)}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = registry.conversationId == provider.id, onClick = { save(AiProviderRegistry(registry.providers, provider.id, registry.lightweightId)) }, label = { Text(stringResource(R.string.ai_role_main)) })
                                FilterChip(selected = registry.lightweightId == provider.id, onClick = { save(AiProviderRegistry(registry.providers, registry.conversationId, if (registry.lightweightId == provider.id) null else provider.id)) }, label = { Text(stringResource(R.string.ai_role_light)) })
                            }
                            Row {
                                TextButton(enabled = !busy, onClick = { editing = provider }) { Text(stringResource(R.string.ai_edit_provider)) }
                                TextButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            NativeAiGateway(context).testProvider(provider)
                                            status = context.getString(R.string.ai_test_ok)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { status = failure.message.orEmpty() }
                                        finally { busy = false; usage = store.usageSummary() }
                                    }
                                }) { Text(stringResource(R.string.ai_test_provider)) }
                                TextButton(enabled = !busy, onClick = {
                                    val remaining = registry.providers.filterNot { it.id == provider.id }
                                    save(AiProviderRegistry(remaining, registry.conversationId?.takeIf { it != provider.id } ?: remaining.firstOrNull()?.id, registry.lightweightId?.takeIf { it != provider.id }))
                                }) { Text(stringResource(R.string.ai_delete_item)) }
                            }
                        }
                    }
                }
                TextButton(enabled = !busy, onClick = { creating = true; editing = null }) { Text(stringResource(R.string.ai_add_provider)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotBlank()) Text(status)
                HorizontalDivider()
                Text(stringResource(R.string.ai_skill), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.ai_import_skill)) }
                customSkills.forEach { skill ->
                    TextButton(onClick = { exportId = skill.id; exporter.launch("${skill.id}.json") }) { Text("${stringResource(R.string.ai_export_skill)} · ${skill.title}") }
                }
                HorizontalDivider()
                Text(stringResource(R.string.ai_token_usage), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ai_estimate_hint), style = MaterialTheme.typography.bodySmall)
                usage.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        }
    }
    if (creating || editing != null) AiProviderConfigDialog(initial = editing?.let {
        it.config.copy(apiKey = it.apiKeys.joinToString("\n"))
    } ?: AiProviderConfig(), onDismiss = { creating = false; editing = null }, onSave = { config ->
        val keys = config.apiKey.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
        val id = editing?.id ?: UUID.randomUUID().toString()
        val provider = RegisteredAiProvider(id, config.providerType.displayName, config.copy(apiKey = keys.firstOrNull().orEmpty()), keys)
        save(AiProviderRegistry(registry.providers.filterNot { it.id == id } + provider, registry.conversationId ?: id, registry.lightweightId))
        creating = false; editing = null
    })
}
