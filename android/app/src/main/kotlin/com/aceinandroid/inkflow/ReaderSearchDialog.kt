package com.aceinandroid.inkflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ReaderSearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    results: List<BookSearchHit>,
    onDismiss: () -> Unit,
    onNavigate: (BookSearchHit) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_in_book)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_query)) },
                )
                if (searching) CircularProgressIndicator()
                else if (query.isNotBlank() && results.isEmpty()) {
                    Text(stringResource(R.string.no_search_results), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(results, key = { "${it.chapterIndex}:${it.offset}" }) { hit ->
                        TextButton(onClick = { onNavigate(hit) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(if (hit.isPdfPage) stringResource(R.string.page_number, hit.chapterIndex + 1)
                                    else hit.chapterTitle, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(hit.excerpt, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
