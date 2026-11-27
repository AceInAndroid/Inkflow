package com.aceinandroid.inkflow.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.NativeProgressIdentity
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.ai.NativeReadingActions
import com.aceinandroid.inkflow.ai.NativeReadingOutcomeRepository
import com.aceinandroid.inkflow.ai.NativeReadingUndoResult
import com.aceinandroid.inkflow.core.ReadingContextSnapshot
import com.aceinandroid.inkflow.core.ai.BookMaterialSource
import com.aceinandroid.inkflow.core.ai.CoachCard
import com.aceinandroid.inkflow.core.ai.CoachCardRating
import com.aceinandroid.inkflow.core.ai.CoachGoal
import com.aceinandroid.inkflow.core.ai.CoachGoalDraft
import com.aceinandroid.inkflow.ui.localization.localizedUndoResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Explicit local practice only. Opening, filtering and revealing answers never request a model. */
@Composable
fun ReadingPracticeContent(
    bookId: Long,
    store: BookStore,
    snapshot: ReadingContextSnapshot,
    onOpenSource: ((BookMaterialSource) -> Unit)? = null,
) {
    key(store, bookId) { PracticeContent(bookId, store, snapshot, onOpenSource) }
}

private data class PracticeData(val snapshot: ReadingContextSnapshot, val goal: CoachGoal?, val cards: List<CoachCard>)
private data class PracticeGoalEditor(val expected: CoachGoal?, val title: String, val minutes: String)
private data class PracticeUndo(val id: String, val cardId: String? = null)

/** A title/minutes editor must preserve the existing portable range and completion criteria. */
internal fun practiceGoalDraft(title: String, minutes: String, previous: CoachGoalDraft? = null): CoachGoalDraft? {
    val cleanTitle = title.trim()
    val cleanMinutes = minutes.trim()
    if (cleanTitle.isEmpty() || cleanTitle.length > 500) return null
    val budget = if (cleanMinutes.isEmpty()) null else {
        if (!cleanMinutes.all { it in '0'..'9' }) return null
        cleanMinutes.toIntOrNull()?.takeIf { it > 0 } ?: return null
    }
    return (previous ?: CoachGoalDraft(cleanTitle)).copy(title = cleanTitle, timeBudgetMinutes = budget)
}

/** Input is exclusively the repository's source-verified projection, never raw database rows. */
internal fun practiceCardsForView(cards: List<CoachCard>, dueOnly: Boolean, now: Long): List<CoachCard> =
    cards.filter { !dueOnly || it.nextReviewAt <= now }.sortedWith(compareBy<CoachCard> { it.nextReviewAt }.thenBy { it.id })

