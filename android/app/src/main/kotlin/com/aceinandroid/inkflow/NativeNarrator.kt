package com.aceinandroid.inkflow

import android.content.Context
import com.aceinandroid.inkflow.core.tts.TtsChunkQueue
import com.aceinandroid.inkflow.tts.OnlineTtsConfig
import com.aceinandroid.inkflow.tts.OnlineTtsPlayback
import com.aceinandroid.inkflow.tts.OnlineTtsSettingsStore
import com.aceinandroid.inkflow.tts.TtsFallback
import java.io.File
import java.util.UUID
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class NarrationState { INITIALIZING, READY, PREPARING, PLAYING, PAUSED, ERROR, UNAVAILABLE }

data class SpeechChunk(val start: Int, val end: Int, val text: String)
data class SpeechVoice(val name: String, val label: String)

internal fun nextSpeechChunk(source: String, offset: Int, maxLength: Int = 3000): SpeechChunk? {
    require(maxLength > 0)
    var start = offset.coerceIn(0, source.length)
    while (start < source.length && (source[start].isWhitespace() || source[start] == '\uFFFC')) start++
    if (start == source.length) return null
    val limit = (start + maxLength).coerceAtMost(source.length)
    val end = if (limit == source.length || maxLength < 4) limit else {
        val earliest = start + maxLength / 2
        val sentence = (earliest until limit).lastOrNull {
            source[it] in ".!?。！？\n"
        }
        val word = (earliest until limit).lastOrNull { source[it].isWhitespace() }
        (sentence ?: word)?.plus(1) ?: limit
    }
    return SpeechChunk(start, end, source.substring(start, end).replace('\uFFFC', ' '))
}

