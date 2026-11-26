package com.aceinandroid.inkflow

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class PronunciationState { IDLE, LOADING, PLAYING }

internal class VocabularyPronouncer(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            main.post { stop() }
    }
    private val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build() else null
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var preparationTimeout: Runnable? = null
    private var narrator: NativeNarrator? = null
    private var hasFocus = false
    private var closed = false
    private var generation = 0
    private var usingTts = false

    var state by mutableStateOf(PronunciationState.IDLE)
        private set
    var onFailure: (() -> Unit)? = null

    fun play(word: String, audioUrl: String?) {
        if (closed || word.isBlank()) return
        stop()
        val request = ++generation
        val url = safeAudioUrl(audioUrl)
        if (url == null) {
            speakWithTts(word)
            return
        }
        state = PronunciationState.LOADING
        try {
            val created = MediaPlayer()
            player = created
            created.setAudioAttributes(attributes)
            created.setDataSource(url)
            created.setOnPreparedListener { ready ->
                if (closed || generation != request || player !== ready) return@setOnPreparedListener
                if (!requestFocus()) {
                    fallback(word)
                    return@setOnPreparedListener
                }
                try {
                    ready.start()
                    state = PronunciationState.PLAYING
                } catch (_: Exception) { fallback(word) }
            }
            created.setOnCompletionListener { finished ->
                if (generation == request && player === finished) stop()
            }
            created.setOnErrorListener { failed, _, _ ->
                if (generation == request && player === failed) fallback(word)
                true
            }
            val timeout = Runnable {
                if (!closed && generation == request && player === created) fallback(word)
            }
            preparationTimeout = timeout
            main.postDelayed(timeout, 15_000)
            created.prepareAsync()
        } catch (_: Exception) { fallback(word) }
    }

    fun stop() {
        if (closed) return
        generation++
        releasePlayer()
        narrator?.stop()
        usingTts = false
        state = PronunciationState.IDLE
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
        narrator?.close()
        narrator = null
        onFailure = null
    }

    private fun fallback(word: String) {
        releasePlayer()
        speakWithTts(word)
    }

    private fun speakWithTts(word: String) {
        if (closed) return
        usingTts = true
        state = PronunciationState.LOADING
        val speech = narrator ?: NativeNarrator(appContext).also { created ->
            narrator = created
            created.onStateChanged = callback@{ value ->
                if (!usingTts || closed) return@callback
                when (value) {
                    NarrationState.PLAYING -> state = PronunciationState.PLAYING
                    NarrationState.INITIALIZING, NarrationState.PREPARING ->
                        state = PronunciationState.LOADING
                    NarrationState.ERROR, NarrationState.UNAVAILABLE -> {
                        usingTts = false
                        state = PronunciationState.IDLE
                        onFailure?.invoke()
                    }
                    else -> {
                        usingTts = false
                        state = PronunciationState.IDLE
                    }
                }
            }
        }
        if (speech.state == NarrationState.UNAVAILABLE) {
            usingTts = false
            state = PronunciationState.IDLE
            onFailure?.invoke()
        } else speech.play(word)
    }

    private fun releasePlayer() {
        preparationTimeout?.let(main::removeCallbacks)
        preparationTimeout = null
        player?.let { active ->
            player = null
            active.setOnPreparedListener(null)
            active.setOnCompletionListener(null)
            active.setOnErrorListener(null)
            runCatching { active.release() }
        }
        releaseFocus()
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            audioManager.requestAudioFocus(focusRequest!!)
        else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN)
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun releaseFocus() {
        if (!hasFocus) return
        hasFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            audioManager.abandonAudioFocusRequest(focusRequest!!)
        else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    companion object {
        fun safeAudioUrl(value: String?): String? {
            val trimmed = value?.trim()?.takeIf { it.length <= 2048 } ?: return null
            val url = trimmed.toHttpUrlOrNull() ?: return null
            return trimmed.takeIf { url.scheme == "https" && url.host.isNotBlank() &&
                url.username.isEmpty() && url.password.isEmpty() }
        }
    }
}
