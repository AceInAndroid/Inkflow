package com.aceinandroid.inkflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

@Composable
fun ProgressSyncSettings(store: BookStore, onSynced: () -> Unit) {
    val context = LocalContext.current
    val sync = remember(context) { NativeProgressSync(context) }
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(sync.account()) }
    var automatic by remember { mutableStateOf(sync.automaticEnabled()) }
    var busy by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val syncComplete = stringResource(R.string.progress_sync_complete)
    val signedOut = stringResource(R.string.progress_sync_signed_out)
    val revocationFailed = stringResource(R.string.progress_sync_revocation_failed)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.progress_sync_title),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(if (account.signedIn) account.endpoint else stringResource(R.string.progress_sync_not_signed_in),
            style = MaterialTheme.typography.bodyMedium)
        if (account.signedIn) Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.progress_sync_automatic),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium)
            Switch(checked = automatic, enabled = !busy, onCheckedChange = {
                sync.setAutomaticEnabled(it)
                automatic = it
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account.signedIn) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val count = withContext(Dispatchers.IO) { NativeSyncIntegration.manual { sync.synchronize(store) } }
                            onSynced()
                            message = "$syncComplete ($count)"
                        } catch (failure: Exception) {
                            account = sync.account()
                            automatic = sync.automaticEnabled()
                            message = failure.message.orEmpty()
                        } finally { busy = false }
                    }
                }) { Text(stringResource(R.string.progress_sync_now)) }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val failure = runCatching { withContext(Dispatchers.IO) { sync.signOut() } }.exceptionOrNull()
                        account = sync.account()
                        automatic = sync.automaticEnabled()
                        message = if (failure == null) signedOut else revocationFailed
                        busy = false
                    }
                }) { Text(stringResource(R.string.progress_sync_sign_out)) }
            } else TextButton(enabled = !busy, onClick = { showLogin = true }) {
                Text(stringResource(R.string.progress_sync_sign_in))
            }
            if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp))
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showLogin) {
        var endpoint by remember { mutableStateOf(account.endpoint) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var loginError by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) showLogin = false },
            title = { Text(stringResource(R.string.progress_sync_sign_in)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.progress_sync_endpoint)) }, singleLine = true)
                OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.progress_sync_username)) }, singleLine = true)
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.progress_sync_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                if (loginError.isNotBlank()) Text(loginError, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = !busy && endpoint.isNotBlank() &&
                username.isNotBlank() && password.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { sync.signIn(endpoint, username, password) }
                        password = ""
                        account = sync.account()
                        automatic = sync.automaticEnabled()
                        message = ""
                        showLogin = false
                    } catch (failure: Exception) {
                        loginError = failure.message.orEmpty()
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.progress_sync_sign_in)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { showLogin = false }) {
                Text(stringResource(R.string.cancel))
            } })
    }
}
