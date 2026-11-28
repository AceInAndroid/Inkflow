package com.aceinandroid.inkflow.core

/** Independent pause owners: dismissing one UI must never release another owner's pause. */
enum class AutomaticReadingPause(val bit: Int) {
    TOOLBAR(1), LIFECYCLE(2), SELECTION(4), DIALOG(8), AI_SHEET(16), LOOKUP(32),
    NARRATION(64), CONTENT_LOADING(128),
}

data class AutomaticReadingState(val enabled: Boolean = false, val pauseMask: Int = 0) {
    val advancing: Boolean get() = enabled && pauseMask == 0
    fun pause(reason: AutomaticReadingPause, active: Boolean): AutomaticReadingState = copy(
        pauseMask = if (active) pauseMask or reason.bit else pauseMask and reason.bit.inv(),
    )
}

/** Local sentence pacing only. No request, permission, progress write or background timer. */
object AutomaticReading {
    fun sentenceAtOrAfter(text: String, offset: Int): TextSelectionRange? {
        var probe = offset.coerceIn(0, text.length)
        while (probe < text.length) {
            if (!text[probe].isWhitespace() && text[probe] != '\uFFFC') {
                return SentenceBoundaryResolver.sentenceRangeAt(text, probe)
            }
            probe++
        }
        return null
    }

    fun dwellMillis(sentence: String, speed: Float): Long {
        require(speed.isFinite() && speed in 0.5f..3f)
        var cjk = 0
        var words = 0
        var latinWord = false
        sentence.forEach { ch ->
            when {
                SentenceBoundaryResolver.isCjk(ch) || ch.code in 0x3040..0x30ff || ch.code in 0xac00..0xd7af -> {
                    cjk++; latinWord = false
                }
                ch.isLetterOrDigit() -> { if (!latinWord) words++; latinWord = true }
                else -> latinWord = false
            }
        }
        // 260 CJK characters / minute, 200 other words / minute, plus a sentence rest.
        return ((cjk * 60_000.0 / 260 + words * 300.0 + 500) / speed)
            .toLong().coerceIn(600, 120_000)
    }
}
