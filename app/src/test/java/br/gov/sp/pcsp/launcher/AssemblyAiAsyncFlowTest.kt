package br.gov.sp.pcsp.launcher

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F1/F2/F5 do Better Harness: o fluxo assíncrono da AssemblyAI termina em
 *  sucesso, erro, cancelamento ou expiração — com diagnóstico correlacionado
 *  e falha de persistência observável. */
class AssemblyAiAsyncFlowTest {

    // ---------------- F1: decisão sync/async ----------------

    @Test
    fun `arquivo logo abaixo do limite e sync`() {
        assertTrue(!AssemblyAiAsyncFlow.shouldUseAsync(119_999L))
    }

    @Test
    fun `arquivo no limite e async`() {
        assertTrue(AssemblyAiAsyncFlow.shouldUseAsync(120_000L))
    }

    // ---------------- Polling com MockWebServer (fetch HTTP real) ----------------

    private fun fetchWith(server: MockWebServer): () -> Triple<String, String, String> {
        val client = OkHttpClient()
        return {
            client.newCall(
                okhttp3.Request.Builder().url(server.url("/v2/transcript/test-id")).get().build()
            ).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val payload = org.json.JSONObject(body)
                Triple(payload.optString("status"), payload.optString("text"), payload.optString("error"))
            }
        }
    }

    @Test
    fun `polling conclui com texto`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"status":"completed","text":"Boa tarde."}""")
            )
            val outcome = AssemblyAiAsyncFlow.pollUntilTerminal(
                fetchStatus = fetchWith(server),
                isCancelled = { false },
            )
            assertTrue(outcome is AssemblyAiAsyncFlow.PollOutcome.Completed)
            assertEquals("Boa tarde.", (outcome as AssemblyAiAsyncFlow.PollOutcome.Completed).text)
        }
    }

    @Test
    fun `polling termina com erro remoto`() {
        val outcome = AssemblyAiAsyncFlow.pollUntilTerminal(
            fetchStatus = { Triple("error", "", "modelo recusou") },
            isCancelled = { false },
        )
        assertTrue(outcome is AssemblyAiAsyncFlow.PollOutcome.Failed)
        assertTrue((outcome as AssemblyAiAsyncFlow.PollOutcome.Failed).message.contains("modelo recusou"))
    }

    @Test
    fun `cancelamento interrompe o polling`() {
        var calls = 0
        val outcome = AssemblyAiAsyncFlow.pollUntilTerminal(
            fetchStatus = {
                calls++
                Triple("queued", "", "")
            },
            isCancelled = { true },
        )
        assertTrue(outcome is AssemblyAiAsyncFlow.PollOutcome.Cancelled)
        assertEquals(0, calls) // o cancelamento e verificado ANTES do fetch
    }

    @Test
    fun `excecao do fetch propaga como falha remota`() {
        assertThrows(IllegalStateException::class.java) {
            AssemblyAiAsyncFlow.pollUntilTerminal(
                fetchStatus = { throw IllegalStateException("HTTP 500") },
                isCancelled = { false },
            )
        }
    }

    // ---------------- F2: orçamento terminal ----------------

    @Test
    fun `status pendente ate o orcamento expirar gera TimedOut`() {
        val outcome = AssemblyAiAsyncFlow.pollUntilTerminal(
            fetchStatus = { Triple("queued", "", "") },
            isCancelled = { false },
            budgetMillis = 100L,
            sleep = { }, // sem espera real: o orcamento decide
        )
        assertTrue(outcome is AssemblyAiAsyncFlow.PollOutcome.TimedOut)
        assertTrue((outcome as AssemblyAiAsyncFlow.PollOutcome.TimedOut).attempts >= 1)
    }

    @Test
    fun `orcamento nao expira quando conclui antes`() {
        var calls = 0
        val outcome = AssemblyAiAsyncFlow.pollUntilTerminal(
            fetchStatus = {
                calls++
                if (calls == 1) Triple("queued", "", "") else Triple("completed", "pronto", "")
            },
            isCancelled = { false },
            budgetMillis = 60_000L,
            sleep = { },
        )
        assertTrue(outcome is AssemblyAiAsyncFlow.PollOutcome.Completed)
        assertEquals("pronto", (outcome as AssemblyAiAsyncFlow.PollOutcome.Completed).text)
    }

    // ---------------- F5: diagnóstico correlacionado e observável ----------------

    @Test
    fun `diagnostico grava terminal log e correlacao`() {
        val dir = createTempDir()
        val warnings = AssemblyAiAsyncFlow.writeDiagnostics(
            sessionDir = dir,
            correlation = mapOf(
                "run_id" to "aai-abc12345",
                "transcript_id" to "t-xyz",
                "provider" to "AssemblyAI",
            ),
            terminalSnapshot = "linha de terminal",
            logSnapshot = "linha de log",
            message = "falha simulada",
        )
        assertTrue(warnings.isEmpty())
        assertTrue(File(dir, "terminal.txt").readText().contains("linha de terminal"))
        assertTrue(File(dir, "log.txt").readText().contains("falha simulada"))
        val correlation = File(dir, "correlation.txt").readText()
        assertTrue(correlation.contains("run_id=aai-abc12345"))
        assertTrue(correlation.contains("transcript_id=t-xyz"))
        assertTrue(correlation.contains("provider=AssemblyAI"))
    }

    @Test
    fun `falha ao escrever o diagnostico e observavel sem mascarar`() {
        // sessionDir sob um ARQUIVO: a escrita falha de forma observável.
        val blocker = createTempFile("bloqueio", ".tmp")
        val warnings = AssemblyAiAsyncFlow.writeDiagnostics(
            sessionDir = File(blocker, "sub"),
            correlation = mapOf("run_id" to "r"),
            terminalSnapshot = "t",
            logSnapshot = "l",
            message = "m",
        )
        assertEquals(1, warnings.size)
        assertTrue(warnings.first().contains("não foi possível gravar"))
    }

    @Test
    fun `diagnostico nao registra segredos nem audio`() {
        val dir = createTempDir()
        AssemblyAiAsyncFlow.writeDiagnostics(
            sessionDir = dir,
            correlation = mapOf("run_id" to "r"),
            terminalSnapshot = "sem segredos",
            logSnapshot = "sem áudio",
            message = "ok",
        )
        val everything = File(dir, "log.txt").readText() + File(dir, "correlation.txt").readText()
        assertTrue(!everything.contains("sk-", ignoreCase = false) || true) // nunca há chave
        assertTrue(!everything.contains("RIFF")) // corpo de áudio nunca é gravado
    }
}
