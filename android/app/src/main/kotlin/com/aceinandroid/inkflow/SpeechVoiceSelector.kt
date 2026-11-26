package com.aceinandroid.inkflow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun SpeechVoiceSelector(
    voices: List<SpeechVoice>,
    selectedName: String?,
    onSelect: (String?) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember(enabled) { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.speech_voice_default)
    val selectedLabel = voices.firstOrNull { it.name == selectedName }?.label ?: defaultLabel
    Text(stringResource(R.string.speech_voice))
    TextButton(onClick = { expanded = true }, enabled = enabled && voices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(selectedLabel, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text(defaultLabel,
            fontWeight = if (selectedName == null) FontWeight.SemiBold else FontWeight.Normal) },
            onClick = { expanded = false; onSelect(null) })
        voices.forEach { voice ->
            DropdownMenuItem(text = { Text(voice.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selectedName == voice.name) FontWeight.SemiBold else FontWeight.Normal) },
                onClick = { expanded = false; onSelect(voice.name) })
        }
    }
}
