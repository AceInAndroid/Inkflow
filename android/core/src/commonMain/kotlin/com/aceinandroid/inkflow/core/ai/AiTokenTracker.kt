package com.aceinandroid.inkflow.core.ai

import kotlin.math.ceil

data class AiTokenUsage(val input: Long, val output: Long, val estimated: Boolean = false) {
    init { require(input >= 0 && output >= 0) }
    val total: Long get() = input + output
}
object AiTokenTracker {
    fun estimate(input: String, output: String) = AiTokenUsage(count(input), count(output), true)
    private fun count(text: String): Long {
        // CJK text commonly uses more tokens per character than Latin text.
        val cjk = text.count { it in '\u2e80'..'\u9fff' }
        return cjk.toLong() + ceil((text.length - cjk) / 4.0).toLong()
    }
}
