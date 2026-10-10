package br.gov.sp.pcsp.launcher

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Prova opt-in com fixture sintética aprovada; credencial e caminhos vêm só do ambiente. */
class GeminiSttRealApiTest {
    private fun key(): String {
        val key = System.getenv("SIG_GEMINI_TEST_KEY").orEmpty()
        assumeTrue("Prova real requer credencial temporária autorizada no ambiente.", key.isNotEmpty())
        return key
    }

    private fun fixture(variable: String): File {
        val file = File(System.getenv(variable).orEmpty())
        assumeTrue("Prova real requer fixture sintética aprovada.", file.isFile)
        return file
    }

    @Test(timeout = 120_000) fun restUploadInteractionsAndCleanup() {
        val key = key()
        val audio = fixture("SIG_GEMINI_TEST_WAV")
        val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()
        fun request(url: String) = Request.Builder().url(url).header("x-goog-api-key", key)
        fun execute(req: Request): Pair<String, String?> = http.newCall(req).execute().use {
            check(it.isSuccessful) { "Gemini HTTP ${it.code}" }
            it.body?.string().orEmpty() to it.header("X-Goog-Upload-URL")
        }
        var name: String? = null
        try {
            val (_, url) = execute(request(GeminiSttProtocol.FILES_URL)
                .header("X-Goog-Upload-Protocol", "resumable").header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Type", "audio/wav")
                .header("X-Goog-Upload-Header-Content-Length", audio.length().toString())
                .post("{\"file\":{\"display_name\":\"sig-synthetic-test\"}}".toRequestBody("application/json".toMediaType())).build())
            val (uploaded, _) = execute(request(GeminiSttProtocol.uploadUrl(url.orEmpty()))
                .header("X-Goog-Upload-Command", "upload, finalize").header("X-Goog-Upload-Offset", "0")
                .post(audio.asRequestBody("audio/wav".toMediaType())).build())
            var file = JSONObject(uploaded).getJSONObject("file")
            name = file.getString("name")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (file.optString("state") == "PROCESSING") {
                check(System.nanoTime() < deadline) { "Gemini file processing timeout" }
                Thread.sleep(500)
                file = JSONObject(execute(request(GeminiSttProtocol.fileUrl(name)).get().build()).first)
            }
            check(file.optString("state") != "FAILED") { "Gemini file failed" }
            val body = GeminiSttProtocol.restBody(file.getString("uri"), "audio/wav", listOf("pt-BR"), false, listOf("SIG"))
            val (result, _) = execute(request(GeminiSttProtocol.REST_URL).post(body.toRequestBody("application/json".toMediaType())).build())
            val transcript = GeminiSttProtocol.restText(JSONObject(result), false).lowercase(java.util.Locale.ROOT)
            assertTrue("REST deve retornar a última frase da fixture.", transcript.contains("português") && transcript.contains("corretamente"))
        } finally {
            name?.let { execute(request(GeminiSttProtocol.fileUrl(it)).delete().build()) }
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }

    @Test(timeout = 90_000) fun liveSetupPcmDraftFinalAndEnd() {
        val key = key()
        val pcm = fixture("SIG_GEMINI_TEST_PCM").readBytes()
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val finals = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<String?>(null)
        val drafts = AtomicInteger(0)
        val socket = http.newWebSocket(Request.Builder().url(GeminiSttProtocol.LIVE_URL)
            .header("x-goog-api-key", key).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(GeminiSttProtocol.setup(listOf("pt-BR"), listOf("SIG")))
            }
            private fun receive(text: String) {
                val event = GeminiSttProtocol.liveEvent(JSONObject(text))
                if (event.ready) ready.countDown()
                if (event.error) { failure.set("Gemini Live API error"); ready.countDown(); finished.countDown() }
                if (!event.draft.isNullOrBlank()) drafts.incrementAndGet()
                event.final?.takeIf { it.isNotBlank() }?.let {
                    finals.add(it)
                    if (finals.joinToString(" ").lowercase(java.util.Locale.ROOT).contains("corretamente")) finished.countDown()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = receive(text)
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = receive(bytes.utf8())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.set("Gemini WebSocket failure HTTP ${response?.code ?: 0}")
                ready.countDown(); finished.countDown()
            }
        })
        try {
            assertTrue("setupComplete deve chegar em 20s.", ready.await(20, TimeUnit.SECONDS))
            check(failure.get() == null) { failure.get().orEmpty() }
            var offset = 0
            while (offset < pcm.size) {
                val length = minOf(3200, pcm.size - offset)
                assertTrue(socket.send(GeminiSttProtocol.audio(pcm, offset, length)))
                offset += length
                Thread.sleep(100)
            }
            repeat(20) { socket.send(GeminiSttProtocol.audio(ByteArray(3200))); Thread.sleep(100) }
            assertTrue(socket.send(GeminiSttProtocol.endAudio()))
            assertTrue("Resultado final deve chegar.", finished.await(20, TimeUnit.SECONDS))
            check(failure.get() == null) { failure.get().orEmpty() }
            assertTrue("Live deve retornar a última frase da fixture.", finals.joinToString(" ").lowercase(java.util.Locale.ROOT).contains("corretamente"))
            assertTrue("Live deve emitir rascunhos.", drafts.get() > 0)
        } finally {
            socket.close(1000, "Teste concluído")
            socket.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }
}
