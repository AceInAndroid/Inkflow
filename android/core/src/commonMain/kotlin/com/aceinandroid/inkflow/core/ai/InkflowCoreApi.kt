package com.aceinandroid.inkflow.core.ai

import com.aceinandroid.inkflow.core.ReadingContextSnapshot

/** Small Objective-C/Swift surface; platform code supplies transport, never another prompt pipeline. */
class InkflowCoreApi {
    private val registry = ReadingSkillRegistry()
    private val assembler = AiContextAssembler(registry)

    fun readingSkills(): List<InkflowSkill> = registry.skills.map { InkflowSkill(it.id, it.title, it.summary) }

    /** Local preparation only. sourceText is the current chapter/page, in UTF-16 coordinates. */
    fun prepareReadingRequest(
        requestId: String,
        bookTitle: String,
        bookAuthor: String,
        chapterTitle: String,
        sourceText: String,
        chapterIndex: Int,
        chapterCount: Int,
        readOffset: Int,
        selectedStart: Int,
        selectedEnd: Int,
        prompt: String,
        skillId: String,
        workload: String,
        preferChinese: Boolean,
        userAuthorized: Boolean,
    ): InkflowPreparedRequest {
        fun rejected(reason: String) = InkflowPreparedRequest(null, "", reason)
        if (!userAuthorized) return rejected("Explicit user action required")
        if (requestId.isBlank() || prompt.isBlank()) return rejected("Request and prompt must not be empty")
        if (registry.find(skillId) == null) return rejected("Unknown reading skill")
        if (workload !in AiWorkloads.registered) return rejected("Unknown AI workload")
        if (chapterCount <= 0 || chapterIndex !in 0 until chapterCount || readOffset !in 0..sourceText.length)
            return rejected("Invalid reading boundary")
        val hasSelection = selectedStart != selectedEnd
        if (hasSelection && (selectedStart < 0 || selectedEnd <= selectedStart || selectedEnd > readOffset))
            return rejected("Show the selection end before preparing a request")
        // Reject positions inside a surrogate pair rather than silently changing the captured source.
        fun splitsSurrogate(offset: Int) = offset > 0 && offset < sourceText.length &&
            sourceText[offset - 1].isHighSurrogate() && sourceText[offset].isLowSurrogate()
        if (splitsSurrogate(readOffset) || (hasSelection &&
                (splitsSurrogate(selectedStart) || splitsSurrogate(selectedEnd))))
            return rejected("Invalid UTF-16 boundary")
        val progress = (chapterIndex + if (sourceText.isEmpty()) 1.0 else readOffset.toDouble() / sourceText.length) / chapterCount
        val snapshot = ReadingContextSnapshot(
            bookTitle, bookAuthor, chapterTitle,
            selectedText = if (hasSelection) sourceText.substring(selectedStart, selectedEnd) else null,
            precedingContext = sourceText.substring(0, readOffset).takeLast(1600),
            progressPercent = (progress * 100).toInt(), readingProgress = progress,
            chapterIndex = chapterIndex, readOffset = readOffset,
        )
        val request = AiRequest(requestId, workload, snapshot, prompt, skillId = skillId,
            preferChinese = preferChinese, userAuthorized = true)
        return InkflowPreparedRequest(request, assembler.assemble(request).systemPrompt, "")
    }

    /** Any future platform send still traverses the existing authorization/routing/fallback gateway. */
    @Throws(Exception::class)
    suspend fun execute(request: InkflowPreparedRequest, gateway: AiRequestGateway,
                        onDelta: (String) -> Unit): AiRequestResult =
        gateway.execute(requireNotNull(request.request) { request.error }, onDelta = { onDelta(it) })
}

class InkflowSkill(val id: String, val title: String, val summary: String)
class InkflowPreparedRequest internal constructor(
    internal val request: AiRequest?,
    val systemPrompt: String,
    val error: String,
) {
    val isValid: Boolean get() = request != null
    val workload: String get() = request?.workload.orEmpty()
    val skillId: String get() = request?.skillId.orEmpty()
    val prompt: String get() = request?.prompt.orEmpty()
    val readingProgress: Double get() = request?.snapshot?.readingProgress ?: 0.0
    val chapterIndex: Int get() = request?.snapshot?.chapterIndex ?: -1
    val readOffset: Int get() = request?.snapshot?.readOffset ?: -1
}
