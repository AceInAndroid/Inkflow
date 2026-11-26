package com.aceinandroid.inkflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

@Composable
fun ReaderNarrationDialog(
    state: NarrationState,
    preparingText: Boolean,
    rate: Float,
    pitch: Float,
    voices: List<SpeechVoice>,
    selectedVoiceName: String?,
    continuous: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onVoiceChange: (String?) -> Unit,
    onContinuousChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val status = when {
        preparingText || state == NarrationState.PREPARING || state == NarrationState.INITIALIZING -> R.string.narration_preparing
        state == NarrationState.PLAYING -> R.string.narration_playing
        state == NarrationState.PAUSED -> R.string.narration_paused
        state == NarrationState.ERROR || state == NarrationState.UNAVAILABLE -> R.string.narration_unavailable
        else -> R.string.narration_ready
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.read_aloud)) },
        text = {
            Column {
                Text(stringResource(status))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPlayPause,
                        enabled = !preparingText && state != NarrationState.PREPARING && state != NarrationState.UNAVAILABLE) {
                        if (state == NarrationState.PLAYING) Icon(Icons.Default.Pause, stringResource(R.string.pause))
                        else Icon(Icons.Default.PlayArrow, stringResource(R.string.play))
                    }
                    IconButton(onClick = onStop, enabled = state == NarrationState.PLAYING ||
                        state == NarrationState.PAUSED || state == NarrationState.PREPARING) {
                        Icon(Icons.Default.Stop, stringResource(R.string.stop))
                    }
                }
                Text("${stringResource(R.string.speech_rate)}: ${"%.1f".format(rate)}")
                Slider(value = rate, onValueChange = { onRateChange((it * 10).roundToInt() / 10f) },
                    valueRange = 0.1f..4f, steps = 38)
                Text("${stringResource(R.string.speech_pitch)}: ${"%.1f".format(pitch)}")
                Slider(value = pitch, onValueChange = { onPitchChange((it * 10).roundToInt() / 10f) },
                    valueRange = 0.5f..2f, steps = 14)
                SpeechVoiceSelector(voices, selectedVoiceName, onVoiceChange)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.continuous_narration))
                    Switch(checked = continuous, onCheckedChange = onContinuousChange)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
