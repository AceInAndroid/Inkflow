package com.aceinandroid.inkflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.ai.NativeAiGateway
import com.aceinandroid.inkflow.ai.NativeAiStore
import com.aceinandroid.inkflow.core.AiProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/** Explicit acceptance against existing encrypted device credentials; no configuration is exported. */
@RunWith(AndroidJUnit4::class)
class NativeSavedLocalAiTest {
    @Test fun savedLocalOpenAiAndAnthropicProvidersCompleteThroughTheGateway() = runBlocking {
        assumeTrue("Live saved-provider tests require an explicit opt-in",
            InstrumentationRegistry.getArguments().getString("exerciseSavedLocalAi") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val providers = NativeAiStore(context).loadRegistry().providers.filter {
            val address = runCatching { URI(it.config.baseUrl) }.getOrNull()
            address?.host == "192.168.31.235" && address.port == 8080 &&
                it.config.model == "swift-1.5-iq3_s-sm75-v01441" && it.config.isConfigured
        }
        for (type in listOf(AiProviderType.OPENAI_COMPATIBLE, AiProviderType.ANTHROPIC)) {
            val provider = requireNotNull(providers.firstOrNull { it.config.providerType == type }) {
                "The explicitly requested local provider must already be configured on this device"
            }
            // Do not report transport messages: they may contain request headers or model output.
            val result = try { NativeAiGateway(context).testProvider(provider) }
                catch (_: Exception) { throw AssertionError("Saved local ${type.name} request failed; inspect private device diagnostics") }
            assertEquals(provider.id, result.providerId)
            assertEquals(provider.config.model, result.model)
            assertTrue(result.usage.output > 0)
            assertEquals(1, result.attempts)
            println("Saved local ${type.name}: gateway response accepted; outputTokens=${result.usage.output}; estimated=${result.usage.estimated}")
        }
    }
}