@Composable
private fun PracticeContent(bookId: Long, store: BookStore, snapshot: ReadingContextSnapshot,
                            onOpenSource: ((BookMaterialSource) -> Unit)?) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val repository = remember(store) { NativeReadingOutcomeRepository(store) }
    val actions = remember(store) { NativeReadingActions(store) }
    var data by remember { mutableStateOf<PracticeData?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var mutationFailed by remember { mutableStateOf(false) }
    var undoResult by remember { mutableStateOf<NativeReadingUndoResult?>(null) }
    var undoItems by remember { mutableStateOf(emptyList<PracticeUndo>()) }
    var editor by remember { mutableStateOf<PracticeGoalEditor?>(null) }
    var dueOnly by rememberSaveable { mutableStateOf(true) }
    var revealed by remember(snapshot) { mutableStateOf(emptySet<String>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // A changed/rewound snapshot hides old card text synchronously, before the new IO query starts.
    val visible = data?.takeIf { it.snapshot == snapshot }
    val enabled = !busy && !loading && visible != null

    LaunchedEffect(snapshot, refresh) {
        loading = true
        loadFailed = false
        try {
            val loaded = withContext(Dispatchers.IO) {
                PracticeData(snapshot, repository.activeGoal(bookId), repository.cards(bookId, snapshot))
            }
            data = loaded
            now = System.currentTimeMillis()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            data = null
            loadFailed = true
        } finally { loading = false }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    fun mutate(work: suspend () -> Unit) {
        if (busy || loading) return
        busy = true
        mutationFailed = false
        undoResult = null
        scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutationFailed = true
                // Do not silently rebase a stale editor onto a newer goal after a conflict.
                editor = null
            } finally {
                data = null
                revealed = emptySet()
                refresh++
                busy = false
                listState.scrollToItem(0)
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("reading_practice"), state = listState, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.inkflow_practice_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.inkflow_practice_local), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (mutationFailed) item {
            Text(stringResource(R.string.inkflow_practice_write_failed), color = MaterialTheme.colorScheme.error)
        }
        undoResult?.let { result -> item { Text(context.localizedUndoResult(result)) } }
        items(undoItems, key = { "undo:${it.id}" }) { entry ->
            PracticeCard {
                Text(stringResource(if (entry.cardId == null) R.string.inkflow_practice_goal_saved else R.string.inkflow_practice_rating_saved))
                visible?.cards?.firstOrNull { it.id == entry.cardId }?.let {
                    Text(stringResource(R.string.inkflow_practice_next_review, dateFormat.format(Date(it.nextReviewAt))))
                }
                TextButton(enabled = !busy && !loading, modifier = Modifier.testTag("practice_undo:${entry.id}"), onClick = {
                    mutate {
                        val result = withContext(Dispatchers.IO) { actions.undo(entry.id) }
                        undoResult = result
                        undoItems = undoItems.filterNot { it.id == entry.id }
                    }
                }) { Text(stringResource(R.string.inkflow_practice_undo)) }
            }
        }
        item {
            PracticeCard {
                Text(stringResource(R.string.inkflow_practice_goal), style = MaterialTheme.typography.titleMedium)
                visible?.goal?.let { goal ->
                    Text(goal.draft.title, modifier = Modifier.testTag("practice_active_goal"))
                    goal.draft.timeBudgetMinutes?.let {
                        Text(stringResource(R.string.inkflow_practice_minutes_value, it))
                    }
                } ?: Text(stringResource(R.string.inkflow_practice_no_goal))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = enabled, onClick = { editor = PracticeGoalEditor(null, "", "") }) {
                        Text(stringResource(R.string.inkflow_practice_goal_new))
                    }
                    visible?.goal?.let { goal ->
                        TextButton(enabled = enabled, onClick = {
                            editor = PracticeGoalEditor(goal, goal.draft.title, goal.draft.timeBudgetMinutes?.toString().orEmpty())
                        }) { Text(stringResource(R.string.inkflow_practice_goal_edit)) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.inkflow_practice_cards), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = dueOnly, onClick = { dueOnly = true },
                        label = { Text(stringResource(R.string.inkflow_practice_due)) })
                    FilterChip(selected = !dueOnly, onClick = { dueOnly = false },
                        label = { Text(stringResource(R.string.inkflow_practice_all)) })
                    TextButton(enabled = !busy && !loading, onClick = { data = null; refresh++ }) {
                        Text(stringResource(R.string.inkflow_practice_refresh))
                    }
                }
            }
        }
        if (loading || visible == null && !loadFailed) item {
            Text(stringResource(R.string.inkflow_practice_loading))
        }
        if (loadFailed) item {
            PracticeCard {
                Text(stringResource(R.string.inkflow_practice_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy && !loading, onClick = { refresh++ }) {
                    Text(stringResource(R.string.inkflow_practice_retry))
                }
            }
        }
        visible?.let { current ->
            val cards = practiceCardsForView(current.cards, dueOnly, now)
            if (cards.isEmpty()) item {
                Text(stringResource(if (dueOnly && current.cards.isNotEmpty()) R.string.inkflow_practice_none_due
                    else R.string.inkflow_practice_no_cards))
            }
            items(cards, key = { "card:${it.id}" }) { card ->
                val revealKey = "${card.id}:${card.stateHash}"
                PracticeCard {
                    Text(card.question, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.inkflow_practice_next_review, dateFormat.format(Date(card.nextReviewAt))),
                        style = MaterialTheme.typography.bodySmall)
                    if (revealKey in revealed) {
                        Text(card.answer, modifier = Modifier.testTag("practice_answer:${card.id}"))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CoachCardRating.entries.forEach { rating ->
                                OutlinedButton(enabled = enabled, modifier = Modifier.weight(1f), onClick = {
                                    mutate {
                                        val id = withContext(Dispatchers.IO) {
                                            actions.reviewCard(bookId, card, rating.id, snapshot, userAuthorized = true)
                                        }
                                        undoItems = listOf(PracticeUndo(id, card.id)) + undoItems
                                    }
                                }) { Text(stringResource(when (rating) {
                                    CoachCardRating.HARD -> R.string.inkflow_practice_hard
                                    CoachCardRating.REMEMBERED -> R.string.inkflow_practice_remembered
                                    CoachCardRating.MASTERED -> R.string.inkflow_practice_mastered
                                })) }
                            }
                        }
                    } else {
                        TextButton(enabled = enabled, onClick = { revealed = revealed + revealKey }) {
                            Text(stringResource(R.string.inkflow_practice_show_answer))
                        }
                    }
                    if (onOpenSource != null) card.sources.forEach { source ->
                        TextButton(enabled = enabled, onClick = { onOpenSource(source) }) {
                            Text(stringResource(R.string.inkflow_practice_open_source, source.chapterTitle))
                        }
                    }
                }
            }
        }
    }

    editor?.let { edit ->
        val draft = practiceGoalDraft(edit.title, edit.minutes, edit.expected?.draft)
        AlertDialog(onDismissRequest = { if (!busy) editor = null },
            title = { Text(stringResource(if (edit.expected == null) R.string.inkflow_practice_goal_new else R.string.inkflow_practice_goal_edit)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (edit.expected == null && visible?.goal != null) Text(stringResource(R.string.inkflow_practice_goal_replace))
                    OutlinedTextField(value = edit.title, onValueChange = { editor = edit.copy(title = it) },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.inkflow_practice_goal_title)) },
                        isError = edit.title.trim().length > 500)
                    OutlinedTextField(value = edit.minutes, onValueChange = { editor = edit.copy(minutes = it) },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.inkflow_practice_minutes)) },
                        isError = edit.minutes.isNotBlank() && practiceGoalDraft("valid", edit.minutes) == null)
                    Text(stringResource(R.string.inkflow_practice_goal_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = enabled && draft != null, onClick = {
                    val saving = draft ?: return@TextButton
                    mutate {
                        val id = withContext(Dispatchers.IO) {
                            actions.saveGoal(bookId, saving, edit.expected, userAuthorized = true,
                                deviceId = NativeProgressIdentity.getOrCreate(context))
                        }
                        undoItems = listOf(PracticeUndo(id)) + undoItems
                        editor = null
                    }
                }) { Text(stringResource(R.string.inkflow_practice_save)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { editor = null }) { Text(stringResource(R.string.inkflow_practice_cancel)) }
            })
    }
}

@Composable
private fun PracticeCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
