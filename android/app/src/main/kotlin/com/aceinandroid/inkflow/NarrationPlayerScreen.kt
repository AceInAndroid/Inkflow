package com.aceinandroid.inkflow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses the reader/media service's existing narrator. Opening or closing never starts/stops audio. */
@Composable
fun NarrationPlayerScreen(
    narrator: NativeNarrator,
    title: String,
    chapterTitle: String,
    cover: File? = null,
    preparingText: Boolean = false,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onPreviousChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    onShowOriginal: (() -> Unit)? = null,
    onShowCurrent: ((Int) -> Unit)? = null,
    onListenFromReader: (() -> Unit)? = null,
) {
    LaunchedEffect(narrator, title, chapterTitle) { narrator.updateMediaMetadata(title, chapterTitle) }
    NarrationPlayerDialog(
        state = narrator.state, preparingText = preparingText, rate = narrator.rate, pitch = narrator.pitch,
        voices = narrator.voices, selectedVoiceName = narrator.selectedVoiceName, continuous = narrator.continuous,
        onPlayPause = onPlayPause, onStop = onStop, onRateChange = narrator::updateRate,
        onPitchChange = narrator::updatePitch, onVoiceChange = { narrator.updateVoice(it) },
        onContinuousChange = narrator::updateContinuous, onDismiss = onDismiss,
        title = title, chapterTitle = chapterTitle, cover = cover, position = narrator.position,
        sourceText = narrator.narrationText, onlineVoice = narrator.onlineEnabled,
        sleepTimerRemainingMillis = narrator.sleepTimerRemainingMillis, onSleepTimerChange = narrator::setSleepTimer,
        onPreviousChapter = onPreviousChapter, onNextChapter = onNextChapter,
        onShowOriginal = onShowOriginal, onShowCurrent = onShowCurrent,
        onListenFromReader = onListenFromReader,
    )
}

