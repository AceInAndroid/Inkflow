package com.aceinandroid.inkflow.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.StoredBook
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.*
import com.aceinandroid.inkflow.core.ai.*
import com.aceinandroid.inkflow.ui.localization.localizedAiProvider
import com.aceinandroid.inkflow.ui.localization.localizedSkillTitle
import com.aceinandroid.inkflow.ui.localization.localizedSkillSummary
import kotlinx.coroutines.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReadingSheet(
    bookId: Long,
    snapshot: ReadingContextSnapshot,
    initialQuickAction: AiQuickAction?,
    onSaveToNotes: (title: String, content: String, quote: String?, source: ReadingContextSnapshot) -> Unit,
    onDismiss: () -> Unit,
    book: StoredBook? = null,
    store: BookStore? = null,
    docked: Boolean = false,
    initialTranslation: com.aceinandroid.inkflow.core.translate.TranslationRequest? = null,
    visible: Boolean = true,
    panelWidth: Float = 400f,
    onPanelWidthChange: (Float) -> Unit = {},
    onOpenSource: ((BookMaterialSource) -> Unit)? = null,
) {
    val context = LocalContext.current
    val aiStore = remember(context) { NativeAiStore(context) }
    val sessions = remember(store) { store?.let(::AiSessionRepository) }
    val gateway = remember(context, store) { NativeAiGateway(context, store) }
    val scope = rememberCoroutineScope()
    val taskJobState = remember(bookId) { mutableStateOf<Job?>(null) }
    val preferChinese = LocalConfiguration.current.locales[0].language.startsWith("zh")
    var config by remember { mutableStateOf(aiStore.loadConfig()) }
    var sessionList by remember(bookId) { mutableStateOf(sessions?.list(bookId).orEmpty()) }
    var activeId by remember(bookId) { mutableStateOf(aiStore.activeSession(bookId)?.takeIf { id -> sessionList.any { it.id == id } } ?: sessionList.firstOrNull()?.id) }
    // Session creation must keep the same state object captured by the streaming callback.
    // Existing session changes load messages explicitly in the session picker below.
    var messages by remember(bookId) { mutableStateOf(activeId?.let { sessions?.loadMessages(bookId, it) } ?: aiStore.loadMessages(bookId)) }
    var agentId by remember(bookId) { mutableStateOf(aiStore.agentForBook(bookId).id) }
    var agentOpen by remember { mutableStateOf(false) }
    var skillId by remember(bookId) { mutableStateOf(sessionList.firstOrNull { it.id == activeId }?.skillId ?: aiStore.skillForBook(bookId).id) }
    var executionStage by remember { mutableStateOf<AiExecutionStage?>(null) }
    var draft by rememberSaveable(bookId) { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var sessionsOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameTitle by remember { mutableStateOf("") }
    var renameSessionId by remember(bookId) { mutableStateOf<String?>(null) }
    var skillOpen by remember { mutableStateOf(false) }
    var bookTab by rememberSaveable(bookId) { mutableStateOf(false) }
    var expanded by rememberSaveable(bookId) { mutableStateOf(false) }
    var allContentForNextSend by remember(bookId) { mutableStateOf(false) }
    var viewAuthorization by remember(bookId) { mutableStateOf<BookViewAuthorization?>(null) }
    val fullView = viewAuthorization?.let { it.bookId == bookId && it.userConfirmed } == true
    var usage by remember { mutableStateOf("") }
    var requestError by remember { mutableStateOf<String?>(null) }
    var autoActionConsumed by remember(initialQuickAction) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val visibleMessages = messages.filter { it.visibleFromProgress in 0..(if (fullView) 100 else snapshot.progressPercent) &&
        it.sourceProgress.isFinite() && it.sourceProgress in 0.0..(if (fullView) 1.0 else snapshot.readingProgress) &&
        (fullView || snapshot.chapterIndex < 0 || (it.chapterIndex >= 0 && (it.chapterIndex < snapshot.chapterIndex ||
            it.chapterIndex == snapshot.chapterIndex && it.readOffset in 0..snapshot.readOffset))) }

    fun openSource(source: BookMaterialSource) {
        val currentStore = store ?: return
        val grant = viewAuthorization
        scope.launch {
            val verified = withContext(Dispatchers.IO) {
                NativeBookSourceResolver(currentStore).resolve(bookId, source, snapshot, grant)
            }
            if (verified != null && grant == viewAuthorization) onOpenSource?.invoke(verified)
            else requestError = context.getString(R.string.inkflow_ai_invalid_source)
        }
    }

    fun persist(id: String?, current: List<AiChatMessage>, frozen: ReadingContextSnapshot, skill: String, selected: AiProviderConfig) {
        if (id != null && sessions != null) {
            sessions.save(bookId, id, current, skill, frozen, selected.providerType.name, selected.model)
            aiStore.clearMessages(bookId)
        } else aiStore.saveMessages(bookId, current)
    }
    fun sendPrompt(prompt: String, workload: String = "reading.chat", useAllContent: Boolean = false) {
        if (prompt.isBlank() || streaming) return
        if (!snapshot.selectionWithinBoundary) { requestError = context.getString(R.string.ai_selection_boundary); return }
        requestError = null
        if (!config.isConfigured) { settingsOpen = true; return }
        val frozen = snapshot.copy()
        val selectedConfig = config
        val selectedSkill = skillId
        val selectedAgent = agentId
        val requestView = viewAuthorization.takeIf { useAllContent && workload in setOf("reading.chat", "reading.explain") }
        allContentForNextSend = false
        val sourceBoundary = if (requestView != null) 1.0 else frozen.readingProgress
        val sourcePercent = if (requestView != null) 100 else frozen.progressPercent
        val history = messages
        var id = activeId
        if (id == null && sessions != null) {
            val session = sessions.create(bookId, prompt.take(40), frozen)
            id = session.id
            activeId = id
            aiStore.setActiveSession(bookId, id)
            sessionList = sessions.list(bookId)
        }
        val requestSession = id
        val user = AiChatMessage(UUID.randomUUID().toString(), AiRole.USER, prompt.trim(), frozen.selectedText,
            System.currentTimeMillis(), visibleFromProgress = sourcePercent, chapterIndex = frozen.chapterIndex, readOffset = frozen.readOffset, sourceChapterTitle = frozen.chapterTitle, sourceProgress = sourceBoundary)
        val assistant = AiChatMessage(UUID.randomUUID().toString(), AiRole.ASSISTANT, "", quote = frozen.selectedText, timestampMs = System.currentTimeMillis(),
            visibleFromProgress = sourcePercent, chapterIndex = frozen.chapterIndex, readOffset = frozen.readOffset, sourceChapterTitle = frozen.chapterTitle, sourceProgress = sourceBoundary)
        messages = history + user + assistant
        persist(requestSession, messages, frozen, selectedSkill, selectedConfig)
        draft = ""
        streaming = true
        activeJob = scope.launch {
            val buffer = StringBuilder()
            try {
                val result = gateway.executeWithEvents(AiRequest(UUID.randomUUID().toString(), workload, frozen, user.content,
                    history, selectedSkill, preferChinese, userAuthorized = true, bookId = bookId, bookViewAuthorization = requestView, agentId = selectedAgent), onEvent = { event ->
                    when (event) {
                        is AiResponseEvent.Status -> executionStage = event.stage
                        is AiResponseEvent.Citation -> messages = messages.map { if (it.id == assistant.id)
                            it.copy(citations = (it.citations + event.citation).distinctBy { citation -> citation.id }) else it }
                        else -> Unit
                    }
                }) { delta ->
                    buffer.append(delta)
                    messages = messages.map { if (it.id == assistant.id) it.copy(content = buffer.toString()) else it }
                }
                usage = "${result.usage.input} + ${result.usage.output}${if (result.usage.estimated) " ≈" else ""}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messages = messages.map { if (it.id == assistant.id) it.copy(content = buffer.toString() + "\n[${context.getString(R.string.localization_ai_request_failed)}]", isError = true) else it }
                draft = prompt
            } finally {
                persist(requestSession, messages, frozen, selectedSkill, selectedConfig)
                sessionList = sessions?.list(bookId).orEmpty()
                streaming = false
                executionStage = null
                activeJob = null
            }
        }
    }
    // Quick actions originate from an explicit selection command, never a reading event.
    LaunchedEffect(initialQuickAction, initialTranslation, visible) {
        if (visible && initialQuickAction != null && !autoActionConsumed) {
            autoActionConsumed = true
            draft = initialTranslation?.let { "Translate from ${it.sourceLanguage} to ${it.targetLanguage}. Preserve meaning, paragraph structure and terminology. Return the translation of this selected passage:\n\n${it.text}" }
                ?: AiReadingPrompts.quickActionPrompt(initialQuickAction, snapshot, preferChinese)
        }
    }
    DisposableEffect(bookId) { onDispose { activeJob?.cancel() } }
    LaunchedEffect(visibleMessages.size) { if (visibleMessages.isNotEmpty()) listState.scrollToItem(visibleMessages.lastIndex) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val latestWidth by rememberUpdatedState(panelWidth)
    val resizePanel by rememberUpdatedState(onPanelWidthChange)
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    BackHandler(enabled = visible && docked) {
        if (imeVisible) keyboardController?.hide() else onDismiss()
    }
    LaunchedEffect(visible) {
        if (!visible) { viewAuthorization = null; allContentForNextSend = false }
    }
    if (!visible) return

    // Short screens need room for translated controls and the composer below the header.
    val compactHeight = LocalConfiguration.current.screenHeightDp < 720
    val panel: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (docked || expanded) 1f else .90f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            requestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_assistant), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (!docked) IconButton(onClick = { expanded = !expanded }) { Icon(Icons.Default.OpenInFull, stringResource(R.string.ai_expand)) }
                IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, stringResource(R.string.ai_settings)) }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            if (book != null && store != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !bookTab, onClick = { bookTab = false }, label = { Text(stringResource(R.string.localization_ai_label)) })
                    FilterChip(selected = bookTab, onClick = { bookTab = true }, label = { Text(stringResource(R.string.ai_book_hub)) })
                }
            }
            if (bookTab && book != null && store != null) {
                BookHubContent(book, store, snapshot, ::openSource, viewAuthorization, { viewAuthorization = it }, scope, taskJobState)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(modifier = Modifier.weight(1f), enabled = !streaming, onClick = { sessionsOpen = true }) {
                        Text(sessionList.firstOrNull { it.id == activeId }?.title?.take(20) ?: stringResource(R.string.ai_new_session), maxLines = 1)
                    }
                    TextButton(modifier = Modifier.weight(1f), enabled = !streaming, onClick = { skillOpen = true }) {
                        Text(aiStore.skills().find(skillId)?.let { localizedSkillTitle(it) } ?: stringResource(R.string.ai_skill))
                    }
                    if (usage.isNotBlank()) Text(usage, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { agentOpen = true }, enabled = !streaming) { Text(agentTitle(agentId)) }
                if (fullView) {
                    Text(stringResource(R.string.inkflow_all_content_active), color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allContentForNextSend, onCheckedChange = { allContentForNextSend = it }, enabled = !streaming)
                        Text(stringResource(R.string.inkflow_ai_send_all_once), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!config.isConfigured) Text(stringResource(R.string.ai_settings_summary), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiQuickAction.entries.forEach { action ->
                        val label = when(action) {
                            AiQuickAction.EXPLAIN_SELECTION -> R.string.ai_explain
                            AiQuickAction.TRANSLATE_SELECTION -> R.string.ai_translate
                            AiQuickAction.SUMMARIZE_CHAPTER -> R.string.ai_summarize_chapter
                            AiQuickAction.KEY_TAKEAWAYS -> R.string.ai_key_takeaways
                        }
                        AssistChip(enabled = !streaming, onClick = {
                            sendPrompt(AiReadingPrompts.quickActionPrompt(action, snapshot, preferChinese), if (action == AiQuickAction.TRANSLATE_SELECTION) "reading.translate" else "reading.chat")
                        }, label = { Text(stringResource(label)) })
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleMessages, key = { it.id }) { message ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (message.role == AiRole.USER) stringResource(R.string.ai_you) else stringResource(R.string.localization_ai_label), style = MaterialTheme.typography.labelSmall)
                                message.quote?.takeIf(String::isNotBlank)?.let { Text(it.take(400), style = MaterialTheme.typography.bodySmall) }
                                androidx.compose.foundation.text.selection.SelectionContainer { AiMarkdownText(message.content) }
                                message.citations.filter { citation -> citation.bookId == bookId &&
                                    (fullView || citation.source.progress <= snapshot.readingProgress) }.forEachIndexed { index, citation ->
                                    TextButton(onClick = { openSource(citation.source) }) {
                                        Text("[${index + 1}] ${citation.source.chapterTitle}")
                                    }
                                }
                                if (message.role == AiRole.ASSISTANT && message.content.isNotBlank() && !message.isError && !streaming && message.sourceProgress <= snapshot.readingProgress) {
                                    TextButton(onClick = { onSaveToNotes(message.sourceChapterTitle.ifBlank { snapshot.bookTitle }, message.content, message.quote,
                                            snapshot.copy(chapterTitle = message.sourceChapterTitle, selectedText = message.quote, precedingContext = "",
                                                progressPercent = message.visibleFromProgress, readingProgress = message.sourceProgress,
                                                chapterIndex = message.chapterIndex, readOffset = message.readOffset)) }) {
                                        Text(stringResource(R.string.ai_save_to_notes))
                                    }
                                }
                            }
                        }
                    }
                }
                if (streaming) {
                    executionStage?.let { Text(stringResource(when (it) {
                        AiExecutionStage.READING_MATERIALS -> R.string.inkflow_ai_reading_materials
                        AiExecutionStage.CONTEXT_READY -> R.string.inkflow_ai_context_ready
                        AiExecutionStage.GENERATING -> R.string.inkflow_ai_generating
                        AiExecutionStage.VALIDATING_SOURCES -> R.string.inkflow_ai_verifying_sources
                    }), style = MaterialTheme.typography.labelSmall) }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                snapshot.selectedText?.takeIf { it.isNotBlank() }?.let { Text(it.take(300), style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), maxLines = 3,
                        placeholder = { Text(stringResource(R.string.ai_ask_placeholder)) })
                    if (streaming) IconButton(onClick = { activeJob?.cancel() }) { Icon(Icons.Default.Stop, stringResource(R.string.ai_stop)) }
                    else IconButton(enabled = draft.isNotBlank(), onClick = { sendPrompt(draft, if (initialQuickAction == AiQuickAction.TRANSLATE_SELECTION) "reading.translate" else "reading.chat", allContentForNextSend) }) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.ai_send)) }
                }
            }
        }
    }
    if (docked) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            Surface(Modifier.width(panelWidth.dp).fillMaxHeight(), tonalElevation = 4.dp) {
                Box {
                    panel()
                    Box(Modifier.align(Alignment.CenterStart).width(12.dp).fillMaxHeight().pointerInput(density) {
                        detectHorizontalDragGestures { change, amount ->
                            change.consume()
                            resizePanel((latestWidth - amount / density.density).coerceIn(380f, 440f))
                        }
                    })
                }
            }
        }
    } else ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content = { panel() })

    if (settingsOpen) AiSettingsScreen(onDismiss = { config = aiStore.loadConfig(); settingsOpen = false })
    if (agentOpen) AlertDialog(onDismissRequest = { agentOpen = false }, title = { Text(stringResource(R.string.inkflow_agents_title)) }, text = {
        Column { ReadingAgentRegistry().available.forEach { agent -> TextButton(onClick = {
            aiStore.setAgentForBook(bookId, agent.id)
            agentId = agent.id; skillId = aiStore.skillForBook(bookId, agent.id).id; agentOpen = false
        }) { Text(agentTitle(agent.id)) } } }
    }, confirmButton = { TextButton(onClick = { agentOpen = false }) { Text(stringResource(R.string.close)) } })
    if (skillOpen) AlertDialog(onDismissRequest = { skillOpen = false }, title = { Text(stringResource(R.string.ai_skill)) }, text = {
        LazyColumn { items(aiStore.skills().skills) { skill -> TextButton(onClick = { skillId = skill.id; aiStore.setSkillForBook(bookId, skill.id, agentId); skillOpen = false }) { Column { Text(localizedSkillTitle(skill)); Text(localizedSkillSummary(skill), style = MaterialTheme.typography.bodySmall) } } } }
    }, confirmButton = { TextButton(onClick = { skillOpen = false }) { Text(stringResource(R.string.close)) } })
    if (sessionsOpen) AlertDialog(onDismissRequest = { sessionsOpen = false }, title = { Text(stringResource(R.string.ai_sessions)) }, text = {
        LazyColumn {
            item { TextButton(onClick = { activeId = null; messages = emptyList(); skillId = "reading.general"; sessionsOpen = false }) { Text(stringResource(R.string.ai_new_session)) } }
            items(sessionList, key = { it.id }) { session ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        activeId = session.id; aiStore.setActiveSession(bookId, session.id); messages = sessions?.loadMessages(bookId, session.id).orEmpty()
                        skillId = session.skillId; sessionsOpen = false
                    }) { Text(session.title.ifBlank { session.id.take(8) }, maxLines = 2) }
                    IconButton(onClick = { renameSessionId = session.id; renameTitle = session.title; renameOpen = true }) { Icon(Icons.Default.Edit, stringResource(R.string.ai_rename_session)) }
                    IconButton(onClick = {
                        sessions?.delete(bookId, session.id); sessionList = sessions?.list(bookId).orEmpty()
                        if (activeId == session.id) { activeId = null; messages = emptyList(); skillId = "reading.general" }
                    }) { Icon(Icons.Default.Delete, stringResource(R.string.ai_delete_item)) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { sessionsOpen = false }) { Text(stringResource(R.string.close)) } })
    if (renameOpen) AlertDialog(onDismissRequest = { renameOpen = false }, title = { Text(stringResource(R.string.ai_rename_session)) }, text = {
        OutlinedTextField(renameTitle, { renameTitle = it }, singleLine = true)
    }, confirmButton = { TextButton(enabled = renameTitle.isNotBlank(), onClick = {
        renameSessionId?.let { sessions?.rename(bookId, it, renameTitle) }; sessionList = sessions?.list(bookId).orEmpty(); renameOpen = false
    }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { renameOpen = false }) { Text(stringResource(R.string.cancel)) } })
}
@Composable
fun AiProviderConfigDialog(
    initial: AiProviderConfig,
    onDismiss: () -> Unit,
    onSave: (AiProviderConfig) -> Unit,
) {
    var provider by remember(initial) { mutableStateOf(initial.providerType) }
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var apiKey by remember(initial) { mutableStateOf(initial.apiKey) }
    var model by remember(initial) { mutableStateOf(initial.model) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_settings)) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ai_provider), style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiProviderType.entries.forEach { entry ->
                        FilterChip(
                            selected = provider == entry,
                            onClick = {
                                provider = entry
                                baseUrl = entry.defaultBaseUrl
                                model = entry.defaultModel
                            },
                            label = { Text(localizedAiProvider(entry)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.ai_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.ai_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.ai_api_key)) },
                    minLines = 2,
                    maxLines = 4,
                    supportingText = { Text(stringResource(R.string.ai_keys_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(AiProviderConfig(provider, baseUrl.trim(), apiKey.trim(), model.trim(), initial.temperature))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun agentTitle(id: String): String = stringResource(when (id) {
    "agent.story" -> R.string.inkflow_agents_story
    "agent.background" -> R.string.inkflow_agents_background
    "agent.argument" -> R.string.inkflow_agents_argument
    "agent.language" -> R.string.inkflow_agents_language
    "agent.coach" -> R.string.inkflow_agents_coach
    else -> R.string.inkflow_agents_general
})
