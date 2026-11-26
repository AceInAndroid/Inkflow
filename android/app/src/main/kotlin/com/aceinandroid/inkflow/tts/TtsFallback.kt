package com.aceinandroid.inkflow.tts

import com.aceinandroid.inkflow.core.tts.TtsChunkQueue

/** Per-playback routing: a failed online session stays on system speech until the next explicit Play. */
internal class TtsFallback(private val systemQueue: TtsChunkQueue) {
    private var active = false

    fun usesOnline(enabled: Boolean): Boolean = enabled && !active

    fun reset() { active = false }

    /** Keep the caller's source/cursor; invalidate all reservations before attempting a local voice. */
    fun recover(source: String, cursor: Int, systemReady: Boolean, prepareOfflineVoice: () -> Boolean): Boolean {
        systemQueue.reset(source, cursor)
        if (!systemReady || !prepareOfflineVoice()) return false
        active = true
        return true
    }
}
