package br.gov.sp.pcsp.launcher

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TranscriptAssistantClientTest {

    @Test
    fun usesFallbackAfterPrimaryHttpError() {
        MockWebServer().use { primary ->
            MockWebServer().use { fallback ->
                primary.enqueue(MockResponse().setResponseCode(500))
                fallback.enqueue(MockResponse().setBody("fallback"))

                val response = call(primary.url("/"), fallback.url("/"))

                assertEquals("fallback", response)
                assertEquals(1, primary.requestCount)
                assertEquals(1, fallback.requestCount)
            }
        }
    }

    @Test
    fun usesFallbackAfterPrimaryNetworkFailure() {
        MockWebServer().use { fallback ->
            fallback.enqueue(MockResponse().setBody("fallback"))
            val unavailablePort = ServerSocket(0).use { it.localPort }

            val response = call(
                "http://127.0.0.1:$unavailablePort/",
                fallback.url("/").toString()
            )

            assertEquals("fallback", response)
            assertEquals(1, fallback.requestCount)
        }
    }

    @Test
    fun doesNotUseFallbackWhenPrimarySucceeds() {
        MockWebServer().use { primary ->
            MockWebServer().use { fallback ->
                primary.enqueue(MockResponse().setBody("primary"))

                val response = call(primary.url("/"), fallback.url("/"))

                assertEquals("primary", response)
                assertEquals(1, primary.requestCount)
                assertEquals(0, fallback.requestCount)
            }
        }
    }

    @Test
    fun requestHistoryMakesOnlyTheHistoryRequest() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"choices":[{"message":{"content":"Histórico gerado"}}]}"""
                )
            )
            val result = AtomicReference<Result<String>>()
            val completed = CountDownLatch(1)
            val config = ModelServerStore.Config(
                name = "test",
                url = server.url("/history").toString(),
                parameters = JSONObject(),
                provider = "test",
            )

            TranscriptAssistantClient.requestHistory(
                client = OkHttpClient(),
                serverConfig = config,
                transcript = "transcrição",
                historySystemPrompt = "sistema",
                historyUserPrompt = "usuário",
            ) {
                result.set(it)
                completed.countDown()
            }

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals("Histórico gerado", result.get().getOrThrow())
            assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest().body.readUtf8().contains("usuário"))
        }
    }

    @Test
    fun proxyGrokUsesResponsesInputPayloadWithoutClientKey() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"choices":[{"message":{"content":"Histórico do proxy"}}]}"""
                )
            )
            val result = AtomicReference<Result<String>>()
            val completed = CountDownLatch(1)
            val config = ModelServerStore.Config(
                name = GrokApiSettings.IA_PROXY_NAME,
                url = server.url("/chat/completions").toString(),
                parameters = JSONObject()
                    .put("model", GrokApiSettings.TEXT_NAME)
                    .put("temperature", 0.0)
                    .put("max_output_tokens", 10000)
                    .put("reasoning", JSONObject().put("effort", "low")),
                isProxy = true,
                provider = "grok",
            )

            TranscriptAssistantClient.requestHistory(
                client = OkHttpClient(),
                serverConfig = config,
                transcript = "material do proxy",
                historySystemPrompt = "sistema",
                historyUserPrompt = "usuário",
            ) {
                result.set(it)
                completed.countDown()
            }

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals("Histórico do proxy", result.get().getOrThrow())
            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertTrue(body.has("input"))
            assertTrue(!body.has("messages"))
            assertEquals("grok-4.6", body.optString("model"))
            assertTrue(request.getHeader("Authorization") == null)
        }
    }

    @Test
    fun proxyDeepseekUsesChatMessagesPayloadWithoutClientKey() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"choices":[{"message":{"content":"Histórico DeepSeek proxy"}}]}"""
                )
            )
            val result = AtomicReference<Result<String>>()
            val completed = CountDownLatch(1)
            val config = ModelServerStore.Config(
                name = GrokApiSettings.IA_PROXY_NAME,
                url = server.url("/chat/completions").toString(),
                parameters = JSONObject()
                    .put("model", GrokApiSettings.DEEPSEEK_TEXT_NAME)
                    .put("temperature", 0.0)
                    .put("max_tokens", 10000)
                    .put("reasoning_effort", "none"),
                isProxy = true,
                provider = "deepseek",
            )

            TranscriptAssistantClient.requestHistory(
                client = OkHttpClient(),
                serverConfig = config,
                transcript = "material do proxy DeepSeek",
                historySystemPrompt = "sistema",
                historyUserPrompt = "usuário",
            ) {
                result.set(it)
                completed.countDown()
            }

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals("Histórico DeepSeek proxy", result.get().getOrThrow())
            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())
            assertTrue(body.has("messages"))
            assertTrue(!body.has("input"))
            assertEquals("deepseek-v4-flash", body.optString("model"))
            assertTrue(request.getHeader("Authorization") == null)
        }
    }

    @Test
    fun directGrokKeepsResponsesInputPayload() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"output_text":"Histórico direto"}"""
                )
            )
            val result = AtomicReference<Result<String>>()
            val completed = CountDownLatch(1)
            val config = ModelServerStore.Config(
                name = GrokApiSettings.TEXT_NAME,
                url = server.url("/responses").toString(),
                parameters = JSONObject().put("model", GrokApiSettings.TEXT_NAME),
                provider = "grok",
            )

            TranscriptAssistantClient.requestHistory(
                client = OkHttpClient(),
                serverConfig = config,
                transcript = "material direto",
                historySystemPrompt = "sistema",
                historyUserPrompt = "usuário",
            ) {
                result.set(it)
                completed.countDown()
            }

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals("Histórico direto", result.get().getOrThrow())
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(body.has("input"))
            assertTrue(!body.has("messages"))
        }
    }

    private fun call(primaryUrl: Any, fallbackUrl: Any): String {
        val primary = Request.Builder().url(primaryUrl.toString()).build()
        val fallback = Request.Builder().url(fallbackUrl.toString()).build()
        return TranscriptAssistantClient.newFallbackCall(OkHttpClient(), primary, fallback)
            .execute()
            .use { it.body?.string().orEmpty() }
    }
}
