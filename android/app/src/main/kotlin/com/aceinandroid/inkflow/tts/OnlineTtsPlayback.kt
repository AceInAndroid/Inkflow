package com.aceinandroid.inkflow.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.aceinandroid.inkflow.core.tts.TtsChunk
import com.aceinandroid.inkflow.core.tts.TtsChunkQueue
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Owned by NativeNarrator on the main thread; buffers at most one chunk ahead of playback. */
internal class OnlineTtsPlayback(
    private val cache: File,
    private val attributes: AudioAttributes,
    private val onPreparing: () -> Unit,
    private val onPlaying: (Int) -> Unit,
    private val onChunkFinished: (Int) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "online-tts").apply { isDaemon = true }
    }
    private val adapter = OnlineTtsAdapter()
    private val queue = TtsChunkQueue(capacity = 2, maxLength = 1000)
    private val audio = mutableMapOf<Int, File>()
    private val requests = mutableMapOf<Int, Future<*>>()
    private var player: MediaPlayer? = null
    private var closed = false
    private lateinit var config: OnlineTtsConfig
    private var rate = 1f
    private var pitch = 1f

    fun start(text: String, offset: Int, config: OnlineTtsConfig, rate: Float, pitch: Float) {
        check(!closed)
        stop()
        this.config = config
        this.rate = rate
        this.pitch = pitch
        queue.reset(text, offset)
        onPreparing()
        fill()
    }

    fun updateParameters(rate: Float, pitch: Float) { this.rate = rate; this.pitch = pitch }

    fun stop() {
        queue.reset()
        requests.values.forEach { it.cancel(true) }
        requests.clear()
        adapter.cancel()
        player?.release()
        player = null
        audio.values.forEach { it.delete() }
        audio.clear()
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        executor.shutdownNow()
        cache.deleteRecursively()
    }

    private fun fill() {
        while (true) {
            val chunk = queue.reserve() ?: break
            val requestConfig = config
            val requestRate = rate
            requests[chunk.number] = executor.submit {
                try {
                    val file = adapter.synthesize(requestConfig, chunk.text, requestRate, cache)
                    main.post {
                        if (closed || !queue.contains(chunk)) file.delete()
                        else {
                            requests.remove(chunk.number)
                            audio[chunk.number] = file
                            playHead()
                        }
                    }
                } catch (failure: Exception) {
                    main.post {
                        if (!closed && queue.contains(chunk)) fail(failure.message ?: "Speech synthesis failed")
                    }
                }
            }
        }
        if (queue.head == null) onFinished() else playHead()
    }

    private fun playHead() {
        if (closed || player != null) return
        val chunk = queue.head ?: return
        val file = audio[chunk.number] ?: run { onPreparing(); return }
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(attributes)
            media.setDataSource(file.path)
            media.setOnPreparedListener {
                if (!current(chunk, media)) return@setOnPreparedListener
                try {
                    // Speed is synthesized by the provider; pitch is an optional device playback adjustment.
                    runCatching { media.playbackParams = media.playbackParams.setSpeed(1f).setPitch(pitch) }
                    media.start()
                    onPlaying(chunk.start)
                } catch (_: Exception) { fail("Cannot play speech audio") }
            }
            media.setOnCompletionListener {
                if (current(chunk, media) && queue.complete(chunk)) {
                    media.release()
                    player = null
                    audio.remove(chunk.number)?.delete()
                    onChunkFinished(chunk.end)
                    fill()
                }
            }
            media.setOnErrorListener { _, _, _ ->
                if (current(chunk, media)) fail("Cannot decode speech audio")
                true
            }
            media.prepareAsync()
        } catch (_: Exception) { fail("Cannot prepare speech audio") }
    }

    private fun current(chunk: TtsChunk, media: MediaPlayer): Boolean =
        !closed && queue.contains(chunk) && player === media

    private fun fail(message: String) { stop(); onError(message) }
}
