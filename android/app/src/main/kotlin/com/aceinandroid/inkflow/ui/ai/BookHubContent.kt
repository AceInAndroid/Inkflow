package com.aceinandroid.inkflow.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.StoredBook
import com.aceinandroid.inkflow.ai.*
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Local reads on open/search/filter. Only the generation confirmation starts the gateway. */
@Composable
fun BookHubContent(book: StoredBook, store: BookStore, snapshot: ReadingContextSnapshot,
                   onOpenSource: ((BookMaterialSource) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(store) { BookWikiRepository(store) }
    val tasks = remember(store) { ReadingTaskRepository(store) }
    val actions = remember(store) { NativeReadingActions(store) }
    val service = remember(store, context) { NativeBookGenerationService(context, store) }
    val noteRepository = remember(store) { NativeNoteOrganizationRepository(store) }
    val noteService = remember(store, context) { NativeNoteOrganizationService(context, store) }
    val outcomeRepository = remember(store) { NativeReadingOutcomeRepository(store) }
    var outcomes by remember(book.id) { mutableStateOf(emptyList<ReadingOutcome>()) }
    var closureId by remember(book.id) { mutableStateOf("fiction.immersion") }
    var timelineView by remember(book.id) { mutableStateOf(StoryTimelineView.STORY) }
    var timelineDensity by remember(book.id) { mutableStateOf(StoryTimelineDensity.STANDARD) }
    var timelineParticipant by remember(book.id) { mutableStateOf("") }
    var collapsedChapters by remember(book.id) { mutableStateOf(emptySet<String>()) }
    var notes by remember(book.id) { mutableStateOf(emptyList<OrganizationNote>()) }
    var reviews by remember(book.id) { mutableStateOf(emptyList<NoteOrganizationReview>()) }
    var selectedNotes by remember(book.id) { mutableStateOf(emptySet<String>()) }
    var organizationSelection by remember(book.id) { mutableStateOf<List<String>?>(null) }
    var materials by remember(book.id) { mutableStateOf(emptyList<BookMaterial>()) }
    var taskRows by remember(book.id) { mutableStateOf(emptyList<StoredReadingTask>()) }
    var history by remember(book.id) { mutableStateOf(emptyList<ReadingActionRecord>()) }
    var boundary by remember(book.id) { mutableStateOf(0.0) }
    var category by remember(book.id) { mutableStateOf(BookHubCategory.CHAPTERS) }
    var query by remember(book.id) { mutableStateOf("") }
    var plan by remember(book.id) { mutableStateOf<NativeBookGenerationPlan?>(null) }
    var sourceMaterial by remember(book.id) { mutableStateOf<BookMaterial?>(null) }
    var correction by remember(book.id) { mutableStateOf<BookMaterial?>(null) }
    var correctionText by remember(book.id) { mutableStateOf("") }
    var message by remember(book.id) { mutableStateOf<String?>(null) }
    var activeJob by remember(book.id) { mutableStateOf<Job?>(null) }
    val errorMessage = stringResource(R.string.p1_error)
    val emptyMessage = stringResource(R.string.p1_generate_empty)
    val undoFormat = stringResource(R.string.p1_undo_result)

    suspend fun refresh() {
        val data = withContext(Dispatchers.IO) {
            val fresh = store.all().firstOrNull { it.id == book.id }
            val safe = minOf(fresh?.progress ?: 0.0, snapshot.readingProgress).coerceIn(0.0, 1.0)
            Triple(safe, repository.materials(book.id, safe, snapshot), tasks.list(book.id)) to actions.history(book.id)
        }
        val noteData = withContext(Dispatchers.IO) { noteRepository.notes(book.id) to noteRepository.reviews(book.id) }
        outcomes = withContext(Dispatchers.IO) { outcomeRepository.load(book.id, snapshot) }
        notes = noteData.first; reviews = noteData.second
        selectedNotes = selectedNotes.intersect(notes.map { it.id }.toSet())
        boundary = data.first.first; materials = data.first.second; taskRows = data.first.third; history = data.second
    }
    fun localAction(block: suspend () -> Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { block() }; refresh() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = errorMessage }
        }
    }
    fun runTask(task: StoredReadingTask) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            try { service.run(book.id, task.id, true) { updated ->
                taskRows = listOf(updated) + taskRows.filterNot { it.id == updated.id }
            } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = errorMessage }
            finally { if (kotlinx.coroutines.currentCoroutineContext().isActive) refresh() }
        }
    }
    LaunchedEffect(book.id) {
        withContext(Dispatchers.IO) { tasks.restorePaused(book.id) }
        refresh()
    }
    LaunchedEffect(snapshot.readingProgress, snapshot.chapterIndex, snapshot.readOffset) { refresh() }

    val grouped = materials.groupBy { BookWikiProjector.categories.getValue(it.kind) }
    val matching = materials.filter { query.isBlank() || "${it.title}\n${it.summary}\n${it.body}".contains(query, ignoreCase = true) }
    val timeline = StoryAtlasProjector().timeline(book.id, boundary, matching, timelineView, timelineDensity,
        timelineParticipant, collapsedChapters = collapsedChapters, chapterIndex = snapshot.chapterIndex, readOffset = snapshot.readOffset)
    val visible = if (category == BookHubCategory.TIMELINE) timeline.filterNot { it.collapsed }.flatMap { it.events }
        else grouped[category].orEmpty().filter { it in matching }
    val policy = ClosurePolicyRegistry().find(closureId)!!
    val nextAction = NextReadingActionResolver().resolve(book.id, policy, outcomes, System.currentTimeMillis(), boundary, snapshot.chapterIndex, snapshot.readOffset)
    val eligibleNotes = notes.filter { it.source.length <= 32_000 }
    val busy = activeJob?.isActive == true
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.p1_hub_boundary), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.p1_hub_search)) }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BookHubCategory.entries.forEach { item ->
                        FilterChip(category == item, { category = item }, label = {
                            Text("${categoryLabel(item)} (${grouped[item].orEmpty().size})")
                        })
                    }
                }
                Text(stringResource(R.string.p1_context_pack,
                    ReadingContextPackBuilder().build(book.id, boundary, materials).size), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NativeBookGenerationKind.entries.forEach { kind ->
                        OutlinedButton(enabled = !busy, onClick = {
                            scope.launch {
                                try {
                                    val next = withContext(Dispatchers.IO) { service.plan(book, snapshot, kind) }
                                    if (next.chapterHrefs.isEmpty()) message = emptyMessage else plan = next
                                } catch (_: Exception) { message = errorMessage }
                            }
                        }) { Text(stringResource(when (kind) {
                            NativeBookGenerationKind.WIKI -> R.string.p1_generate_wiki
                            NativeBookGenerationKind.ATLAS -> R.string.p1_generate_atlas
                            NativeBookGenerationKind.NOTES -> R.string.p1_generate_notes
                        })) }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(taskRows, key = { "task:${it.id}" }) { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.p1_task_status, task.state.status, (task.state.progress * 100).toInt()))
                    if (task.state.type in ReadingTaskStateMachine.supportedTypes) Text(stringResource(when (task.state.type) {
                        "native.book.atlas.v1" -> R.string.p1_generate_atlas
                        "native.book.notes.v1" -> R.string.p1_generate_notes
                        NativeNoteOrganizationRepository.TASK_TYPE -> R.string.p1_organize_notes
                        else -> R.string.p1_generate_wiki
                    }), style = MaterialTheme.typography.bodySmall)
                    if (task.state.type !in ReadingTaskStateMachine.supportedTypes) Text(stringResource(R.string.p1_task_unknown))
                    else {
                        if (task.state.status == "paused") Text(stringResource(R.string.p1_task_paused), style = MaterialTheme.typography.bodySmall)
                        task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (task.state.status in setOf("paused", "failed", "queued")) TextButton(enabled = !busy, onClick = { runTask(task) }) {
                                Text(stringResource(if (task.state.status == "failed") R.string.p1_retry else R.string.p1_resume))
                            }
                            if (task.state.status == "running") TextButton(onClick = {
                                localAction { tasks.get(book.id, task.id)?.let { if (it.state.status in setOf("running", "queued")) tasks.transition(it, ReadingTaskEvent.PAUSE) } }
                                activeJob?.cancel()
                            }) { Text(stringResource(R.string.p1_pause)) }
                            if (task.state.status in setOf("queued", "paused", "running", "failed")) TextButton(onClick = {
                                localAction { tasks.get(book.id, task.id)?.let { if (it.state.status in setOf("queued", "running", "paused", "failed")) tasks.transition(it, ReadingTaskEvent.CANCEL) } }
                                activeJob?.cancel()
                            }) { Text(stringResource(R.string.p1_cancel)) }
                        }
                    }
                }
            }
        }
        if (category == BookHubCategory.RECORDS) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.p1_reading_outcomes), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        ClosurePolicyRegistry().policies.forEach { choice ->
                            FilterChip(closureId == choice.id, onClick = { closureId = choice.id }, label = {
                                Text(stringResource(when (choice.id) {
                                    "fiction.immersion" -> R.string.p1_closure_fiction
                                    "knowledge.argument" -> R.string.p1_closure_knowledge
                                    else -> R.string.p1_closure_reflection
                                }))
                            })
                        }
                    }
                    Text(stringResource(R.string.p1_outcomes_counts, outcomes.count { it.status in setOf("completed", "resolved") }, outcomes.size))
                    Text(stringResource(R.string.p1_next_reading_action), style = MaterialTheme.typography.labelMedium)
                    Text(nextAction.outcome?.title ?: stringResource(R.string.p1_continue_reading))
                    Text(stringResource(R.string.p1_outcomes_local), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(outcomes, key = { "outcome:${it.kind}:${it.id}" }) { outcome ->
                Column {
                    Text(outcome.title)
                    Row {
                        if (outcome.source != null && onOpenSource != null) TextButton(onClick = { outcome.source?.let(onOpenSource) }) {
                            Text(stringResource(R.string.p1_open_source))
                        }
                        if (outcome.kind != ReadingOutcomeKind.CARD && outcome.status in setOf("active", "pending", "unresolved"))
                            TextButton(onClick = { localAction { actions.completeOutcome(book.id, outcome, snapshot, true) } }) {
                                Text(stringResource(R.string.p1_complete_outcome))
                            }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.p1_existing_notes), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.p1_organize_description), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(enabled = eligibleNotes.isNotEmpty() && eligibleNotes.size <= 100, onClick = { selectedNotes = eligibleNotes.map { it.id }.toSet() }) {
                            Text(stringResource(R.string.p1_select_book_notes))
                        }
                        TextButton(enabled = selectedNotes.isNotEmpty(), onClick = { selectedNotes = emptySet() }) {
                            Text(stringResource(R.string.p1_clear_selection))
                        }
                    }
                    OutlinedButton(enabled = !busy && selectedNotes.isNotEmpty() && selectedNotes.size <= 100,
                        onClick = { organizationSelection = selectedNotes.toList() }) {
                        Text(stringResource(R.string.p1_organize_selected, selectedNotes.size))
                    }
                    if (notes.isEmpty()) Text(stringResource(R.string.p1_no_existing_notes))
                }
            }
            items(notes.filter { query.isBlank() || "${it.title}\n${it.body}".contains(query, true) }, key = { "existing:${it.id}" }) { note ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(note.id in selectedNotes, enabled = note.source.length <= 32_000, onCheckedChange = { checked ->
                        selectedNotes = if (checked) selectedNotes + note.id else selectedNotes - note.id
                    })
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(note.title.ifBlank { stringResource(R.string.p1_untitled_note) }, style = MaterialTheme.typography.titleSmall)
                        if (note.body.isNotBlank()) Text(note.body.take(300), style = MaterialTheme.typography.bodySmall)
                        if (note.source.length > 32_000) Text(stringResource(R.string.p1_note_too_large), style = MaterialTheme.typography.bodySmall)
                        if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (reviews.isNotEmpty()) item { Text(stringResource(R.string.p1_review_organization), style = MaterialTheme.typography.titleMedium) }
            items(reviews, key = { "review:${it.id}" }) { review ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.p1_before), style = MaterialTheme.typography.labelMedium)
                        Text(review.original.title); Text(review.original.body)
                        Text(review.original.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text(stringResource(R.string.p1_proposed), style = MaterialTheme.typography.labelMedium)
                        Text(review.proposed.title); Text(review.proposed.body)
                        Text(review.proposed.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        if (review.conflicted) Text(stringResource(R.string.p1_note_conflict), color = MaterialTheme.colorScheme.error)
                        Row {
                            TextButton(enabled = !review.conflicted && !busy, onClick = {
                                localAction { actions.reviewNoteOrganization(book.id, review.id, true, true) }
                            }) { Text(stringResource(R.string.p1_accept)) }
                            TextButton(enabled = !busy, onClick = {
                                localAction { actions.reviewNoteOrganization(book.id, review.id, false, true) }
                            }) { Text(stringResource(R.string.p1_reject)) }
                        }
                    }
                }
            }
        }
        if (category == BookHubCategory.CHARACTERS) {
            val graph = ReadingRelationshipGraph().project(book.id, boundary, visible, snapshot.chapterIndex, snapshot.readOffset)
            if (graph.edges.isNotEmpty()) item { SourcedCharacterGraph(graph) { sourceMaterial = it } }
        }
        if (category == BookHubCategory.TIMELINE) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        StoryTimelineView.entries.forEach { choice ->
                            FilterChip(timelineView == choice, onClick = { timelineView = choice }, label = { Text(stringResource(when (choice) {
                                StoryTimelineView.STORY -> R.string.p1_view_story
                                StoryTimelineView.MYSTERY -> R.string.p1_view_mystery
                                StoryTimelineView.CHARACTER -> R.string.p1_view_character
                                StoryTimelineView.RELATIONSHIP -> R.string.p1_view_relationship
                            })) })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        StoryTimelineDensity.entries.forEach { choice ->
                            FilterChip(timelineDensity == choice, onClick = { timelineDensity = choice }, label = { Text(stringResource(when (choice) {
                                StoryTimelineDensity.COMPACT -> R.string.p1_density_compact
                                StoryTimelineDensity.STANDARD -> R.string.p1_density_standard
                                StoryTimelineDensity.COMPLETE -> R.string.p1_density_complete
                            })) })
                        }
                    }
                    if (timelineView == StoryTimelineView.CHARACTER) OutlinedTextField(timelineParticipant, { timelineParticipant = it },
                        label = { Text(stringResource(R.string.p1_participant_filter)) }, singleLine = true)
                    Text(stringResource(R.string.p1_timeline_order), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(timeline, key = { "chapter-group:${it.id}" }) { chapter ->
                TextButton(onClick = {
                    collapsedChapters = if (chapter.collapsed) collapsedChapters - chapter.id else collapsedChapters + chapter.id
                }) { Text("${if (chapter.collapsed) "+" else "−"} ${chapter.title} (${chapter.events.size})") }
            }
        }
        if (visible.isEmpty()) item { Text(stringResource(R.string.p1_hub_empty), style = MaterialTheme.typography.bodyMedium) }
        items(visible, key = { "material:${it.id}" }) { material ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(material.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (material.userCorrected) R.string.p1_corrected
                        else if (material.epistemicStatus == "agentInference") R.string.p1_inference else R.string.p1_fact),
                        style = MaterialTheme.typography.labelSmall)
                    if (material.summary.isNotBlank()) Text(material.summary)
                    if (material.body.isNotBlank() && material.body != material.summary) Text(material.body)
                    Text(material.sources.first().chapterTitle, style = MaterialTheme.typography.bodySmall)
                    material.story?.stage?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { sourceMaterial = material }) { Text(stringResource(R.string.p1_sources)) }
                        if (material.kind.startsWith("wiki.")) {
                            TextButton(onClick = { correction = material; correctionText = material.body.ifBlank { material.summary } }) { Text(stringResource(R.string.p1_correct)) }
                            TextButton(onClick = { localAction { actions.hideWiki(book.id, material.id, material.version, true) } }) { Text(stringResource(R.string.p1_hide)) }
                        }
                        if (material.kind == "note.suggestion") {
                            TextButton(onClick = { localAction { actions.reviewSuggestion(book.id, material.id, true, true) } }) { Text(stringResource(R.string.p1_accept)) }
                            TextButton(onClick = { localAction { actions.reviewSuggestion(book.id, material.id, false, true) } }) { Text(stringResource(R.string.p1_reject)) }
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty()) item { Text(stringResource(R.string.p1_history), style = MaterialTheme.typography.titleMedium) }
        items(history.take(20), key = { "action:${it.id}" }) { action ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(action.title.ifBlank { stringResource(R.string.p1_saved_material) }, Modifier.weight(1f))
                TextButton(enabled = action.status == "applied", onClick = {
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { actions.undo(action.id) }
                            message = undoFormat.format(result.name)
                            refresh()
                        } catch (_: Exception) { message = errorMessage }
                    }
                }) { Text(stringResource(R.string.p1_undo)) }
            }
        }
    }
    organizationSelection?.let { ids ->
        AlertDialog(onDismissRequest = { organizationSelection = null },
            title = { Text(stringResource(R.string.p1_organize_notes)) },
            text = { Text(stringResource(R.string.p1_organize_confirmation, ids.size)) },
            confirmButton = { TextButton(onClick = {
                organizationSelection = null
                scope.launch {
                    try {
                        val task = withContext(Dispatchers.IO) { noteService.create(book.id, ids, true) }
                        refresh(); runTask(task)
                    } catch (_: Exception) { message = errorMessage }
                }
            }) { Text(stringResource(R.string.p1_generate_start)) } },
            dismissButton = { TextButton(onClick = { organizationSelection = null }) { Text(stringResource(R.string.p1_cancel)) } })
    }
    plan?.let { prepared ->
        AlertDialog(onDismissRequest = { plan = null }, title = { Text(stringResource(R.string.p1_generate_confirm)) },
            text = { Text(stringResource(R.string.p1_generate_scope, prepared.chapterHrefs.size, (prepared.boundary * 100).toInt())) },
            confirmButton = { TextButton(onClick = {
                plan = null
                scope.launch {
                    try {
                        val task = withContext(Dispatchers.IO) { service.create(prepared, true) }
                        refresh(); runTask(task)
                    } catch (_: Exception) { message = errorMessage }
                }
            }) { Text(stringResource(R.string.p1_generate_start)) } },
            dismissButton = { TextButton(onClick = { plan = null }) { Text(stringResource(R.string.p1_cancel)) } })
    }
    sourceMaterial?.let { material ->
        AlertDialog(onDismissRequest = { sourceMaterial = null }, title = { Text(stringResource(R.string.p1_source_title)) },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(material.sources) { source ->
                Column { Text(source.chapterTitle); Text(source.text)
                    if (onOpenSource != null) TextButton(onClick = { sourceMaterial = null; onOpenSource(source) }) {
                        Text(stringResource(R.string.p1_open_source))
                    }
                }
            } } }, confirmButton = { TextButton(onClick = { sourceMaterial = null }) { Text(stringResource(R.string.p1_close)) } })
    }
    correction?.let { material ->
        AlertDialog(onDismissRequest = { correction = null }, title = { Text(stringResource(R.string.p1_correct)) },
            text = { OutlinedTextField(correctionText, { correctionText = it }, label = { Text(stringResource(R.string.p1_edit_label)) }) },
            confirmButton = { TextButton(enabled = correctionText.isNotBlank(), onClick = {
                val text = correctionText; correction = null
                localAction { actions.correctWiki(book.id, material.id, material.version, text, true) }
            }) { Text(stringResource(R.string.p1_save)) } },
            dismissButton = { TextButton(onClick = { correction = null }) { Text(stringResource(R.string.p1_cancel)) } })
    }
}

