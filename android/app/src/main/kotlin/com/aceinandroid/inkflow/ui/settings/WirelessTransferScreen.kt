package com.aceinandroid.inkflow.ui.settings

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.R
import com.aceinandroid.inkflow.transfer.WirelessTransferServer
import com.aceinandroid.inkflow.transfer.SkillTransferInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The owner supplies its shared BookStore and refreshes library data from onImported (main thread). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessTransferScreen(store: BookStore, onBack: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val refresh by rememberUpdatedState(onImported)
    var url by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var imported by remember { mutableIntStateOf(0) }
    var inboxRevision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val main = remember { Handler(Looper.getMainLooper()) }
    val server = remember(store, context) {
        WirelessTransferServer(context, store) { event ->
            main.post {
                if (event.stopped) {
                    url = null
                    message = context.getString(R.string.p2_transfer_stopped)
                } else if (event.skillPending) {
                    inboxRevision++
                    message = context.getString(R.string.skill_transfer_received, event.name)
                } else if (event.imported) {
                    imported++
                    message = context.getString(R.string.p2_transfer_imported, event.name)
                    refresh()
                } else if (event.error != null) message = context.getString(when (event.errorStatus) {
                    408 -> R.string.inkflow_transfer_timeout
                    403, 404 -> R.string.inkflow_transfer_session_failed
                    else -> R.string.inkflow_transfer_file_failed
                })
            }
        }
    }
    DisposableEffect(owner, server) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                server.close()
                url = null
                message = context.getString(R.string.p2_transfer_stopped)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            server.close()
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.p2_wireless_transfer)) }, navigationIcon = {
            IconButton(onClick = { server.close(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.p2_transfer_instructions))
            Text(stringResource(R.string.p2_transfer_limits), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (url == null) Button(enabled = !starting, onClick = {
                starting = true
                message = ""
                scope.launch {
                    try {
                        val address = withContext(Dispatchers.IO) { server.start() }
                        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) url = address
                        else server.close()
                    } catch (cancelled: CancellationException) {
                        server.close()
                        throw cancelled
                    } catch (failure: Exception) {
                        server.close()
                        message = context.getString(R.string.p2_transfer_start_failed)
                    } finally { starting = false }
                }
            }) { Text(stringResource(R.string.p2_transfer_start)) }
            else {
                Text(stringResource(R.string.p2_transfer_address), style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(url.orEmpty(), Modifier.fillMaxWidth()) }
                OutlinedButton(onClick = {
                    server.close()
                    url = null
                    message = context.getString(R.string.p2_transfer_stopped)
                }) { Text(stringResource(R.string.p2_transfer_stop)) }
            }
            SkillTransferInboxPanel(inboxRevision)
            Text(stringResource(R.string.p2_transfer_count, imported))
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}


/** Local review is the only entry point that can confirm a remotely staged Skill. */
@Composable
private fun SkillTransferInboxPanel(revision: Int) {
    val context = LocalContext.current
    val inbox = remember(context) { SkillTransferInbox(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<SkillTransferInbox.Entry>>(emptyList()) }
    var reviewing by remember { mutableStateOf<SkillTransferInbox.Entry?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(inbox, revision, refresh) {
        try { entries = withContext(Dispatchers.IO) { inbox.pending() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = context.getString(R.string.inkflow_skill_transfer_failed) }
    }
    Text(stringResource(R.string.skill_transfer_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.skill_transfer_notice), style = MaterialTheme.typography.bodySmall)
    if (entries.isEmpty()) Text(stringResource(R.string.skill_transfer_empty))
    entries.forEach { entry ->
        OutlinedButton(enabled = !busy, onClick = { reviewing = entry; error = "" }) {
            Text(stringResource(R.string.skill_transfer_review, entry.name))
        }
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { inbox.reject(entry.id) }
                    refresh++
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = context.getString(R.string.inkflow_skill_transfer_failed) }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.skill_transfer_reject)) }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    reviewing?.let { entry ->
        AlertDialog(onDismissRequest = { if (!busy) reviewing = null },
            title = { Text(stringResource(R.string.skill_transfer_preview)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.skill_transfer_notice))
                    Text(stringResource(R.string.skill_transfer_copy_policy))
                    entry.preview.skills.forEach { skill ->
                        Text(skill.title, style = MaterialTheme.typography.titleMedium)
                        Text(skill.id)
                        Text(skill.summary)
                        Text(stringResource(R.string.skill_transfer_metadata, skill.category,
                            skill.recommendedAgent ?: "—", skill.outputFormat))
                        skill.quickPrompts.forEach { Text(it) }
                        SelectionContainer { Text(skill.instructions) }
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { inbox.confirm(entry) }
                            reviewing = null
                            refresh++
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: SkillTransferInbox.PreviewChangedException) {
                            reviewing = null
                            refresh++
                            error = context.getString(R.string.inkflow_skill_transfer_changed)
                        } catch (failure: SkillTransferInbox.ConfirmationUncertainException) {
                            reviewing = null
                            refresh++
                            error = context.getString(R.string.inkflow_skill_transfer_uncertain)
                        } catch (failure: Exception) { error = context.getString(R.string.inkflow_skill_transfer_failed) }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.skill_transfer_confirm)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { reviewing = null }) {
                    Text(stringResource(R.string.skill_transfer_back))
                }
            })
    }
}
