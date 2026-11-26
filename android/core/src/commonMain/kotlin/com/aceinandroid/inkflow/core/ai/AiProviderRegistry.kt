package com.aceinandroid.inkflow.core.ai

import com.aceinandroid.inkflow.core.AiProviderConfig

enum class AiProviderRole { CONVERSATION, LIGHTWEIGHT }

data class RegisteredAiProvider(
    val id: String,
    val name: String,
    val config: AiProviderConfig,
    val apiKeys: List<String> = listOf(config.apiKey),
)

/** Ordered, deterministic routing. A lightweight role falls back to the main role only when unassigned. */
class AiProviderRegistry(
    val providers: List<RegisteredAiProvider>,
    val conversationId: String? = providers.firstOrNull()?.id,
    val lightweightId: String? = null,
) {
    init { require(providers.map { it.id }.distinct().size == providers.size) }

    fun candidates(role: AiProviderRole): List<Pair<String, AiProviderConfig>> {
        val chosen = if (role == AiProviderRole.LIGHTWEIGHT) lightweightId ?: conversationId else conversationId
        val provider = providers.firstOrNull { it.id == chosen } ?: return emptyList()
        val keys = provider.apiKeys.map(String::trim).filter(String::isNotEmpty).distinct()
        return (if (keys.isEmpty()) listOf(provider.config) else keys.map { provider.config.copy(apiKey = it) })
            .filter { it.isConfigured }.map { provider.id to it }
    }
}
