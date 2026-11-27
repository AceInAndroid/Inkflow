package com.aceinandroid.inkflow.ui.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import com.aceinandroid.inkflow.NativeSyncIntegration
import com.aceinandroid.inkflow.ui.localization.localizedProviderName
import com.aceinandroid.inkflow.ui.localization.localizedSkillTitle
import com.aceinandroid.inkflow.ui.localization.localizedSkillSummary
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
    var usageRevision by remember { mutableIntStateOf(0) }
    var skills by remember { mutableStateOf(store.skills().skills) }
    var selectedAgent by remember { mutableStateOf(store.selectedAgent().id) }
    var editorText by remember { mutableStateOf<String?>(null) }
    var importSource by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<SkillImportPreview?>(null) }
    var exportText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun save(updated: AiProviderRegistry) { store.saveRegistry(updated); registry = updated }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val imported = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= SkillCodec.MAX_FILE_BYTES) {
                            val count = input.read(buffer, 0, minOf(buffer.size, SkillCodec.MAX_FILE_BYTES + 1 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error(context.getString(R.string.localization_skill_read_failed))
                    store.previewSkillImport(bytes) to bytes.decodeToString(throwOnInvalidSequence = true)
                }
                importSource = imported.second
                preview = imported.first
                status = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = context.getString(R.string.inkflow_skills_invalid) }
            finally { busy = false }
        }
    }
    fun writeExport(uri: android.net.Uri?) {
        if (uri == null) return
        val content = exportText
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                        ?: error(context.getString(R.string.localization_skill_export_failed))
                }
                status = context.getString(R.string.ai_skill_exported)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = context.getString(R.string.localization_skill_export_failed) }
            finally { busy = false }
        }
    }
    val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::writeExport)
    val markdownExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), ::writeExport)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ai_settings), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.ai_roles_hint), style = MaterialTheme.typography.bodySmall)
                registry.providers.forEach { provider ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${localizedProviderName(provider)} · ${provider.config.model}")
                            Text(stringResource(R.string.localization_key_count, provider.apiKeys.filter(String::isNotBlank).size), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = registry.conversationId == provider.id, onClick = { save(AiProviderRegistry(registry.providers, provider.id, registry.lightweightId)) }, label = { Text(stringResource(R.string.ai_role_main)) })
                                FilterChip(selected = registry.lightweightId == provider.id, onClick = { save(AiProviderRegistry(registry.providers, registry.conversationId, if (registry.lightweightId == provider.id) null else provider.id)) }, label = { Text(stringResource(R.string.ai_role_light)) })
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(enabled = !busy, onClick = { editing = provider }) { Text(stringResource(R.string.ai_edit_provider)) }
                                TextButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            NativeAiGateway(context).testProvider(provider)
                                            status = context.getString(R.string.ai_test_ok)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { status = context.getString(R.string.localization_ai_test_failed) }
                                        finally { busy = false; usageRevision++ }
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
                Text(stringResource(R.string.inkflow_agents_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.inkflow_agents_hint), style = MaterialTheme.typography.bodySmall)
                ReadingAgentRegistry().agents.forEach { agent ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            FilterChip(selected = selectedAgent == agent.id, enabled = agent.enabled && !busy,
                                onClick = { store.selectAgent(agent.id); selectedAgent = agent.id },
                                label = { Text(agentTitle(agent.id)) })
                            if (agent.enabled) {
                                Text(stringResource(R.string.inkflow_agents_default_skill,
                                    localizedSkillTitle(store.skillForAgent(agent.id))), style = MaterialTheme.typography.bodySmall)
                                Text(agent.localCapabilities.joinToString(" · ") { capability -> context.getString(when (capability) {
                                    ReadingLocalCapability.VERIFIED_BOOK_CONTEXT -> R.string.inkflow_agents_context
                                    ReadingLocalCapability.SOURCE_NAVIGATION -> R.string.inkflow_agents_navigation
                                    ReadingLocalCapability.LOCAL_BOOK_SEARCH -> R.string.inkflow_agents_search
                                    ReadingLocalCapability.NOTE_PREVIEW -> R.string.inkflow_agents_note_preview
                                }) }, style = MaterialTheme.typography.bodySmall)
                            } else Text(stringResource(R.string.inkflow_agents_disabled), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.ai_skill), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.inkflow_skills_limits), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(enabled = !busy, onClick = { importer.launch(arrayOf("application/json", "text/markdown", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.ai_import_skill)) }
                    TextButton(enabled = !busy, onClick = { editorText = "" }) { Text(stringResource(R.string.inkflow_skills_paste)) }
                }
                skills.forEach { skill ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(localizedSkillTitle(skill))
                            if (skill.id.startsWith("custom.")) Text(skill.id, style = MaterialTheme.typography.bodySmall)
                            Text(localizedSkillSummary(skill), style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(enabled = !busy, onClick = {
                                    runCatching { store.exportSkillMarkdown(skill.id) }.onSuccess { editorText = it }
                                        .onFailure { status = context.getString(R.string.localization_skill_export_failed) }
                                }) { Text(stringResource(R.string.inkflow_skills_edit)) }
                                TextButton(enabled = !busy, onClick = {
                                    runCatching { store.exportSkillJson(skill.id) }.onSuccess {
                                        exportText = it; jsonExporter.launch("${skill.id}.json")
                                    }.onFailure { status = context.getString(R.string.localization_skill_export_failed) }
                                }) { Text(stringResource(R.string.inkflow_skills_export_json)) }
                                TextButton(enabled = !busy, onClick = {
                                    runCatching { store.exportSkillMarkdown(skill.id) }.onSuccess {
                                        exportText = it; markdownExporter.launch("${skill.id}.md")
                                    }.onFailure { status = context.getString(R.string.localization_skill_export_failed) }
                                }) { Text(stringResource(R.string.inkflow_skills_export_md)) }
                            }
                        }
                    }
                }
                HorizontalDivider()
                AiUsageSummary(usageRevision, registry.providers.associate { it.id to localizedProviderName(it) })
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        }
    }
    editorText?.let { initial ->
        SkillTextEditor(initial, onDismiss = { editorText = null }, onPreview = { source ->
            store.previewSkillImport(source).also { importSource = source; preview = it; editorText = null }
        })
    }
    preview?.let { pending ->
        SkillImportDialog(pending, onDismiss = { preview = null }, onEdit = {
            preview = null; editorText = importSource
        }, onConfirm = { overwrite ->
            // Share the import/sync lease so provenance and definitions cannot race a local edit.
            // A busy lease keeps the reviewed preview open and leaves both stores untouched.
            runCatching { NativeSyncIntegration.localMutation { store.applySkillImport(pending, overwrite) } }.onSuccess {
                skills = store.skills().skills; preview = null
                status = context.getString(R.string.ai_skill_imported)
            }.onFailure { status = context.getString(R.string.localization_skill_import_failed) }.isSuccess
        })
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


@Composable
private fun agentTitle(id: String): String = when (id) {
    "agent.general" -> stringResource(R.string.inkflow_agents_general)
    "agent.scholar", "agent.argument" -> stringResource(R.string.inkflow_agents_argument)
    "agent.background" -> stringResource(R.string.inkflow_agents_background)
    "agent.story" -> stringResource(R.string.inkflow_agents_story)
    "agent.language" -> stringResource(R.string.inkflow_agents_language)
    "agent.notes" -> stringResource(R.string.inkflow_agents_general)
    "agent.coach" -> stringResource(R.string.inkflow_agents_coach)
    else -> id
}

@Composable
private fun SkillTextEditor(initial: String, onDismiss: () -> Unit, onPreview: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    var invalid by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.inkflow_skills_editor_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.inkflow_skills_limits), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = {
                    // Bound pasted input before keeping it in Compose state; codec checks exact UTF-8 bytes.
                    if (it.length <= SkillCodec.MAX_FILE_BYTES) { text = it; invalid = false } else invalid = true
                }, modifier = Modifier.fillMaxWidth().weight(1f),
                    label = { Text(stringResource(R.string.inkflow_skills_source)) })
                if (invalid) Text(stringResource(R.string.inkflow_skills_invalid), color = MaterialTheme.colorScheme.error)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                    TextButton(enabled = text.isNotBlank(), onClick = {
                        runCatching { onPreview(text) }.onFailure { invalid = true }
                    }) { Text(stringResource(R.string.inkflow_skills_preview)) }
                }
            }
        }
    }
}

