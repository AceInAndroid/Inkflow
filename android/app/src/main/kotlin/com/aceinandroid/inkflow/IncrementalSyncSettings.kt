package com.aceinandroid.inkflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun IncrementalSyncSettings(store: BookStore, enabled: Boolean,
    connected: Boolean, onBusyChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val sync = remember(context) { NativeIncrementalSync(context) }
    val scope = rememberCoroutineScope()
    var automatic by remember { mutableStateOf(sync.automaticEnabled()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.incremental_sync_description))
        Row {
            Checkbox(checked = automatic, enabled = enabled && connected && !busy, onCheckedChange = {
                automatic = it
                sync.setAutomaticEnabled(it)
            })
            Text(stringResource(R.string.incremental_sync_automatic))
        }
        TextButton(enabled = enabled && connected && !busy, onClick = {
            busy = true; onBusyChange(true)
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { sync.synchronizeManually(store) }
                    message = context.getString(R.string.incremental_sync_result,
                        result.merged, result.uploaded, result.conflicts, result.ignored)
                } catch (failure: Exception) { message = failure.message.orEmpty() }
                finally { busy = false; onBusyChange(false) }
            }
        }) { Text(stringResource(R.string.incremental_sync_now)) }
        if (message.isNotBlank()) Text(message)
    }
}
