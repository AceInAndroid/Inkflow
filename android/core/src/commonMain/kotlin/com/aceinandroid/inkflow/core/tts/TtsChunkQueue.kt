package com.aceinandroid.inkflow.core.tts

data class TtsChunk(val generation: Long, val number: Int, val start: Int, val end: Int, val text: String)

/** Owner-thread queue. At most [capacity] chunks can be reserved, including the currently playing chunk. */
class TtsChunkQueue(val capacity: Int = 2, val maxLength: Int = 1000) {
    init { require(capacity in 1..4 && maxLength in 2..3000) }
    private var source = ""
    private var offset = 0
    private var number = 0
    private val pending = mutableListOf<TtsChunk>()
    var generation: Long = 0
        private set
    val size: Int get() = pending.size
    val head: TtsChunk? get() = pending.firstOrNull()

    fun reset(text: String = "", fromOffset: Int = 0) {
        generation++
        source = text
        offset = fromOffset.coerceIn(0, text.length)
        if (offset in 1 until text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset--
        number = 0
        pending.clear()
    }

    fun reserve(): TtsChunk? {
        if (pending.size >= capacity) return null
        var start = offset
        while (start < source.length && (source[start].isWhitespace() || source[start] == '\uFFFC')) start++
        if (start == source.length) return null
        var limit = (start.toLong() + maxLength).coerceAtMost(source.length.toLong()).toInt()
        if (limit < source.length && source[limit].isLowSurrogate() && source[limit - 1].isHighSurrogate()) limit--
        var end = limit
        if (limit < source.length && maxLength >= 4) {
            val earliest = start + (limit - start) / 2
            val sentence = (earliest until limit).lastOrNull { source[it] in ".!?。！？\n" }
            val word = (earliest until limit).lastOrNull { source[it].isWhitespace() }
            end = (sentence ?: word)?.plus(1) ?: limit
        }
        return TtsChunk(generation, number++, start, end,
            source.substring(start, end).replace('\uFFFC', ' ')).also { pending += it; offset = end }
    }

    fun contains(chunk: TtsChunk): Boolean = chunk.generation == generation && chunk in pending

    /** Reject out-of-order and stale playback callbacks; only the head can advance playback. */
    fun complete(chunk: TtsChunk): Boolean {
        if (head != chunk) return false
        pending.removeAt(0)
        return true
    }
}