@Composable private fun categoryLabel(category: BookHubCategory): String = stringResource(when (category) {
    BookHubCategory.CHAPTERS -> R.string.p1_hub_chapters
    BookHubCategory.CHARACTERS -> R.string.p1_hub_characters
    BookHubCategory.TIMELINE -> R.string.p1_hub_timeline
    BookHubCategory.CONCEPTS -> R.string.p1_hub_concepts
    BookHubCategory.RECORDS -> R.string.p1_hub_records
})

@Composable private fun SourcedCharacterGraph(graph: SourcedRelationshipGraph, onSource: (BookMaterial) -> Unit) {
    var full by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val limit = if (full) 60 else 12
    val nodes = graph.nodes.take(limit)
    val edges = graph.edges.filter { it.relationship!!.from in nodes && it.relationship!!.to in nodes }
    val lineColor = MaterialTheme.colorScheme.outline
    val nodeColor = MaterialTheme.colorScheme.secondaryContainer
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.p1_relationship_graph), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.p1_graph_description), style = MaterialTheme.typography.bodySmall)
        Row {
            FilterChip(!full, onClick = { full = false; zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }, label = { Text(stringResource(R.string.p1_density_compact)) })
            FilterChip(full, onClick = { full = true; zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero }, label = { Text(stringResource(R.string.p1_density_complete)) })
        }
        Box(Modifier.fillMaxWidth().height(320.dp).clipToBounds().pointerInput(graph.nodes) {
            detectTransformGestures { _, drag, scale, _ ->
                zoom = (zoom * scale).coerceIn(0.5f, 4f)
                pan += drag
            }
        }) {
        BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
        }) {
            val width = maxWidth
            fun point(index: Int): Pair<Float, Float> {
                val angle = -Math.PI / 2 + index * 2 * Math.PI / nodes.size.coerceAtLeast(1)
                return (.5f + .37f * kotlin.math.cos(angle).toFloat()) to (.5f + .37f * kotlin.math.sin(angle).toFloat())
            }
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                edges.forEach { material ->
                    val edge = material.relationship!!
                    val start = point(nodes.indexOf(edge.from)); val end = point(nodes.indexOf(edge.to))
                    drawLine(lineColor, androidx.compose.ui.geometry.Offset(start.first * size.width, start.second * size.height),
                        androidx.compose.ui.geometry.Offset(end.first * size.width, end.second * size.height), strokeWidth = 2.dp.toPx())
                }
                nodes.forEachIndexed { index, _ ->
                    val p = point(index)
                    drawCircle(nodeColor, 28.dp.toPx(), androidx.compose.ui.geometry.Offset(p.first * size.width, p.second * size.height))
                }
            }
            nodes.forEachIndexed { index, label ->
                val p = point(index)
                Box(Modifier.offset(x = width * p.first - 38.dp, y = 320.dp * p.second - 24.dp).width(76.dp).height(48.dp).clickable {
                    onSource(graph.characters.firstOrNull { it.title == label } ?: graph.edges.first {
                        it.relationship!!.from == label || it.relationship!!.to == label })
                },
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(label, maxLines = 2, style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        }
        // Every edge retains its own evidence, including different relationship states at different points.
        edges.forEach { material ->
            val edge = material.relationship!!
            TextButton(onClick = { onSource(material) }) { Text("${edge.from} → ${edge.to}: ${edge.relation}") }
        }
        if (graph.nodes.size > nodes.size) Text(stringResource(R.string.p1_graph_limit, limit), style = MaterialTheme.typography.bodySmall)
    }
}
