package com.aceinandroid.inkflow.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AiProviderEndpointTest {
    @Test fun anthropicOriginIncludesVersionedMessagesRoute() {
        for (base in listOf("http://192.168.31.235:8080", "http://192.168.31.235:8080/",
            "https://api.anthropic.com")) {
            assertEquals(base.trimEnd('/') + "/v1/messages",
                AiProviderConfig(AiProviderType.ANTHROPIC, base).chatCompletionsUrl())
        }
    }

    @Test fun explicitAnthropicPathsRemainIntact() {
        for (base in listOf("https://example.com/v1", "https://example.com/proxy/v1")) {
            assertEquals("$base/messages",
                AiProviderConfig(AiProviderType.ANTHROPIC, base).chatCompletionsUrl())
            assertEquals("$base/messages",
                AiProviderConfig(AiProviderType.ANTHROPIC, "$base/messages/").chatCompletionsUrl())
        }
    }

    @Test fun openAiCompatibleRoutesRemainIntact() {
        val base = "http://192.168.31.235:8080/v1"
        assertEquals("$base/chat/completions", AiProviderConfig(baseUrl = base).chatCompletionsUrl())
        assertEquals("$base/chat/completions", AiProviderConfig(baseUrl = "$base/chat/completions/").chatCompletionsUrl())
    }
}
