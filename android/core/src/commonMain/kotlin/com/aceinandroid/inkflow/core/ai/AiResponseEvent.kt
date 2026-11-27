package com.aceinandroid.inkflow.core.ai

/** Public execution states only; these events never represent a model's private reasoning. */
enum class AiExecutionStage { READING_MATERIALS, CONTEXT_READY, GENERATING, VALIDATING_SOURCES }
data class AiCitation(val id: String, val bookId: Long, val source: BookMaterialSource)
sealed interface AiResponseEvent {
    data class Status(val stage: AiExecutionStage) : AiResponseEvent
    data class TextDelta(val text: String) : AiResponseEvent
    data class Citation(val citation: AiCitation) : AiResponseEvent
    data class Finished(val result: AiRequestResult) : AiResponseEvent
    data object Stopped : AiResponseEvent
    data object Failed : AiResponseEvent
}

/** Unknown or repeated source markers cannot become clickable links. */
object AiCitationMarkers {
    private val marker = Regex("\\[\\[source:([A-Za-z0-9_.:-]{1,180})]]")
    fun referenced(text: String, allowed: List<AiCitation>): List<AiCitation> {
        val ids = marker.findAll(text).map { it.groupValues[1] }.toSet()
        return allowed.filter { it.id in ids }.distinctBy { it.id }
    }
}
