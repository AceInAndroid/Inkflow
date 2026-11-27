package com.aceinandroid.inkflow

import androidx.compose.runtime.Composable
import java.io.File

/** Compatibility entry point. New integrations can pass their shared narrator to NarrationPlayerScreen. */
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
    title: String = "",
    chapterTitle: String = "",
    cover: File? = null,
    position: NarrationPosition? = null,
    sourceText: String = "",
    onlineVoice: Boolean = false,
    sleepTimerRemainingMillis: Long? = null,
    onSleepTimerChange: ((Int?) -> Unit)? = null,
    onPreviousChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    onShowOriginal: (() -> Unit)? = null,
    onShowCurrent: ((Int) -> Unit)? = null,
    onListenFromReader: (() -> Unit)? = null,
) = NarrationPlayerDialog(
    state, preparingText, rate, pitch, voices, selectedVoiceName, continuous,
    onPlayPause, onStop, onRateChange, onPitchChange, onVoiceChange, onContinuousChange, onDismiss,
    title, chapterTitle, cover, position, sourceText, onlineVoice, sleepTimerRemainingMillis, onSleepTimerChange,
    onPreviousChapter, onNextChapter, onShowOriginal, onShowCurrent, onListenFromReader,
)