@Composable
internal fun NarrationPlayerDialog(
    state: NarrationState, preparingText: Boolean, rate: Float, pitch: Float,
    voices: List<SpeechVoice>, selectedVoiceName: String?, continuous: Boolean,
    onPlayPause: () -> Unit, onStop: () -> Unit, onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit, onVoiceChange: (String?) -> Unit,
    onContinuousChange: (Boolean) -> Unit, onDismiss: () -> Unit,
    title: String = "", chapterTitle: String = "", cover: File? = null,
    position: NarrationPosition? = null, sourceText: String = "", onlineVoice: Boolean = false,
    sleepTimerRemainingMillis: Long? = null, onSleepTimerChange: ((Int?) -> Unit)? = null,
    onPreviousChapter: (() -> Unit)? = null, onNextChapter: (() -> Unit)? = null,
    onShowOriginal: (() -> Unit)? = null, onShowCurrent: ((Int) -> Unit)? = null,
    onListenFromReader: (() -> Unit)? = null,
) {
    val preparing = preparingText || state in setOf(NarrationState.INITIALIZING, NarrationState.PREPARING)
    val status = when {
        preparing -> R.string.narration_preparing
        state == NarrationState.PLAYING -> R.string.narration_playing
        state == NarrationState.PAUSED -> R.string.narration_paused
        state in setOf(NarrationState.ERROR, NarrationState.UNAVAILABLE) -> R.string.narration_unavailable
        else -> R.string.narration_ready
    }
    var settingsOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.ExpandMore, stringResource(R.string.m4_tts_minimize)) }
                    Text(stringResource(R.string.read_aloud), Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { settingsOpen = !settingsOpen }) {
                        Icon(Icons.Default.Tune, stringResource(R.string.m4_tts_settings))
                    }
                }
                NarrationCover(cover)
                Text(title.ifBlank { stringResource(R.string.read_aloud) }, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (chapterTitle.isNotBlank()) Text(chapterTitle, style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(status), style = MaterialTheme.typography.labelLarge)
                if (position != null && position.sourceLength > 0) {
                    LinearProgressIndicator(progress = { (position.offset.toFloat() / position.sourceLength).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.m4_tts_text_offset, position.offset, position.sourceLength),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.m4_tts_time_seek_unavailable), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = onPreviousChapter != null, onClick = { onPreviousChapter?.invoke() }) {
                        Icon(Icons.Default.SkipPrevious, stringResource(R.string.m4_tts_previous_chapter))
                    }
                    FilledIconButton(modifier = Modifier.size(64.dp), onClick = onPlayPause,
                        enabled = !preparing && state != NarrationState.UNAVAILABLE) {
                        if (state == NarrationState.PLAYING) Icon(Icons.Default.Pause, stringResource(R.string.pause))
                        else Icon(Icons.Default.PlayArrow, stringResource(R.string.play))
                    }
                    IconButton(enabled = onNextChapter != null, onClick = { onNextChapter?.invoke() }) {
                        Icon(Icons.Default.SkipNext, stringResource(R.string.m4_tts_next_chapter))
                    }
                    IconButton(enabled = state in setOf(NarrationState.PLAYING, NarrationState.PAUSED, NarrationState.PREPARING) || preparingText,
                        onClick = onStop) { Icon(Icons.Default.Stop, stringResource(R.string.stop)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.weight(1f), enabled = onListenFromReader != null,
                        onClick = { onListenFromReader?.invoke() }) {
                        Text(stringResource(R.string.m4_tts_listen_from_reader), textAlign = TextAlign.Center)
                    }
                    TextButton(modifier = Modifier.weight(1f), enabled = onShowCurrent != null && position != null,
                        onClick = { position?.let { onShowCurrent?.invoke(it.offset) } }) {
                        Text(stringResource(R.string.m4_tts_current_position), textAlign = TextAlign.Center)
                    }
                }
                if (onShowOriginal != null) {
                    TextButton(onClick = onShowOriginal) {
                        Text(stringResource(R.string.m4_tts_original_position))
                    }
                }
                NarrationHighlight(sourceText, position)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { settingsOpen = !settingsOpen }) {
                        Text(stringResource(R.string.localization_speech_rate, rate))
                    }
                    Box {
                        TextButton(enabled = onSleepTimerChange != null, onClick = { timerOpen = true }) {
                            val remaining = sleepTimerRemainingMillis
                            Text(if (remaining == null) stringResource(R.string.m4_tts_timer)
                                else stringResource(R.string.m4_tts_timer_remaining, timerLabel(remaining)))
                        }
                        DropdownMenu(expanded = timerOpen, onDismissRequest = { timerOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.m4_tts_timer_off)) },
                                onClick = { onSleepTimerChange?.invoke(null); timerOpen = false })
                            for (minutes in listOf(15, 30, 45, 60, 90)) DropdownMenuItem(
                                text = { Text(stringResource(R.string.m4_tts_timer_minutes, minutes)) },
                                onClick = { onSleepTimerChange?.invoke(minutes); timerOpen = false })
                        }
                    }
                }
                if (settingsOpen) {
                    Text(stringResource(R.string.m4_tts_settings), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.localization_speech_rate, rate))
                    Slider(value = rate.coerceIn(0.1f, 4f), onValueChange = onRateChange, valueRange = 0.1f..4f, steps = 38)
                    Text(stringResource(R.string.localization_speech_pitch, pitch))
                    Slider(value = pitch.coerceIn(0.5f, 2f), onValueChange = onPitchChange, valueRange = 0.5f..2f, steps = 14)
                    if (onlineVoice) Text(stringResource(R.string.m4_tts_online_voice), style = MaterialTheme.typography.bodySmall)
                    else SpeechVoiceSelector(voices, selectedVoiceName, onVoiceChange)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.continuous_narration), Modifier.weight(1f))
                        Switch(checked = continuous, onCheckedChange = onContinuousChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun NarrationCover(file: File?) {
    val bitmap by produceState<Bitmap?>(null, file?.path, file?.lastModified()) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (file == null || !file.isFile) return@runCatching null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(file.path, options)
            }.getOrNull()
        }
    }
    Box(Modifier.width(168.dp).aspectRatio(.7f).clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NarrationHighlight(source: String, position: NarrationPosition?) {
    val excerpt = narrationExcerpt(source, position)
    if (excerpt == null) {
        Text(stringResource(R.string.m4_tts_range_unavailable), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        return
    }
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val highlightedText = buildAnnotatedString {
        append(source.substring(excerpt.sourceRange.start, excerpt.sourceRange.end))
        addStyle(SpanStyle(background = highlight, fontWeight = FontWeight.SemiBold),
            excerpt.highlightRange.start - excerpt.sourceRange.start, excerpt.highlightRange.end - excerpt.sourceRange.start)
    }
    Text(highlightedText, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    if (position?.precision == NarrationPositionPrecision.CHUNK_BOUNDARY) {
        Text(stringResource(R.string.m4_tts_boundary_highlight), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun timerLabel(remainingMillis: Long): String {
    val seconds = (remainingMillis.coerceAtLeast(0) + 999) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
