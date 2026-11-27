package com.aceinandroid.inkflow.core.ai

/** Descriptive local capabilities, not executable tools or a grant of permissions. */
enum class ReadingLocalCapability {
    VERIFIED_BOOK_CONTEXT, SOURCE_NAVIGATION, LOCAL_BOOK_SEARCH, NOTE_PREVIEW,
}

data class ReadingAgent(
    val id: String,
    val defaultSkillId: String,
    val localCapabilities: Set<ReadingLocalCapability>,
    val enabled: Boolean = true,
)

/** Stable catalog only. All execution still belongs to the existing gateway/action boundaries. */
class ReadingAgentRegistry {
    val agents: List<ReadingAgent> get() = registered
    val available: List<ReadingAgent> get() = agents.filter { it.enabled }
    fun canonicalId(id: String?): String? = aliases[id] ?: id
    fun find(id: String?): ReadingAgent? = agents.firstOrNull { it.id == canonicalId(id) }
    fun resolve(id: String?): ReadingAgent = find(id)?.takeIf { it.enabled } ?: registered.first()
    fun resolveBookAgent(bookAgentId: String?, deviceAgentId: String?): ReadingAgent =
        find(bookAgentId)?.takeIf { it.enabled } ?: resolve(deviceAgentId)

    fun resolveSkill(agentId: String, skills: ReadingSkillRegistry, bookSkillId: String? = null,
                     deviceSkillId: String? = null): ReadingSkill {
        val agent = requireNotNull(find(agentId)?.takeIf { it.enabled }) { "Agent is unavailable" }
        return skills.find(bookSkillId) ?: skills.find(deviceSkillId) ?: requireNotNull(skills.find(agent.defaultSkillId))
    }

    companion object {
        const val DEFAULT_ID = "agent.general"
        private val context = setOf(ReadingLocalCapability.VERIFIED_BOOK_CONTEXT, ReadingLocalCapability.SOURCE_NAVIGATION)
        val aliases: Map<String, String> = mapOf("agent.scholar" to "agent.argument", "agent.notes" to DEFAULT_ID)
        private val registered = listOf(
            ReadingAgent(DEFAULT_ID, "reading.general", context + ReadingLocalCapability.NOTE_PREVIEW),
            ReadingAgent("agent.story", "reading.characters", context + ReadingLocalCapability.LOCAL_BOOK_SEARCH),
            ReadingAgent("agent.background", "reading.history", context + ReadingLocalCapability.LOCAL_BOOK_SEARCH),
            ReadingAgent("agent.argument", "reading.argument", context + ReadingLocalCapability.LOCAL_BOOK_SEARCH),
            ReadingAgent("agent.language", "reading.language", context),
            ReadingAgent("agent.coach", "reading.socratic", emptySet(), enabled = false),
        )
    }
}
