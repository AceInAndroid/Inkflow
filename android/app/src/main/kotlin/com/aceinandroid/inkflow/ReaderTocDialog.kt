package com.aceinandroid.inkflow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ReaderTocDialog(
    document: BookDocument,
    currentChapter: Int,
    onDismiss: () -> Unit,
    onNavigate: (TocEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contents)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 500.dp)) {
                items(document.toc) { entry ->
                    TextButton(onClick = { onNavigate(entry) }, modifier = Modifier.fillMaxWidth()) {
                        Text(entry.title, modifier = Modifier.fillMaxWidth().padding(start = (entry.depth * 16).dp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (entry.chapterIndex == currentChapter) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (entry.chapterIndex == currentChapter) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
