package br.gov.sp.pcsp.launcher

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TranscriptAssistantStreamingTest {
    @Test fun historyAndStatementPublishBeforeUpstreamCanFinish() {
        for (statement in listOf(false, true)) ServerSocket(0).use { server ->
            val release = CountDownLatch(1)
            val partial = CountDownLatch(1)
            val done = CountDownLatch(1)
            val result = AtomicReference<Result<String>>()
            val payload = AtomicReference<JSONObject>()
            val first = AtomicReference<String>()
            val serving = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) offset += reader.read(body, offset, length - offset)
                    payload.set(JSONObject(String(body)))
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    fun send(text: String) {
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        output.write("${bytes.size.toString(16)}\r\n".toByteArray())
                        output.write(bytes)
                        output.write("\r\n".toByteArray())
                        output.flush()
                    }
                    send("data: {\"choices\":[{\"delta\":{\"content\":\"João\"}}]}\n\n")
                    release.await(5, TimeUnit.SECONDS)
                    send("data: {\"choices\":[{\"delta\":{\"content\":\" Teste\"}}]}\n\ndata: [DONE]\n\n")
                    output.write("0\r\n\r\n".toByteArray())
                    output.flush()
                }
            }
            val config = ModelServerStore.Config("test", "http://127.0.0.1:${server.localPort}/", JSONObject(), provider = "test")
            val onPartial: (String) -> Unit = { first.compareAndSet(null, it); partial.countDown() }
            val callback: (Result<String>) -> Unit = { result.set(it); done.countDown() }
            val call = if (statement) TranscriptAssistantClient.requestStatement(
                OkHttpClient(), config, "material", "system", "user", onPartial, callback
            ) else TranscriptAssistantClient.requestHistory(
                OkHttpClient(), config, "material", "system", "user", onPartial, callback
            )
            try {
                assertTrue("o primeiro texto ficou retido", partial.await(3, TimeUnit.SECONDS))
                assertEquals("João", first.get())
                assertEquals("a conclusão ocorreu com o upstream ainda bloqueado", 1L, done.count)
                assertTrue(payload.get().getBoolean("stream"))
            } finally {
                release.countDown()
            }
            try {
                assertTrue(done.await(3, TimeUnit.SECONDS))
                assertEquals("João Teste", result.get().getOrThrow())
            } finally {
                call.cancel()
                serving.join(2000)
            }
        }
    }

    @Test fun localModelIsDiscoveredAgainForEveryRequest() {
        val previous = ModelServerStore.localModelId()
        try {
            MockWebServer().use { server ->
                val config = ModelServerStore.Config(ModelServerStore.LOCAL_TEXT_SERVER_NAME,
                    server.url("/v1/chat/completions").toString(), JSONObject().put("max_tokens", 100), provider = "servidor")
                for (id in listOf("/models/Modelo-A.gguf", "/models/Modelo-B.gguf")) {
                    server.enqueue(MockResponse().setBody("""{"data":[{"id":"$id"}]}"""))
                    server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"))
                    val done = CountDownLatch(1)
                    val result = AtomicReference<Result<String>>()
                    TranscriptAssistantClient.requestHistory(OkHttpClient(), config, "raw", "system", "user") {
                        result.set(it); done.countDown()
                    }
                    assertTrue(done.await(5, TimeUnit.SECONDS))
                    assertEquals("ok", result.get().getOrThrow())
                    assertEquals("/v1/models", server.takeRequest().path)
                    val request = server.takeRequest()
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals(id, body.getString("model"))
                    assertTrue(body.getBoolean("stream"))
                    assertEquals(id, ModelServerStore.localModelId())
                }
            }
        } finally {
            ModelServerStore.updateLocalModel(previous)
        }
    }

    @Test fun explicitProxyErrorIsReturnedAsFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"parcial\"}}]}\n\n" +
                    "data: {\"type\":\"error\",\"error\":{\"type\":\"upstream_error\",\"message\":\"Falha no provedor\"}}\n\n"))
            val done = CountDownLatch(1)
            val result = AtomicReference<Result<String>>()
            val partial = AtomicReference<String>()
            val config = ModelServerStore.Config("proxy", server.url("/").toString(), JSONObject(), isProxy = true)
            TranscriptAssistantClient.requestHistory(OkHttpClient(), config, "raw", "system", "user", partial::set) {
                result.set(it); done.countDown()
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("parcial", partial.get())
            assertTrue(result.get().isFailure)
            assertEquals("Falha no provedor", result.get().exceptionOrNull()?.message)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(body.has("messages"))
            assertFalse(body.has("input"))
        }
    }
}
