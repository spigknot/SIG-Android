package br.gov.sp.pcsp.launcher

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Vacinas do Grok STT: taxa de início, backoff, Retry-After, cancelamento e escopo. */
class GrokSttRequestPolicyTest {
    private class Clock {
        var now = 100_000L
        var cancelled = false
        val sleeps = mutableListOf<Long>()
        fun sleep(ms: Long) { now += ms; sleeps += ms }
        fun check() { if (cancelled) throw CancellationException() }
        fun pacer() = GrokSttRequestPolicy.Pacer({ now }, ::sleep)
    }

    @Test fun `no maximo oito headers em qualquer segundo sem rajada inicial`() {
        val clock = Clock()
        val pacer = clock.pacer()
        val starts = (1..40).map {
            pacer.begin(clock::check)
            val start = clock.now
            pacer.end()
            start
        }
        starts.forEach { start -> assertTrue(starts.count { it >= start && it < start + 1000 } <= 8) }
        starts.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 125) }
    }

    @Test fun `headers lentos nao acumulam permissoes`() {
        val clock = Clock()
        val pacer = clock.pacer()
        pacer.begin(clock::check)
        clock.now += 3000
        pacer.end()
        pacer.begin(clock::check)
        pacer.end()
        assertEquals(125L, clock.sleeps.sum())
    }

    @Test fun `cooldown compartilhado nao pode ser encurtado`() {
        val clock = Clock()
        val pacer = clock.pacer()
        pacer.defer(4000)
        pacer.defer(1000)
        pacer.begin(clock::check)
        pacer.end()
        assertEquals(4000L, clock.sleeps.sum())
    }

    @Test fun `cancelamento interrompe pacing sem enviar`() {
        val clock = Clock()
        val pacer = GrokSttRequestPolicy.Pacer({ clock.now }) { clock.cancelled = true }
        pacer.begin(clock::check)
        pacer.end()
        assertThrows(CancellationException::class.java) { pacer.begin(clock::check) }
    }

    @Test fun `cinco tentativas com backoff e jitter`() {
        val clock = Clock()
        val metrics = GrokSttRequestPolicy.Metrics { clock.now }
        var attempts = 0
        val notices = mutableListOf<String>()
        val reply = GrokSttRequestPolicy.execute(
            checkCancelled = clock::check,
            request = { attempts++; GrokSttRequestPolicy.Reply(429, "busy") },
            metrics = metrics, pacer = clock.pacer(), onRetry = notices::add,
            sleep = clock::sleep, jitterMillis = { 250 },
        )
        assertEquals(429, reply.status)
        assertEquals(5, attempts)
        assertEquals(4, notices.size)
        assertEquals(1250L + 2250 + 4250 + 8250, clock.sleeps.sum())
        assertTrue(metrics.summary().contains("429 5 (100%)"))
    }

    @Test fun `Retry After numerico prevalece sobre backoff`() {
        val clock = Clock()
        var attempts = 0
        val reply = GrokSttRequestPolicy.execute(
            checkCancelled = clock::check,
            request = { attempts++; if (attempts == 1) GrokSttRequestPolicy.Reply(429, "busy", "12")
                        else GrokSttRequestPolicy.Reply(200, "ok") },
            metrics = GrokSttRequestPolicy.Metrics(), pacer = clock.pacer(),
            sleep = clock::sleep, jitterMillis = { 0 },
        )
        assertEquals("ok", reply.value)
        assertEquals(12000L, clock.sleeps.sum())
    }

    @Test fun `Retry After em data HTTP e valores invalidos`() {
        val wall = 1_700_000_000_000L
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val value = formatter.format(java.util.Date(wall + 30000))
        assertEquals(30000L, GrokSttRequestPolicy.retryDelayMillis(0, value, wall, 250))
        listOf(null, "", "bad", "-3", "NaN", "Infinity").forEach {
            assertEquals(1250L, GrokSttRequestPolicy.retryDelayMillis(0, it, wall, 250))
        }
    }

    @Test fun `erros deterministas nao sao reenviados`() {
        listOf(400, 401, 403, 404, 413, 422).forEach { code ->
            var attempts = 0
            GrokSttRequestPolicy.execute(
                checkCancelled = {}, request = { attempts++; GrokSttRequestPolicy.Reply(code, "bad", "12") },
                metrics = GrokSttRequestPolicy.Metrics(), sleep = { fail("Não pode aguardar/reenviar $code") },
            )
            assertEquals(1, attempts)
        }
    }

    @Test fun `cancelamento durante backoff nao envia outra tentativa`() {
        val clock = Clock()
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            GrokSttRequestPolicy.execute(
                checkCancelled = clock::check,
                request = { attempts++; GrokSttRequestPolicy.Reply(429, "busy") },
                metrics = GrokSttRequestPolicy.Metrics(), pacer = clock.pacer(),
                sleep = { clock.cancelled = true },
            )
        }
        assertEquals(1, attempts)
    }

    @Test fun `aviso exclusivo do Grok na Transcricao`() {
        assertTrue(GrokSttRequestPolicy.showWarning(true, true))
        assertFalse(GrokSttRequestPolicy.showWarning(true, false))
        assertFalse(GrokSttRequestPolicy.showWarning(false, true))
        assertTrue(GrokSttRequestPolicy.HELP_TEXT.contains("10 RPS"))
        assertTrue(GrokSttRequestPolicy.HELP_TEXT.contains("atraso"))
    }

    @Test fun `UI coloca aviso amarelo a direita de modelos apenas no layout Transcricao`() {
        val root = if (File("src/main/res").isDirectory) File("src/main/res") else File("app/src/main/res")
        val layout = File(root, "layout/activity_remote_stt_transcription.xml").readText()
        val occurrence = File(root, "layout/activity_remote_stt_occurrence.xml").readText()
        assertTrue(layout.indexOf("button_grok_rate_limit_help") > layout.indexOf("button_model_settings"))
        assertTrue(layout.contains("#FFFFC107"))
        assertTrue(layout.contains("android:visibility=\"gone\""))
        assertFalse(occurrence.contains("button_grok_rate_limit_help"))
    }

    @Test fun `HTTP real respeita pacing e mantem respostas paralelas`() {
        MockWebServer().use { server ->
            repeat(12) { server.enqueue(MockResponse().setBody("ok").setBodyDelay(350, TimeUnit.MILLISECONDS)) }
            val metrics = GrokSttRequestPolicy.Metrics()
            val pacer = GrokSttRequestPolicy.Pacer()
            val client = GrokSttHttpClient.create(OkHttpClient(), {}, { metrics }, pacer)
            val executor = Executors.newFixedThreadPool(4)
            val starts = java.util.Collections.synchronizedList(mutableListOf<Long>())
            val observer = Thread {
                repeat(12) {
                    assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                    starts += System.nanoTime() / 1_000_000
                }
            }
            observer.start()
            try {
                val futures = (1..12).map {
                    executor.submit<String> {
                        client.newCall(Request.Builder().url(server.url("/stt")).post("audio".toRequestBody()).build())
                            .execute().use { it.body!!.string() }
                    }
                }
                futures.forEach { assertEquals("ok", it.get(10, TimeUnit.SECONDS)) }
                observer.join(12000)
                assertEquals(12, starts.size)
                starts.forEach { start -> assertTrue(starts.count { it >= start && it < start + 1000 } <= 10) }
                // 12 respostas com 350ms, se serializadas, levariam pelo menos 4,2s.
                assertTrue(starts.last() - starts.first() < 3500)
            } finally {
                executor.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun `503 Retry After zero nao cria tentativa invisivel do OkHttp`() {
        MockWebServer().use { server ->
            repeat(10) { server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")) }
            val clock = Clock()
            val pacer = clock.pacer()
            val metrics = GrokSttRequestPolicy.Metrics { clock.now }
            val client = GrokSttHttpClient.create(OkHttpClient(), clock::check, { metrics }, pacer)
            val request = Request.Builder().url(server.url("/stt")).post("audio".toRequestBody()).build()
            GrokSttRequestPolicy.execute(
                checkCancelled = clock::check,
                request = { client.newCall(request).execute().use {
                    GrokSttRequestPolicy.Reply(it.code, it.body?.string().orEmpty(), it.header("Retry-After"))
                } },
                metrics = metrics, pacer = pacer, sleep = clock::sleep, jitterMillis = { 0 },
            )
            assertEquals(5, server.requestCount)
            repeat(5) { assertEquals("audio", server.takeRequest().body.readUtf8()) }
            client.connectionPool.evictAll()
        }
    }
}