class NativeNarrator(context: Context) {
    private val appContext = context.applicationContext
    private val onlineStore = OnlineTtsSettingsStore(appContext)
    private var onlineConfig = runCatching { onlineStore.load() }.getOrElse { OnlineTtsConfig() }
    private var onlinePlayback: OnlineTtsPlayback? = null
    private val chunkQueue = TtsChunkQueue(capacity = 1, maxLength = 3000)
    private val fallback = TtsFallback(chunkQueue)
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            if (closed) return@post
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> stop()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
            }
        }
    }
    private val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
    } else null
    private var hasFocus = false
    private val settingsStore = NarrationSettingsStore(context)
    private val initialSettings = settingsStore.load()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var systemUnavailable = false
    private var closed = false
    private var source = ""
    private var cursor = 0
    private var activeChunk: SpeechChunk? = null
    private var activeUtterance = ""
    private var utteranceNumber = 0
    private var onFinished: (() -> Unit)? = null

    var onlineEnabled by mutableStateOf(onlineConfig.enabled)
        private set
    var onlineError by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf(if (onlineConfig.enabled) NarrationState.READY else NarrationState.INITIALIZING)
        private set
    var onStateChanged: ((NarrationState) -> Unit)? = null
    var rate by mutableStateOf(initialSettings.rate)
        private set
    var pitch by mutableStateOf(initialSettings.pitch)
        private set
    var voices by mutableStateOf<List<SpeechVoice>>(emptyList())
        private set
    var selectedVoiceName by mutableStateOf(initialSettings.voiceName)
        private set
    var continuous by mutableStateOf(initialSettings.continuous)
        private set

    init {
        engine = TextToSpeech(context.applicationContext) { result ->
            main.post {
                if (closed) return@post
                if (result == TextToSpeech.SUCCESS) {
                    ready = true
                    engine?.setSpeechRate(rate)
                    engine?.setPitch(pitch)
                    voices = engine?.voices.orEmpty()
                        .map { SpeechVoice(it.name, "${it.locale.displayName} - ${it.name}") }
                        .sortedWith(compareBy({ it.label }, { it.name }))
                    selectedVoiceName = initialSettings.voiceName?.takeIf { name ->
                        val voice = engine?.voices?.firstOrNull { it.name == name }
                        voice != null && engine?.setVoice(voice) == TextToSpeech.SUCCESS
                    }
                    if (!usesOnlineSpeech()) {
                        if (state == NarrationState.PREPARING) speakNext()
                        else if (state == NarrationState.INITIALIZING) transitionTo(NarrationState.READY)
                    }
                } else {
                    systemUnavailable = true
                    if (!usesOnlineSpeech()) transitionTo(NarrationState.UNAVAILABLE)
                }
            }
        }.also { tts ->
            tts.setAudioAttributes(audioAttributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) {
                    main.post {
                        if (closed || usesOnlineSpeech() || utteranceId != activeUtterance || state != NarrationState.PLAYING) return@post
                        cursor = activeChunk?.end ?: cursor
                        chunkQueue.head?.let { chunkQueue.complete(it) }
                        if (chunkQueue.reserve() == null) {
                            val completion = onFinished
                            stop()
                            completion?.invoke()
                        } else speakNext()
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String) = handleError(utteranceId)

                override fun onError(utteranceId: String, errorCode: Int) = handleError(utteranceId)

                private fun handleError(utteranceId: String) {
                    main.post {
                        if (!closed && !usesOnlineSpeech() && utteranceId == activeUtterance) {
                            activeUtterance = ""
                            if (onlineEnabled) onlineError = appContext.getString(R.string.p2_tts_system_failed)
                            releaseFocus()
                            transitionTo(NarrationState.ERROR)
                        }
                    }
                }

                override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                    main.post {
                        if (!closed && utteranceId == activeUtterance && state == NarrationState.PLAYING) {
                            cursor = (activeChunk?.start ?: cursor) + start
                        }
                    }
                }
            })
        }
    }

    fun play(text: String, fromOffset: Int = 0, onFinished: (() -> Unit)? = null) {
        if (closed || (!onlineEnabled && state == NarrationState.UNAVAILABLE)) return
        onlinePlayback?.stop()
        engine?.stop()
        activeUtterance = ""
        fallback.reset()
        this.onFinished = onFinished
        source = text
        cursor = fromOffset.coerceIn(0, text.length)
        onlineError = null
        chunkQueue.reset(source, cursor)
        if (onlineEnabled || ready) speakNext() else transitionTo(NarrationState.PREPARING)
    }

    fun pause() {
        if (state !in setOf(NarrationState.PLAYING, NarrationState.PREPARING)) return
        activeUtterance = ""
        chunkQueue.reset(source, cursor)
        onlinePlayback?.stop()
        transitionTo(NarrationState.PAUSED)
        engine?.stop()
        releaseFocus()
    }

    fun resume() {
        if (state == NarrationState.PAUSED) speakNext()
    }

    fun stop() {
        if (closed) return
        activeUtterance = ""
        source = ""
        fallback.reset()
        chunkQueue.reset()
        onlinePlayback?.stop()
        cursor = 0
        activeChunk = null
        onFinished = null
        engine?.stop()
        releaseFocus()
        transitionTo(idleState())
    }

    fun updateRate(value: Float) {
        rate = if (value.isFinite()) value.coerceIn(0.1f, 4f) else 1f
        saveSettings()
        engine?.setSpeechRate(rate)
        onlinePlayback?.updateParameters(rate, pitch)
    }

    fun updatePitch(value: Float) {
        pitch = if (value.isFinite()) value.coerceIn(0.5f, 2f) else 1f
        saveSettings()
        engine?.setPitch(pitch)
        onlinePlayback?.updateParameters(rate, pitch)
    }

    fun updateVoice(name: String?): Boolean {
        if (!ready || closed || onlineEnabled) return false
        val tts = engine ?: return false
        val voice = if (name == null) tts.defaultVoice
            else tts.voices?.firstOrNull { it.name == name }
        if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) return false
        selectedVoiceName = name
        saveSettings()
        if (state == NarrationState.PLAYING) {
            activeUtterance = ""
            tts.stop()
            chunkQueue.reset(source, cursor)
            speakNext()
        }
        return true
    }

    fun updateContinuous(value: Boolean) {
        continuous = value
        saveSettings()
    }

    private fun saveSettings() {
        settingsStore.save(NarrationSettings(rate, pitch, selectedVoiceName, continuous))
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
        engine?.shutdown()
        engine = null
        onlinePlayback?.close()
        onlinePlayback = null
    }

    private fun usesOnlineSpeech(): Boolean = fallback.usesOnline(onlineEnabled)

    private fun speakNext() {
        if (usesOnlineSpeech()) {
            if (!hasFocus && !requestFocus()) { transitionTo(NarrationState.ERROR); return }
            onlinePlayer().start(source, cursor, onlineConfig, rate, pitch)
            return
        }
        if (!ready) { transitionTo(NarrationState.PREPARING); return }
        val queued = chunkQueue.head ?: chunkQueue.reserve()
        val chunk = queued?.let { SpeechChunk(it.start, it.end, it.text) }
        if (chunk == null) {
            stop()
            return
        }
        if (!hasFocus && !requestFocus()) {
            transitionTo(NarrationState.ERROR)
            return
        }
        activeChunk = chunk
        cursor = chunk.start
        val id = "native-${++utteranceNumber}"
        activeUtterance = id
        transitionTo(NarrationState.PLAYING)
        if (engine?.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            activeUtterance = ""
            if (onlineEnabled) onlineError = appContext.getString(R.string.p2_tts_system_failed)
            releaseFocus()
            transitionTo(NarrationState.ERROR)
        }
    }

    fun onlineSettings(): OnlineTtsConfig = onlineConfig

    /** Saving provider settings never synthesizes speech; the next explicit Play selects this backend. */
    fun updateOnlineTts(config: OnlineTtsConfig) {
        check(!closed)
        onlineStore.save(config)
        applyOnlineSettings(config)
        NarrationPlayback.current()?.takeIf { it !== this }?.applyOnlineSettings(config)
    }

    private fun applyOnlineSettings(config: OnlineTtsConfig) {
        if (closed) return
        stop()
        onlineConfig = config
        onlineEnabled = config.enabled
        onlineError = null
        transitionTo(idleState())
    }

    private fun onlinePlayer(): OnlineTtsPlayback = onlinePlayback ?: OnlineTtsPlayback(
        File(appContext.cacheDir, "online-tts/${UUID.randomUUID()}"), audioAttributes,
        onPreparing = { if (!closed) transitionTo(NarrationState.PREPARING) },
        onPlaying = { start -> cursor = start; transitionTo(NarrationState.PLAYING) },
        onChunkFinished = { end -> cursor = end },
        onFinished = {
            val completion = onFinished
            stop()
            completion?.invoke()
        },
        onError = { message -> fallBackToSystem(message) },
    ).also { onlinePlayback = it }

    private fun fallBackToSystem(message: String) {
        if (closed || !usesOnlineSpeech()) return
        onlinePlayback?.stop()
        activeUtterance = ""
        activeChunk = null
        engine?.stop()
        val recovered = fallback.recover(source, cursor, ready, ::prepareOfflineVoice)
        if (recovered) {
            onlineError = appContext.getString(R.string.p2_tts_fallback_active)
            speakNext()
        } else {
            onlineError = "$message. " + appContext.getString(R.string.p2_tts_fallback_unavailable)
            releaseFocus()
            transitionTo(NarrationState.ERROR)
        }
    }

    private fun prepareOfflineVoice(): Boolean = runCatching {
        val tts = engine ?: return@runCatching false
        val available = tts.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        val preferred = listOfNotNull(selectedVoiceName, tts.voice?.name, tts.defaultVoice?.name)
        val ordered = preferred.mapNotNull { name -> available.firstOrNull { it.name == name } } + available
        ordered.distinctBy { it.name }.any { tts.setVoice(it) == TextToSpeech.SUCCESS }
    }.getOrDefault(false)

    private fun idleState(): NarrationState = when {
        onlineEnabled || ready -> NarrationState.READY
        systemUnavailable -> NarrationState.UNAVAILABLE
        else -> NarrationState.INITIALIZING
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun transitionTo(value: NarrationState) {
        state = value
        onStateChanged?.invoke(value)
    }

    private fun releaseFocus() {
        if (!hasFocus) return
        hasFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }
}
