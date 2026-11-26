package com.aceinandroid.inkflow

import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.NativeAiGateway
import com.aceinandroid.inkflow.core.AiProviderConfig
import com.aceinandroid.inkflow.core.AiProviderType
import com.aceinandroid.inkflow.core.ai.RegisteredAiProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in live endpoint check. Credentials are supplied only in the target app's private cache. */
class NativeLocalAiEndpointTest {
    @Test fun explicitlyConfiguredLocalProvidersRespondThroughGateway() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val enabled = InstrumentationRegistry.getArguments().getString("exerciseLocalAi") == "true"
        assumeTrue("Live model requests require an explicit opt-in", enabled)
        val file = File(context.cacheDir, "local-ai-validation.json")
        try {
            val settings = JSONObject(file.readText())
            if (InstrumentationRegistry.getArguments().getString("configureProviders") == "true") {
                val providers = listOf(AiProviderType.OPENAI_COMPATIBLE to "openaiUrl",
                    AiProviderType.ANTHROPIC to "anthropicUrl").map { (type, field) ->
                    RegisteredAiProvider("local-${type.name}", "Local ${type.displayName}",
                        AiProviderConfig(type, settings.getString(field), settings.getString("key"),
                            settings.getString("model")))
                }
                com.aceinandroid.inkflow.ai.NativeAiStore(context).saveRegistry(
                    com.aceinandroid.inkflow.core.ai.AiProviderRegistry(providers))
            }
            if (InstrumentationRegistry.getArguments().getString("verifySavedConfig") == "true") {
                val saved = com.aceinandroid.inkflow.ai.NativeAiStore(context).loadConfig()
                assertTrue("Saved model must match the supplied model", saved.model == settings.getString("model"))
                assertTrue("Saved API key must match the supplied credential", saved.apiKey == settings.getString("key"))
                NativeAiGateway(context).testProvider(RegisteredAiProvider("saved-validation", "Saved configuration", saved))
            }
            for ((type, field) in listOf(AiProviderType.OPENAI_COMPATIBLE to "openaiUrl",
                AiProviderType.ANTHROPIC to "anthropicUrl")) {
                val config = AiProviderConfig(type, settings.getString(field), settings.getString("key"),
                    settings.getString("model"))
                val provider = RegisteredAiProvider("live-${type.name}", "Local validation", config)
                val result = NativeAiGateway(context).testProvider(provider)
                assertEquals(config.model, result.model)
                assertEquals(1, result.attempts)
                assertTrue(result.usage.output > 0)
                println("Live ${type.name}: gateway completed; output tokens=${result.usage.output}")
            }
        } finally {
            file.delete()
        }
    }
}
