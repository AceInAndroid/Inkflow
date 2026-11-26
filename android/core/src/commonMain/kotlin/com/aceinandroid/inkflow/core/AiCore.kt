package com.aceinandroid.inkflow.core

/**
 * Pure KMP AI reading workspace core:
 * - Provider configuration models (OpenAI-compatible / Anthropic / Gemini-compatible)
 * - Context budget assembly (never leaks unread future text; stays within safe token budget)
 * - Streaming SSE delta parser
 * - Built-in high-value reading quick actions (explain, translate, summarize, key points)
 */
enum class AiProviderType(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    OPENAI_COMPATIBLE("OpenAI Compatible", "https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    OLLAMA("Ollama", "http://127.0.0.1:11434/v1", "llama3.2"),
    GEMINI_OPENAI("Gemini (OpenAI API)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
}

data class AiProviderConfig(
    val providerType: AiProviderType = AiProviderType.OPENAI_COMPATIBLE,
    val baseUrl: String = AiProviderType.OPENAI_COMPATIBLE.defaultBaseUrl,
    val apiKey: String = "",
    val model: String = AiProviderType.OPENAI_COMPATIBLE.defaultModel,
    val temperature: Float = 0.4f,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && (apiKey.isNotBlank() || providerType == AiProviderType.OLLAMA) && model.isNotBlank()

    fun chatCompletionsUrl(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when (providerType) {
            AiProviderType.ANTHROPIC -> when {
                trimmed.endsWith("/messages") -> trimmed
                // Anthropic SDK-style base URLs may name only the server origin.
                Regex("^https?://[^/?#]+$").matches(trimmed) -> "$trimmed/v1/messages"
                else -> "$trimmed/messages"
            }
            else -> if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }
    }
}

enum class AiRole { SYSTEM, USER, ASSISTANT }

data class AiChatMessage(
    val id: String,
    val role: AiRole,
    val content: String,
    val quote: String? = null,
    val timestampMs: Long = 0L,
    val isError: Boolean = false,
    val visibleFromProgress: Int = 100,
    val chapterIndex: Int = -1,
    val readOffset: Int = -1,
    val sourceChapterTitle: String = "",
    val sourceProgress: Double = visibleFromProgress / 100.0,
)

enum class AiQuickAction(val id: String) {
    EXPLAIN_SELECTION("explain"),
    TRANSLATE_SELECTION("translate"),
    SUMMARIZE_CHAPTER("summarize_chapter"),
    KEY_TAKEAWAYS("key_takeaways"),
}

data class ReadingContextSnapshot(
    val bookTitle: String,
    val bookAuthor: String,
    val chapterTitle: String,
    val selectedText: String? = null,
    val precedingContext: String = "",
    val progressPercent: Int = 0,
    val readingProgress: Double = progressPercent / 100.0,
    val chapterIndex: Int = -1,
    val readOffset: Int = -1,
    val selectionWithinBoundary: Boolean = true,
)

object AiReadingPrompts {
    private const val MAX_PRECEDING_CHARS = 1600
    private const val MAX_SELECTION_CHARS = 1200
    private const val MAX_HISTORY_MESSAGES = 8

    fun systemPrompt(snapshot: ReadingContextSnapshot, preferChinese: Boolean): String = buildString {
        if (preferChinese) {
            appendLine("你是Inkflow（Inkflow）内置的 AI 阅读助手。")
            appendLine("原则：先给出简洁直接的解释，再补充必要细节；严禁剧透当前进度之后的未读内容；忠实于原文语境。")
        } else {
            appendLine("You are the built-in AI reading assistant for Inkflow.")
            appendLine("Principles: lead with a concise direct answer, then add helpful detail; never spoil unread content beyond the reader's current progress; stay faithful to the book context.")
        }
        appendLine()
        appendLine("Book: ${snapshot.bookTitle}")
        if (snapshot.bookAuthor.isNotBlank()) appendLine("Author: ${snapshot.bookAuthor}")
        if (snapshot.chapterTitle.isNotBlank()) appendLine("Chapter: ${snapshot.chapterTitle}")
        appendLine("Current progress: ${snapshot.progressPercent.coerceIn(0, 100)}%")
        val safePreceding = snapshot.precedingContext.trim().takeLast(MAX_PRECEDING_CHARS)
        if (safePreceding.isNotBlank()) {
            appendLine()
            appendLine("Visible preceding excerpt:")
            appendLine(safePreceding)
        }
    }.trim()

    fun quickActionPrompt(action: AiQuickAction, snapshot: ReadingContextSnapshot, preferChinese: Boolean): String {
        val quote = snapshot.selectedText?.trim()?.take(MAX_SELECTION_CHARS).orEmpty()
        return when (action) {
            AiQuickAction.EXPLAIN_SELECTION -> if (preferChinese) {
                "请结合上下文，简明解释下面这段原文的含义、隐含前提或修辞作用：\n\n「$quote」"
            } else {
                "Explain the meaning, nuance, and contextual role of this passage concisely:\n\n\"$quote\""
            }
            AiQuickAction.TRANSLATE_SELECTION -> if (preferChinese) {
                "请将下面这段文字自然准确地翻译为中文（若原文为中文则译为地道英文），并补充关键词语解释：\n\n「$quote」"
            } else {
                "Translate this passage naturally into English (or Chinese if already English) and briefly note key terms:\n\n\"$quote\""
            }
            AiQuickAction.SUMMARIZE_CHAPTER -> if (preferChinese) {
                "请基于我当前已读到的上文内容，用 3-5 个要点总结本节核心内容（不要推测或剧透未读部分）。"
            } else {
                "Summarize the key points of what I have read so far in this chapter in 3-5 bullets without spoiling ahead."
            }
            AiQuickAction.KEY_TAKEAWAYS -> if (preferChinese) {
                "请从我已读到的本节内容中提炼关键概念、人物/观点线索，以及一个值得思考的问题。"
            } else {
                "Extract key concepts or character/argument threads from the text I have read so far, plus one reflection question."
            }
        }
    }

    fun trimHistory(history: List<AiChatMessage>): List<AiChatMessage> =
        history.filter { !it.isError && it.content.isNotBlank() }.takeLast(MAX_HISTORY_MESSAGES).map { it.copy(content = it.content.take(4000), quote = it.quote?.take(MAX_SELECTION_CHARS)) }

    /**
     * Extracts text delta from a single SSE `data: ...` payload line for OpenAI-compatible or Anthropic streams.
     * Returns null for non-content frames or `[DONE]`.
     */
    fun extractSseDelta(dataLine: String, providerType: AiProviderType): String? {
        val payload = dataLine.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return when (providerType) {
            AiProviderType.ANTHROPIC -> extractJsonStringField(payload, "\"text\"")
            else -> {
                // OpenAI streaming format: {"choices":[{"delta":{"content":"..."}}]}
                val deltaIndex = payload.indexOf("\"delta\"")
                if (deltaIndex < 0) null
                else extractJsonStringField(payload.substring(deltaIndex), "\"content\"")
            }
        }
    }

    /**
     * Minimal, allocation-light JSON string field extractor (KMP-pure, no reflection/platform JSON dependency).
     */
    internal fun extractJsonStringField(json: String, quotedKey: String): String? {
        val keyIndex = json.indexOf(quotedKey)
        if (keyIndex < 0) return null
        var i = keyIndex + quotedKey.length
        while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == ':')) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (val next = json[i + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> if (i + 5 < json.length) {
                        val hex = json.substring(i + 2, i + 6)
                        hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                        i += 4
                    }
                    else -> out.append(next)
                }
                i += 2
            } else if (c == '"') {
                return out.toString()
            } else {
                out.append(c)
                i++
            }
        }
        return null
    }
}
