package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Instant
import java.util.concurrent.CancellationException

class GeminiSttRequestPolicyTest {
    private class Clock {
        var now = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
        val waits = mutableListOf<Long>()
        fun sleep(ms: Long) { waits += ms; now += ms }
    }

    @Test fun pacingHasNoBurstAndSurvivesNewPolicyWithPersistedState() {
        val clock = Clock()
        val state = GeminiSttRequestPolicy.State()
        val starts = mutableListOf<Long>()
        repeat(40) {
            val policy = GeminiSttRequestPolicy(state, now = { clock.now }, sleep = clock::sleep)
            policy.begin {}; starts += clock.now; policy.end()
        }
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= 7_500 })
        assertTrue(starts.all { start -> starts.count { it >= start && it < start + 60_000 } <= 8 })
        assertEquals(40, state.used)
    }

    @Test fun slowHeadersDoNotBankPermits() {
        val clock = Clock()
        val policy = GeminiSttRequestPolicy(now = { clock.now }, sleep = clock::sleep)
        policy.begin {}; clock.sleep(30_000); policy.end()
        val previous = clock.now
        policy.begin {}; policy.end()
        assertEquals(7_500L, clock.now - previous)
    }

    @Test fun dailyLimitBlocksAndResetsAtPacificMidnight() {
        val clock = Clock()
        val state = GeminiSttRequestPolicy.State()
        val policy = GeminiSttRequestPolicy(state, now = { clock.now }, sleep = clock::sleep)
        repeat(100) { policy.begin {}; policy.end() }
        assertThrows(IllegalStateException::class.java) { policy.begin {} }
        clock.now = Instant.parse("2026-10-11T07:00:00Z").toEpochMilli()
        policy.begin {}; policy.end()
        assertEquals(1, state.used)
        assertEquals("2026-10-11", state.day)
    }

    @Test fun pacificDayRespectsDst() {
        assertEquals("2026-03-07", GeminiSttRequestPolicy.pacificDay(Instant.parse("2026-03-08T07:59:59Z").toEpochMilli()))
        assertEquals("2026-03-08", GeminiSttRequestPolicy.pacificDay(Instant.parse("2026-03-08T08:00:00Z").toEpochMilli()))
        assertEquals("2026-11-01", GeminiSttRequestPolicy.pacificDay(Instant.parse("2026-11-02T07:59:59Z").toEpochMilli()))
        assertEquals("2026-11-02", GeminiSttRequestPolicy.pacificDay(Instant.parse("2026-11-02T08:00:00Z").toEpochMilli()))
    }

    @Test fun retryAfterAndRetryInfoAreRespectedAndDeterministicErrorsAreNotRetried() {
        val clock = Clock()
        val policy = GeminiSttRequestPolicy(now = { clock.now }, sleep = clock::sleep)
        var calls = 0
        val initial = clock.now
        policy.execute({}, {
            calls++
            if (calls == 1) GeminiSttRequestPolicy.Reply(429, "busy", mapOf("Retry-After" to "12"), retryInfoMillis = 20_000)
            else GeminiSttRequestPolicy.Reply(200, "ok")
        }, jitter = { 250 })
        assertEquals(2, calls)
        assertEquals(20_000L, clock.now - initial)
        policy.execute({}, { calls++; GeminiSttRequestPolicy.Reply(400, "invalid") })
        assertEquals(3, calls)
        assertTrue(policy.statistics().contains("erros: 2"))
    }

    @Test fun daily429IsNotRetriedAndBlocksOtherFiles() {
        val clock = Clock()
        val policy = GeminiSttRequestPolicy(now = { clock.now }, sleep = clock::sleep)
        var calls = 0
        policy.execute({}, { calls++; GeminiSttRequestPolicy.Reply(429, "quota", dailyExhausted = true) })
        assertEquals(1, calls)
        assertThrows(IllegalStateException::class.java) { policy.begin {} }
        assertTrue(policy.statistics().contains("API informou"))
    }

    @Test fun pacingAndBackoffCanBeCancelled() {
        val clock = Clock()
        val policy = GeminiSttRequestPolicy(now = { clock.now }, sleep = clock::sleep)
        policy.begin {}; policy.end()
        assertThrows(CancellationException::class.java) { policy.begin { throw CancellationException() } }
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            policy.execute({ if (cancelled) throw CancellationException() }, {
                cancelled = true; GeminiSttRequestPolicy.Reply(429, "quota")
            })
        }
    }

    @Test fun optionalHeadersAreExplicitlySeparatedFromLocalDailyBalance() {
        val policy = GeminiSttRequestPolicy()
        assertTrue(policy.statistics().contains("não informado"))
        policy.begin {}; policy.end()
        policy.observe(GeminiSttRequestPolicy.Reply(200, "ok", mapOf("X-RateLimit-Remaining-Requests" to "2")))
        val text = policy.statistics()
        assertTrue(text.contains("janela não especificada"))
        assertTrue(text.contains("Requisições restantes: 2"))
        assertTrue(text.contains("Saldo diário local: 99"))
        assertTrue(GeminiSttRequestPolicy.showWarning(true, true))
        assertFalse(GeminiSttRequestPolicy.showWarning(false, true))
        assertFalse(GeminiSttRequestPolicy.showWarning(true, false))
    }

    @Test fun googleQuotaFailureAndRetryInfoAreParsed() {
        val raw = """{"error":{"details":[{"violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_requests_per_model_per_day"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"22.5s"}]}}"""
        assertEquals(true to 22_500L, GeminiSttProtocol.quotaErrorDetails(raw))
        assertEquals(false to 0L, GeminiSttProtocol.quotaErrorDetails("invalid"))
    }

    @Test fun okhttpWireRetriesAreCappedAndFilesDoNotConsumeModelQuota() {
        val server = MockWebServer()
        server.start()
        try {
            val clock = Clock()
            val state = GeminiSttRequestPolicy.State()
            val policy = GeminiSttRequestPolicy(state, now = { clock.now }, sleep = clock::sleep)
            val url = server.url("/interactions").toString()
            val client = GeminiSttHttpClient.create(OkHttpClient(), policy, {}, inferenceUrl = url)
            server.enqueue(MockResponse().setBody("files"))
            client.newCall(Request.Builder().url(server.url("/files")).build()).execute().close()
            assertEquals(0, state.used)
            repeat(5) { server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("capacity")) }
            policy.execute({}, {
                client.newCall(Request.Builder().url(url).post("audio".toRequestBody()).build()).execute().use { response ->
                    GeminiSttRequestPolicy.Reply(response.code, response.body!!.string(), mapOf("Retry-After" to response.header("Retry-After").orEmpty()))
                }
            }, jitter = { 0 })
            assertEquals(6, server.requestCount)
            assertEquals(5, state.used)
            assertEquals(5, state.errors)
        } finally { server.shutdown() }
    }
}