@Composable
private fun SkillImportDialog(preview: SkillImportPreview, onDismiss: () -> Unit, onEdit: () -> Unit,
                              onConfirm: (Boolean) -> Boolean) {
    var overwrite by remember(preview) { mutableStateOf(false) }
    var failed by remember(preview) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.inkflow_skills_preview), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.inkflow_skills_preview_count, preview.skills.size))
                Text(stringResource(R.string.inkflow_skills_copy_hint), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    preview.skills.forEach { skill ->
                        Text(localizedSkillTitle(skill), style = MaterialTheme.typography.titleMedium)
                        Text(skill.id, style = MaterialTheme.typography.bodySmall)
                        Text(localizedSkillSummary(skill))
                        Text(stringResource(R.string.inkflow_skills_category, skill.category))
                        skill.recommendedAgent?.let { Text(stringResource(R.string.inkflow_skills_recommended_agent, agentTitle(it))) }
                        Text(stringResource(R.string.inkflow_skills_output_format, skill.outputFormat))
                        if (skill.quickPrompts.isNotEmpty()) {
                            Text(stringResource(R.string.inkflow_skills_quick_prompts))
                            skill.quickPrompts.forEach { Text(it) }
                        }
                        Text(skill.instructions)
                        HorizontalDivider()
                    }
                }
                if (preview.conflictingIds.any { it.startsWith("custom.") }) {
                    Row {
                        Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                        Text(stringResource(R.string.inkflow_skills_overwrite), Modifier.padding(top = 12.dp))
                    }
                }
                if (failed) Text(stringResource(R.string.localization_skill_import_failed), color = MaterialTheme.colorScheme.error)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.inkflow_skills_edit)) }
                    TextButton(onClick = { failed = !onConfirm(overwrite) }) { Text(stringResource(R.string.inkflow_skills_confirm)) }
                }
            }
        }
    }
}
