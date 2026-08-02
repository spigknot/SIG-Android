package br.gov.sp.pcsp.launcher

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket

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

    private fun call(primaryUrl: Any, fallbackUrl: Any): String {
        val primary = Request.Builder().url(primaryUrl.toString()).build()
        val fallback = Request.Builder().url(fallbackUrl.toString()).build()
        return TranscriptAssistantClient.newFallbackCall(OkHttpClient(), primary, fallback)
            .execute()
            .use { it.body?.string().orEmpty() }
    }
}
