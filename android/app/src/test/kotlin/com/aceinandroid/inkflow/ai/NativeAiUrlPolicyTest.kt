package com.aceinandroid.inkflow.ai

import com.aceinandroid.inkflow.core.AiProviderConfig
import com.aceinandroid.inkflow.core.AiProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class NativeAiUrlPolicyTest {
    @Test fun permitsConfiguredLanServerForEveryProviderType() {
        AiProviderType.values().forEach { provider ->
            val config = AiProviderConfig(providerType = provider,
                baseUrl = "http://192.168.31.235:8080", apiKey = "fixture-key")
            assertTrue(config.isConfigured)
            val endpoint = URI(config.chatCompletionsUrl())
            assertTrue("$provider: $endpoint", NativeAiUrlPolicy.isAllowed(endpoint))
        }
    }

    @Test fun permitsOnlyLoopbackAndRfc1918HttpLiterals() {
        listOf("127.0.0.0", "127.0.0.1", "127.23.45.67", "127.255.255.255",
            "10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255",
            "192.168.0.0", "192.168.255.255", "localhost", "LOCALHOST",
            "[::1]", "[::0001]", "[0:0:0:0:0:0:0:1]", "[0000:0000:0000:0000:0000:0000:0000:0001]",
            "[0:0::1]", "[::0:1]").forEach { host ->
            assertTrue(host, NativeAiUrlPolicy.isAllowed(URI("http://$host:8080/v1")))
        }
    }

    @Test fun rejectsPublicAndOtherNonPrivateHttpAddresses() {
        listOf("126.255.255.255", "128.0.0.0", "9.255.255.255", "11.0.0.0",
            "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0",
            "8.8.8.8", "0.0.0.0", "169.254.1.1", "100.64.0.1", "[::]", "[::2]",
            "[2001:db8::1]", "[fc00::1]", "[fe80::1]", "[::ffff:127.0.0.1]").forEach { host ->
            assertFalse(host, NativeAiUrlPolicy.isAllowed(URI("http://$host:8080/v1")))
        }
    }

    @Test fun rejectsHostnameLookalikesAndAmbiguousIpv4Forms() {
        listOf("192.168.evil", "192.168.1.1.evil", "10.evil", "10.0.0.1.evil",
            "127.0.0.1.evil", "localhost.evil", "localhost.", "server.local", "provider.invalid",
            "192.168.0.256", "10.999.1.1", "10.1", "127.1", "2130706433", "0x7f000001",
            "0177.0.0.1", "127.000.0.1", "192.168.001.1", "10.0.0.1.").forEach { host ->
            assertFalse(host, NativeAiUrlPolicy.isAllowed(URI("http://$host:8080/v1")))
        }
        listOf("http://localhost@provider.invalid/v1", "http://192.168.1.1@provider.invalid/v1",
            "http://provider.invalid/192.168.1.1", "http:///192.168.1.1/v1").forEach { endpoint ->
            assertFalse(endpoint, NativeAiUrlPolicy.isAllowed(URI(endpoint)))
        }
    }

    @Test fun retainsHttpsForEveryProviderAndRequiresItForPublicEndpoints() {
        AiProviderType.values().forEach { provider ->
            listOf("provider.invalid", "8.8.8.8", "[2001:db8::1]").forEach { host ->
                listOf("http", "https").forEach { scheme ->
                    val config = AiProviderConfig(providerType = provider, baseUrl = "$scheme://$host/v1")
                    val endpoint = URI(config.chatCompletionsUrl())
                    if (scheme == "https") assertTrue("$provider: $endpoint", NativeAiUrlPolicy.isAllowed(endpoint))
                    else assertFalse("$provider: $endpoint", NativeAiUrlPolicy.isAllowed(endpoint))
                }
            }
        }
    }

    @Test fun rejectsOtherSchemesAndHostlessUrls() {
        listOf("ftp://127.0.0.1/v1", "ws://localhost/v1", "file:///v1", "/v1",
            "//localhost/v1", "http:/v1").forEach { endpoint ->
            assertFalse(endpoint, NativeAiUrlPolicy.isAllowed(URI(endpoint)))
        }
    }
}
