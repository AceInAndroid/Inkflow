package com.aceinandroid.inkflow.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aceinandroid.inkflow.R

/** Styles are stable storage IDs. Color previews never grant persistence or AI permissions. */
@Composable
internal fun SelectionStyleCapsule(type: String, color: String, enabled: Boolean, eink: Boolean,
                                   onStyle: (String) -> Unit, onColor: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(if (eink) 0.dp else 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("highlight" to R.string.highlight, "underline" to R.string.underline, "wavy" to R.string.inkflow_mark_wavy).forEach { (id, label) ->
                    FilterChip(selected = type == id, onClick = { onStyle(id) }, enabled = enabled,
                        modifier = Modifier.heightIn(min = if (eink) 56.dp else 48.dp), label = { Text(stringResource(label)) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                listOf("66CCFF" to R.string.inkflow_mark_blue, "FF0000" to R.string.inkflow_mark_red,
                    "00FF00" to R.string.inkflow_mark_green, "EB3BFF" to R.string.inkflow_mark_purple,
                    "FFD700" to R.string.inkflow_mark_yellow).forEach { (hex, name) ->
                    val description = stringResource(name)
                    IconButton(enabled = enabled, onClick = { onColor(hex) }, modifier = Modifier.size(if (eink) 56.dp else 48.dp)
                        .semantics { contentDescription = description }) {
                        Box(Modifier.size(30.dp).background(Color(0xFF000000 or hex.toLong(16)), CircleShape)
                            .border(if (color == hex) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (color == hex) Text("✓", color = Color.Black)

                        }
                    }
                }
            }
        }
    }
}
