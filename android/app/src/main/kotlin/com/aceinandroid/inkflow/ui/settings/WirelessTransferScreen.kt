package com.aceinandroid.inkflow.ui.settings

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    var message by remember { mutableStateOf("") }
    val main = remember { Handler(Looper.getMainLooper()) }
    val server = remember(store, context) {
        WirelessTransferServer(context, store) { event ->
            main.post {
                if (event.stopped) {
                    url = null
                    message = context.getString(R.string.p2_transfer_stopped)
                } else if (event.imported) {
                    imported++
                    message = context.getString(R.string.p2_transfer_imported, event.name)
                    refresh()
                } else if (event.error != null) message = event.error
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
                        message = failure.message ?: context.getString(R.string.p2_transfer_start_failed)
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
            Text(stringResource(R.string.p2_transfer_count, imported))
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
