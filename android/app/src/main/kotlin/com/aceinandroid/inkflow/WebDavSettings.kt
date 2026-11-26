package com.aceinandroid.inkflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

@Composable
internal fun WebDavSettings(store: BookStore, onDownloaded: () -> Unit,
    onBusyChange: (Boolean) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val accounts = remember(context) { WebDavAccountStore(context) }
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(runCatching { accounts.load() }.getOrNull()) }
    var busy by remember { mutableStateOf(false) }
    var editAccount by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<Boolean, WebDavLibraryStatus>?>(null) }
    var message by remember { mutableStateOf("") }
    fun setBusy(value: Boolean) {
        busy = value
        onBusyChange(value)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.webdav_title), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Text(account?.let { stringResource(R.string.webdav_account_summary, it.username, it.endpoint) }
            ?: stringResource(R.string.webdav_no_account),
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = enabled && !busy, onClick = { editAccount = true }) {
                Text(stringResource(if (account == null) R.string.webdav_connect else R.string.webdav_edit_account))
            }
            if (account != null) {
                TextButton(enabled = enabled && !busy, onClick = {
                    setBusy(true)
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { accounts.clear() }
                            account = null
                            message = context.getString(R.string.webdav_account_removed)
                        } catch (failure: Exception) { message = failure.message.orEmpty() }
                        finally { setBusy(false) }
                    }
                }) { Text(stringResource(R.string.webdav_disconnect)) }
            }
        }
        if (account != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (upload in listOf(true, false)) {
                TextButton(enabled = enabled && !busy, onClick = {
                    val selected = account ?: return@TextButton
                    setBusy(true)
                    scope.launch {
                        try {
                            val status = withContext(Dispatchers.IO) {
                                NativeWebDavSync(context, WebDavTransport(selected.endpoint,
                                    selected.username, selected.password)).status()
                            }
                            if (status.newerVersion != null)
                                message = context.getString(R.string.webdav_newer_version, status.newerVersion)
                            else if (!upload && status.database == null)
                                message = context.getString(R.string.webdav_no_remote)
                            else pending = upload to status
                        } catch (failure: Exception) { message = failure.message.orEmpty() }
                        finally { setBusy(false) }
                    }
                }) { Text(stringResource(if (upload) R.string.webdav_upload_library
                    else R.string.webdav_download_library)) }
            }
        }
        IncrementalSyncSettings(store, enabled && !busy, account != null, ::setBusy)
        if (busy) CircularProgressIndicator()
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    pending?.let { (upload, status) ->
        val remoteDate = status.database?.modifiedAt?.let {
            DateFormat.getDateTimeInstance().format(java.util.Date(it))
        } ?: stringResource(R.string.webdav_unknown_date)
        AlertDialog(onDismissRequest = { if (!busy) pending = null },
            title = { Text(stringResource(if (upload) R.string.webdav_upload_confirm_title
                else R.string.webdav_download_confirm_title)) },
            text = { Text(stringResource(if (upload) R.string.webdav_upload_confirm
                else R.string.webdav_download_confirm, remoteDate)) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val selected = account ?: return@TextButton
                setBusy(true)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val sync = NativeWebDavSync(context, WebDavTransport(selected.endpoint,
                                selected.username, selected.password))
                            if (upload) sync.upload(store, status) else sync.download(store, status)
                        }
                        if (!upload) onDownloaded()
                        message = context.getString(if (upload) R.string.webdav_uploaded
                            else R.string.webdav_downloaded)
                    } catch (failure: Exception) { message = failure.message.orEmpty() }
                    finally { pending = null; setBusy(false) }
                }
            }) { Text(stringResource(if (upload) R.string.webdav_upload else R.string.webdav_replace)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) {
                Text(stringResource(R.string.cancel))
            } })
    }

    if (editAccount) {
        var endpoint by remember { mutableStateOf(account?.endpoint.orEmpty()) }
        var username by remember { mutableStateOf(account?.username.orEmpty()) }
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) editAccount = false },
            title = { Text(stringResource(R.string.webdav_account)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.webdav_https_url)) }, singleLine = true)
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.progress_sync_username)) }, singleLine = true)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.progress_sync_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = !busy && endpoint.isNotBlank() &&
                username.isNotBlank() && password.isNotBlank(), onClick = {
                setBusy(true)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val client = WebDavTransport(endpoint.trim(), username, password)
                            client.resources("inkflow")
                            accounts.save(endpoint.trim(), username, password)
                        }
                        account = accounts.load()
                        password = ""
                        message = context.getString(R.string.webdav_account_connected)
                        editAccount = false
                    } catch (failure: Exception) { error = failure.message.orEmpty() }
                    finally { setBusy(false) }
                }
            }) { Text(stringResource(R.string.webdav_connect)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { editAccount = false }) {
                Text(stringResource(R.string.cancel))
            } })
    }
}
